//! link.rs: connects to the phone over adb, tethering, or its Wi-Fi hotspot, runs the handshake and keepalive, and feeds the injector.

use crate::pen::Rect;
use crate::store::Mode;
use padlink_protocol::{encode, status, Frame, FrameDecoder, MouseFrame, PenFrame, TOKEN_LEN, VERSION};
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::Mutex;
use std::time::{Duration, Instant};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(3);
const PAIRING_TIMEOUT: Duration = Duration::from_secs(90);
const PING_INTERVAL: Duration = Duration::from_secs(1);
const LINK_TIMEOUT: Duration = Duration::from_secs(3);
const POLL: Duration = Duration::from_millis(20);
const RETRY_DELAY: Duration = Duration::from_secs(1);

/// Receives decoded input; implemented with SendInput and a synthetic pen on Windows.
pub trait Injector {
    /// Injects one mouse frame.
    fn mouse(&mut self, frame: &MouseFrame);
    /// Injects one pen frame onto the target monitor.
    fn pen(&mut self, frame: &PenFrame);
    /// Releases held buttons and lifts the pen.
    fn release_all(&mut self);
    /// Changes the pen target monitor.
    fn set_target(&mut self, rect: Rect);
}

/// What the tray shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Status {
    /// Looking for the phone; the text says what is missing.
    Searching(String),
    /// Waiting for the user to approve this computer on the phone.
    Pairing(u32),
    /// Delivering input.
    Connected,
    /// The phone refused this helper.
    Rejected,
    /// Phone and helper speak different protocol versions.
    VersionMismatch,
}

/// State shared between the tray thread and the link thread.
pub struct Shared {
    /// Selected path to the phone.
    pub mode: Mutex<Mode>,
    /// Pen target monitor rectangle and size reported to the phone.
    pub target: Mutex<Rect>,
    /// Latest status for the tray.
    pub status: Mutex<Status>,
    /// Bumped when the mode changes so the link reconnects.
    pub mode_generation: AtomicU64,
    /// Bumped when the target monitor changes.
    pub target_generation: AtomicU64,
    /// Set when the helper exits.
    pub stop: AtomicBool,
}

impl Shared {
    /// Creates shared state for `mode` and the initial pen target.
    pub fn new(mode: Mode, target: Rect) -> Self {
        Self {
            mode: Mutex::new(mode),
            target: Mutex::new(target),
            status: Mutex::new(Status::Searching(String::new())),
            mode_generation: AtomicU64::new(0),
            target_generation: AtomicU64::new(0),
            stop: AtomicBool::new(false),
        }
    }

    /// Publishes a status.
    fn set_status(&self, s: Status) {
        *self.status.lock().unwrap_or_else(|e| e.into_inner()) = s;
    }

    /// Current mode.
    fn mode(&self) -> Mode {
        *self.mode.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Current pen target.
    fn target(&self) -> Rect {
        *self.target.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// Identity sent in Hello.
pub struct Identity {
    /// Persistent helper token.
    pub token: [u8; TOKEN_LEN],
    /// Computer name shown on the phone.
    pub name: String,
}

/// Why a session ended.
#[derive(Debug)]
enum End {
    /// Stop requested or mode changed.
    Local,
    /// Phone said Bye or closed the socket.
    Closed,
    /// The socket closed before any frame arrived. The phone app always answers Hello, so this
    /// means something between the helper and the app accepted and dropped the connection.
    Unanswered,
    /// Nothing received within the link timeout.
    Timeout,
    /// Phone refused this helper.
    Rejected,
    /// Version mismatch.
    Version,
    /// Socket or framing error.
    Io(String),
}

/// Where to connect, and the local address to bind first (tethering and hotspot modes).
#[derive(Debug, Clone, Copy)]
struct Endpoint {
    addr: SocketAddr,
    local: Option<IpAddr>,
}

impl From<crate::tether::Route> for Endpoint {
    /// Connects to the route's gateway from this PC's address on the same adapter.
    fn from(r: crate::tether::Route) -> Self {
        Self { addr: r.gateway, local: Some(r.local) }
    }
}

/// Resolves where to connect for `mode`.
fn endpoint(mode: Mode) -> Result<Endpoint, String> {
    match mode {
        Mode::Adb => crate::adb::prepare_forward()
            .map(|(addr, _)| Endpoint { addr, local: None })
            .map_err(|e| e.to_string()),
        Mode::Tether => tether_endpoint(),
        Mode::Hotspot => hotspot_endpoint(),
    }
}

/// Tethering endpoint (Windows adapter lookup).
#[cfg(windows)]
fn tether_endpoint() -> Result<Endpoint, String> {
    crate::tether::gateway().map(Endpoint::from).map_err(|e| e.to_string())
}

/// Tethering endpoint on platforms without adapter lookup.
#[cfg(not(windows))]
fn tether_endpoint() -> Result<Endpoint, String> {
    Err(crate::tether::TetherError::NoAdapter.to_string())
}

/// Hotspot endpoint: the gateway of the Wi-Fi adapter (Windows adapter lookup).
#[cfg(windows)]
fn hotspot_endpoint() -> Result<Endpoint, String> {
    crate::tether::hotspot_gateway().map(Endpoint::from).map_err(|e| e.to_string())
}

/// Hotspot endpoint on platforms without adapter lookup.
#[cfg(not(windows))]
fn hotspot_endpoint() -> Result<Endpoint, String> {
    Err(crate::tether::TetherError::NoWifi.to_string())
}

/// Connects to `ep.addr`, first binding `ep.local` so Windows sends the traffic out of that
/// adapter instead of any other adapter or VPN that also routes the phone's address.
fn connect(ep: Endpoint) -> std::io::Result<TcpStream> {
    use socket2::{Domain, Protocol, SockAddr, Socket, Type};
    let socket = Socket::new(Domain::for_address(ep.addr), Type::STREAM, Some(Protocol::TCP))?;
    if let Some(local) = ep.local {
        socket.bind(&SockAddr::from(SocketAddr::new(local, 0)))?;
    }
    socket.connect_timeout(&SockAddr::from(ep.addr), CONNECT_TIMEOUT)?;
    Ok(socket.into())
}

/// Tray text when the endpoint exists but nothing accepts the connection. In hotspot mode the
/// Wi-Fi gateway is usually a home router rather than the phone.
fn connect_failure(mode: Mode, addr: SocketAddr) -> String {
    match mode {
        Mode::Hotspot => format!(
            "this Wi-Fi network is not the phone's hotspot, or PadLink is not set to Wi-Fi (Phone hotspot) [{addr}]"
        ),
        _ => format!("start PadLink on the phone and pick this USB mode [{addr}]"),
    }
}

/// Link thread body: connect, run a session, release input, retry, until `shared.stop`.
pub fn run(shared: &Shared, identity: &Identity, injector: &mut dyn Injector, on_pair_code: &dyn Fn(u32)) {
    while !shared.stop.load(Ordering::Relaxed) {
        let mode = shared.mode();
        let generation = shared.mode_generation.load(Ordering::Relaxed);
        let ep = match endpoint(mode) {
            Ok(ep) => ep,
            Err(reason) => {
                shared.set_status(Status::Searching(reason));
                sleep_unless_stopped(shared, RETRY_DELAY * 2);
                continue;
            }
        };
        let addr = ep.addr;
        let stream = match connect(ep) {
            Ok(s) => s,
            Err(_) => {
                shared.set_status(Status::Searching(connect_failure(mode, addr)));
                sleep_unless_stopped(shared, RETRY_DELAY);
                continue;
            }
        };
        let (end, connected) = session(stream, shared, identity, injector, generation, on_pair_code);
        injector.release_all();
        match &end {
            End::Rejected => shared.set_status(Status::Rejected),
            End::Version => shared.set_status(Status::VersionMismatch),
            End::Local => {}
            other => shared.set_status(Status::Searching(format!("{} [{addr}]", drop_reason(other, connected)))),
        }
        if !matches!(end, End::Local) {
            let delay = if matches!(end, End::Rejected | End::Version) { RETRY_DELAY * 5 } else { RETRY_DELAY };
            sleep_unless_stopped(shared, delay);
        }
    }
}

/// Tray text for a session that ended on its own; `connected` tells whether the handshake had finished.
fn drop_reason(end: &End, connected: bool) -> String {
    match (end, connected) {
        (End::Unanswered, _) => "the connection was closed before the phone answered. If the phone shows no attempt \
             from this PC, a VPN or security software on this PC is intercepting it; retrying"
            .into(),
        (End::Closed, false) => "the phone closed the connection during the handshake; retrying".into(),
        (End::Timeout, false) => "the phone did not answer the handshake; retrying".into(),
        (End::Closed, true) => "the phone ended the connection; reconnecting".into(),
        (End::Timeout, true) => "no data from the phone for 3 s; reconnecting".into(),
        (End::Io(msg), _) => format!("connection error ({msg}); reconnecting"),
        _ => "reconnecting".into(),
    }
}

/// Sleeps up to `d`, waking early on stop.
fn sleep_unless_stopped(shared: &Shared, d: Duration) {
    let until = Instant::now() + d;
    while !shared.stop.load(Ordering::Relaxed) && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// Socket writer plus frames decoded by a dedicated blocking reader thread.
///
/// Windows leaves a socket in an indeterminate state (bytes may be lost) after a receive
/// timeout, so the socket never gets a read timeout; waiting with a deadline happens on the
/// channel instead.
struct Conn {
    stream: TcpStream,
    frames: Receiver<Result<Frame, End>>,
}

impl Conn {
    /// Starts the reader thread for `stream`.
    fn new(stream: TcpStream) -> Result<Self, End> {
        let reader = stream.try_clone().map_err(|e| End::Io(e.to_string()))?;
        let (tx, frames) = mpsc::channel();
        std::thread::Builder::new()
            .name("padlink-reader".into())
            .spawn(move || read_frames(reader, &tx))
            .map_err(|e| End::Io(e.to_string()))?;
        Ok(Self { stream, frames })
    }

    /// Writes one frame.
    fn send(&mut self, frame: &Frame) -> Result<(), End> {
        self.stream.write_all(&encode(frame)).map_err(|e| End::Io(e.to_string()))
    }

    /// Returns the next frame, `Ok(None)` after one poll interval without one.
    fn poll(&mut self) -> Result<Option<Frame>, End> {
        match self.frames.recv_timeout(POLL) {
            Ok(Ok(frame)) => Ok(Some(frame)),
            Ok(Err(end)) => Err(end),
            Err(RecvTimeoutError::Timeout) => Ok(None),
            Err(RecvTimeoutError::Disconnected) => Err(End::Closed),
        }
    }

    /// Waits for a frame until `deadline`, aborting on stop or mode change.
    fn wait(&mut self, deadline: Instant, shared: &Shared, generation: u64) -> Result<Frame, End> {
        loop {
            if local_end(shared, generation) {
                return Err(End::Local);
            }
            if let Some(f) = self.poll()? {
                return Ok(f);
            }
            if Instant::now() >= deadline {
                return Err(End::Timeout);
            }
        }
    }
}

impl Drop for Conn {
    /// Shuts the socket down so the reader thread's blocking read returns and the thread exits.
    fn drop(&mut self) {
        let _ = self.stream.shutdown(Shutdown::Both);
    }
}

/// Reader thread body: blocking reads, decoded frames forwarded until EOF, error, or hang-up.
fn read_frames(mut stream: TcpStream, tx: &Sender<Result<Frame, End>>) {
    let mut decoder = FrameDecoder::new();
    let mut buf = [0u8; 1024];
    loop {
        let n = match stream.read(&mut buf) {
            Ok(0) => {
                let _ = tx.send(Err(End::Closed));
                return;
            }
            Ok(n) => n,
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => {
                let _ = tx.send(Err(End::Io(e.to_string())));
                return;
            }
        };
        decoder.push(&buf[..n]);
        loop {
            match decoder.next_frame() {
                Ok(Some(frame)) => {
                    if tx.send(Ok(frame)).is_err() {
                        return;
                    }
                }
                Ok(None) => break,
                Err(e) => {
                    let _ = tx.send(Err(End::Io(e.to_string())));
                    return;
                }
            }
        }
    }
}

/// True when the helper is stopping or the user switched modes.
fn local_end(shared: &Shared, generation: u64) -> bool {
    shared.stop.load(Ordering::Relaxed) || shared.mode_generation.load(Ordering::Relaxed) != generation
}

/// HostInfo for a target monitor.
fn host_info(rect: &Rect) -> Frame {
    Frame::HostInfo {
        width: rect.width().clamp(0, i32::from(u16::MAX)) as u16,
        height: rect.height().clamp(0, i32::from(u16::MAX)) as u16,
    }
}

/// One connection: Hello, Welcome (with optional pairing), HostInfo, then the input loop.
/// Returns why it ended and whether the handshake had completed.
fn session(
    stream: TcpStream,
    shared: &Shared,
    identity: &Identity,
    injector: &mut dyn Injector,
    generation: u64,
    on_pair_code: &dyn Fn(u32),
) -> (End, bool) {
    if stream.set_nodelay(true).is_err() {
        return (End::Io("socket setup failed".into()), false);
    }
    let mut conn = match Conn::new(stream) {
        Ok(conn) => conn,
        Err(end) => return (end, false),
    };
    match handshake(&mut conn, shared, identity, generation, on_pair_code) {
        Ok(()) => {}
        Err(end) => {
            if matches!(end, End::Local) {
                let _ = conn.send(&Frame::Bye);
            }
            return (end, false);
        }
    }
    shared.set_status(Status::Connected);
    let end = match input_loop(&mut conn, shared, injector, generation) {
        Ok(()) => End::Closed,
        Err(end) => end,
    };
    if matches!(end, End::Local) {
        let _ = conn.send(&Frame::Bye);
    }
    (end, true)
}

/// Sends Hello and waits for an accepted Welcome, handling tethering approval.
fn handshake(
    conn: &mut Conn,
    shared: &Shared,
    identity: &Identity,
    generation: u64,
    on_pair_code: &dyn Fn(u32),
) -> Result<(), End> {
    conn.send(&Frame::Hello { version: VERSION, token: identity.token, name: identity.name.clone() })?;
    let mut deadline = Instant::now() + HANDSHAKE_TIMEOUT;
    let mut answered = false;
    loop {
        let frame = match conn.wait(deadline, shared, generation) {
            Ok(frame) => frame,
            Err(End::Closed) if !answered => return Err(End::Unanswered),
            Err(end) => return Err(end),
        };
        answered = true;
        match frame {
            Frame::Welcome { status: status::OK, .. } => return Ok(()),
            Frame::Welcome { status: status::PAIRING, .. } => deadline = Instant::now() + PAIRING_TIMEOUT,
            Frame::Welcome { status: status::VERSION_MISMATCH, .. } => return Err(End::Version),
            Frame::Welcome { .. } => return Err(End::Rejected),
            Frame::PairCode { code } => {
                shared.set_status(Status::Pairing(code));
                on_pair_code(code);
            }
            Frame::Bye => return Err(End::Closed),
            _ => {}
        }
    }
}

/// Receives input until the link ends; always returns `Err` with the reason.
fn input_loop(conn: &mut Conn, shared: &Shared, injector: &mut dyn Injector, generation: u64) -> Result<(), End> {
    let mut target_generation = shared.target_generation.load(Ordering::Relaxed);
    let target = shared.target();
    injector.set_target(target);
    conn.send(&host_info(&target))?;
    let mut last_rx = Instant::now();
    let mut last_ping = Instant::now();
    let mut nonce: u32 = 0;
    loop {
        if local_end(shared, generation) {
            return Err(End::Local);
        }
        let current = shared.target_generation.load(Ordering::Relaxed);
        if current != target_generation {
            target_generation = current;
            let target = shared.target();
            injector.release_all();
            injector.set_target(target);
            conn.send(&host_info(&target))?;
        }
        if let Some(frame) = conn.poll()? {
            last_rx = Instant::now();
            match frame {
                Frame::Mouse(m) => injector.mouse(&m),
                Frame::Pen(p) => injector.pen(&p),
                Frame::Ping { nonce } => conn.send(&Frame::Pong { nonce })?,
                Frame::Bye => return Err(End::Closed),
                _ => {}
            }
        }
        if last_rx.elapsed() > LINK_TIMEOUT {
            return Err(End::Timeout);
        }
        if last_ping.elapsed() >= PING_INTERVAL {
            last_ping = Instant::now();
            nonce = nonce.wrapping_add(1);
            conn.send(&Frame::Ping { nonce })?;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::Arc;

    /// Injector that records calls.
    #[derive(Default)]
    struct Recorder {
        mice: Vec<MouseFrame>,
        pens: Vec<PenFrame>,
        releases: usize,
        targets: Vec<Rect>,
    }

    impl Injector for Recorder {
        /// Records a mouse frame.
        fn mouse(&mut self, frame: &MouseFrame) {
            self.mice.push(*frame);
        }
        /// Records a pen frame.
        fn pen(&mut self, frame: &PenFrame) {
            self.pens.push(*frame);
        }
        /// Counts releases.
        fn release_all(&mut self) {
            self.releases += 1;
        }
        /// Records a target change.
        fn set_target(&mut self, rect: Rect) {
            self.targets.push(rect);
        }
    }

    const TARGET: Rect = Rect { left: 0, top: 0, right: 1920, bottom: 1080 };

    /// Reads one frame from a blocking test socket.
    fn read_frame(s: &mut TcpStream) -> Frame {
        let mut len = [0u8; 2];
        s.read_exact(&mut len).unwrap();
        let mut body = vec![0u8; u16::from_be_bytes(len) as usize];
        s.read_exact(&mut body).unwrap();
        padlink_protocol::decode_body(body[0], &body[1..]).unwrap()
    }

    /// Full session against a fake phone: pairing, HostInfo, input, Ping/Pong, and Bye.
    #[test]
    fn session_with_pairing_delivers_input() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let phone = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let hello = read_frame(&mut s);
            assert!(matches!(hello, Frame::Hello { version: VERSION, ref name, .. } if name == "PC"));
            s.write_all(&encode(&Frame::Welcome { version: VERSION, status: status::PAIRING })).unwrap();
            s.write_all(&encode(&Frame::PairCode { code: 123456 })).unwrap();
            std::thread::sleep(Duration::from_millis(100));
            s.write_all(&encode(&Frame::Welcome { version: VERSION, status: status::OK })).unwrap();
            assert_eq!(read_frame(&mut s), Frame::HostInfo { width: 1920, height: 1080 });
            let m = MouseFrame { buttons: 1, dx: 4, dy: -2, wheel: 0, pan: 0 };
            s.write_all(&encode(&Frame::Mouse(m))).unwrap();
            let p = PenFrame { flags: 3, x: 10, y: 20, pressure: 30, tilt_x: 0, tilt_y: 0, twist: 0 };
            s.write_all(&encode(&Frame::Pen(p))).unwrap();
            s.write_all(&encode(&Frame::Ping { nonce: 77 })).unwrap();
            loop {
                if let Frame::Pong { nonce } = read_frame(&mut s) {
                    assert_eq!(nonce, 77);
                    break;
                }
            }
            s.write_all(&encode(&Frame::Bye)).unwrap();
        });
        let shared = Shared::new(Mode::Adb, TARGET);
        let identity = Identity { token: [7; TOKEN_LEN], name: "PC".into() };
        let codes = Arc::new(Mutex::new(Vec::new()));
        let codes_in = codes.clone();
        let mut rec = Recorder::default();
        let stream = TcpStream::connect(addr).unwrap();
        let (end, connected) = session(stream, &shared, &identity, &mut rec, 0, &move |c| codes_in.lock().unwrap().push(c));
        phone.join().unwrap();
        assert!(matches!(end, End::Closed), "{end:?}");
        assert!(connected);
        assert_eq!(*codes.lock().unwrap(), vec![123456]);
        assert_eq!(rec.mice.len(), 1);
        assert_eq!(rec.pens.len(), 1);
        assert_eq!(rec.targets, vec![TARGET]);
    }

    /// Writes `frame` one byte at a time with pauses longer than the poll interval.
    fn trickle(s: &mut TcpStream, frame: &Frame) {
        for b in encode(frame) {
            s.write_all(&[b]).unwrap();
            std::thread::sleep(POLL + Duration::from_millis(5));
        }
    }

    /// Frames arriving byte by byte across many poll intervals, after a long approval wait,
    /// are reassembled without loss and the session keeps running.
    #[test]
    fn slow_bytes_survive_poll_intervals() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let phone = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            read_frame(&mut s);
            trickle(&mut s, &Frame::Welcome { version: VERSION, status: status::PAIRING });
            trickle(&mut s, &Frame::PairCode { code: 654321 });
            std::thread::sleep(Duration::from_millis(1500));
            trickle(&mut s, &Frame::Welcome { version: VERSION, status: status::OK });
            assert_eq!(read_frame(&mut s), Frame::HostInfo { width: 1920, height: 1080 });
            let m = MouseFrame { buttons: 0, dx: -300, dy: 120, wheel: 0, pan: 0 };
            trickle(&mut s, &Frame::Mouse(m));
            trickle(&mut s, &Frame::Mouse(m));
            s.write_all(&encode(&Frame::Bye)).unwrap();
        });
        let shared = Shared::new(Mode::Tether, TARGET);
        let identity = Identity { token: [3; TOKEN_LEN], name: "PC".into() };
        let mut rec = Recorder::default();
        let (end, connected) = session(TcpStream::connect(addr).unwrap(), &shared, &identity, &mut rec, 0, &|_| {});
        phone.join().unwrap();
        assert!(matches!(end, End::Closed), "{end:?}");
        assert!(connected);
        assert_eq!(rec.mice.len(), 2);
        assert_eq!(rec.mice[1].dx, -300);
    }

    /// A rejected helper ends with `Rejected`.
    #[test]
    fn rejected_welcome_ends_session() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let phone = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            read_frame(&mut s);
            s.write_all(&encode(&Frame::Welcome { version: VERSION, status: status::REJECTED })).unwrap();
        });
        let shared = Shared::new(Mode::Tether, TARGET);
        let identity = Identity { token: [0; TOKEN_LEN], name: "PC".into() };
        let mut rec = Recorder::default();
        let (end, connected) = session(TcpStream::connect(addr).unwrap(), &shared, &identity, &mut rec, 0, &|_| {});
        phone.join().unwrap();
        assert!(matches!(end, End::Rejected), "{end:?}");
        assert!(!connected);
    }

    /// Drop reasons name the stage and the cause.
    #[test]
    fn drop_reasons_name_the_stage() {
        assert!(drop_reason(&End::Closed, false).contains("handshake"));
        assert!(drop_reason(&End::Timeout, true).contains("3 s"));
        assert!(drop_reason(&End::Io("reset".into()), true).contains("reset"));
    }

    /// A peer that accepts and closes before answering Hello is reported as an interception, not a phone close.
    #[test]
    fn close_before_any_frame_is_unanswered() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let phone = std::thread::spawn(move || {
            let (s, _) = listener.accept().unwrap();
            drop(s);
        });
        let shared = Shared::new(Mode::Hotspot, TARGET);
        let identity = Identity { token: [0; TOKEN_LEN], name: "PC".into() };
        let mut rec = Recorder::default();
        let (end, connected) = session(TcpStream::connect(addr).unwrap(), &shared, &identity, &mut rec, 0, &|_| {});
        phone.join().unwrap();
        assert!(matches!(end, End::Unanswered), "{end:?}");
        assert!(!connected);
        assert!(drop_reason(&end, connected).contains("VPN or security software"));
    }

    /// Connecting with a bound local address reaches the server from that address.
    #[test]
    fn connect_binds_the_local_address() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let local: IpAddr = "127.0.0.1".parse().unwrap();
        let stream = connect(Endpoint { addr, local: Some(local) }).unwrap();
        let (_, peer) = listener.accept().unwrap();
        assert_eq!(peer.ip(), local);
        assert_eq!(stream.local_addr().unwrap().ip(), local);
    }

    /// A refused connection in hotspot mode points at the wrong Wi-Fi network; USB modes keep the USB hint.
    #[test]
    fn connect_failure_names_the_network() {
        let addr: SocketAddr = "192.168.1.1:47800".parse().unwrap();
        let hotspot = connect_failure(Mode::Hotspot, addr);
        assert!(hotspot.contains("not the phone's hotspot"));
        assert!(hotspot.contains("192.168.1.1:47800"));
        assert!(connect_failure(Mode::Tether, addr).contains("USB mode"));
    }

    /// A silent phone times out instead of hanging.
    #[test]
    fn silent_phone_times_out() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let phone = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            read_frame(&mut s);
            std::thread::sleep(HANDSHAKE_TIMEOUT + Duration::from_millis(500));
        });
        let shared = Shared::new(Mode::Adb, TARGET);
        let identity = Identity { token: [0; TOKEN_LEN], name: "PC".into() };
        let mut rec = Recorder::default();
        let (end, connected) = session(TcpStream::connect(addr).unwrap(), &shared, &identity, &mut rec, 0, &|_| {});
        phone.join().unwrap();
        assert!(matches!(end, End::Timeout), "{end:?}");
        assert!(!connected);
    }
}
