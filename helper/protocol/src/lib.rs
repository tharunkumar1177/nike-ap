//! lib.rs: frames, big-endian codec, and stream decoder of the PadLink wired protocol (version 1).

use std::fmt;

/// Protocol version spoken by this crate.
pub const VERSION: u8 = 1;
/// TCP port the phone listens on.
pub const PORT: u16 = 47800;
/// Largest allowed `len` field (type byte plus payload).
pub const MAX_LEN: usize = 512;
/// Helper token size in bytes.
pub const TOKEN_LEN: usize = 16;

/// Welcome status codes.
pub mod status {
    /// Link accepted.
    pub const OK: u8 = 0;
    /// Tethering approval pending; a PairCode follows.
    pub const PAIRING: u8 = 1;
    /// The user denied the helper.
    pub const REJECTED: u8 = 2;
    /// Protocol versions differ.
    pub const VERSION_MISMATCH: u8 = 3;
}

/// Pen frame flag bits.
pub mod pen_flags {
    /// Pen hovers or touches.
    pub const IN_RANGE: u8 = 0x01;
    /// Tip (or eraser) touches the surface.
    pub const TIP: u8 = 0x02;
    /// Barrel button held.
    pub const BARREL: u8 = 0x04;
    /// Erasing.
    pub const ERASER: u8 = 0x08;
    /// Eraser end is toward the surface.
    pub const INVERT: u8 = 0x10;
}

/// Mouse button bits in [`MouseFrame::buttons`].
pub mod buttons {
    /// Left button.
    pub const LEFT: u8 = 0x01;
    /// Right button.
    pub const RIGHT: u8 = 0x02;
    /// Middle button.
    pub const MIDDLE: u8 = 0x04;
}

const TYPE_HELLO: u8 = 0x01;
const TYPE_WELCOME: u8 = 0x02;
const TYPE_PAIR_CODE: u8 = 0x03;
const TYPE_HOST_INFO: u8 = 0x04;
const TYPE_MOUSE: u8 = 0x10;
const TYPE_PEN: u8 = 0x11;
const TYPE_PING: u8 = 0x20;
const TYPE_PONG: u8 = 0x21;
const TYPE_BYE: u8 = 0x30;

/// Relative mouse input with the full button mask.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct MouseFrame {
    /// Held buttons ([`buttons`] bits).
    pub buttons: u8,
    /// Horizontal motion in counts, positive right.
    pub dx: i16,
    /// Vertical motion in counts, positive down.
    pub dy: i16,
    /// Vertical wheel in 1/120 notches, positive scrolls up.
    pub wheel: i16,
    /// Horizontal wheel in 1/120 notches, positive scrolls right.
    pub pan: i16,
}

/// Absolute pen input over the tablet area.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct PenFrame {
    /// [`pen_flags`] bits.
    pub flags: u8,
    /// 0..=32767 across the target monitor.
    pub x: u16,
    /// 0..=32767 down the target monitor.
    pub y: u16,
    /// 0..=4095.
    pub pressure: u16,
    /// Degrees, -60..=60, positive right.
    pub tilt_x: i8,
    /// Degrees, -60..=60, positive toward the user.
    pub tilt_y: i8,
    /// Degrees 0..=359.
    pub twist: u16,
}

/// One protocol frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    /// Helper introduction.
    Hello { version: u8, token: [u8; TOKEN_LEN], name: String },
    /// Phone answer to Hello.
    Welcome { version: u8, status: u8 },
    /// Six-digit approval code.
    PairCode { code: u32 },
    /// Pixel size of the pen target monitor.
    HostInfo { width: u16, height: u16 },
    /// Mouse input.
    Mouse(MouseFrame),
    /// Pen input.
    Pen(PenFrame),
    /// Keepalive request.
    Ping { nonce: u32 },
    /// Keepalive answer.
    Pong { nonce: u32 },
    /// Orderly close.
    Bye,
    /// Frame type unknown to this version; receivers ignore it.
    Unknown { kind: u8 },
}

/// Malformed bytes on the wire.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// `len` outside 1..=MAX_LEN.
    BadLength(usize),
    /// Payload shorter than the frame type requires.
    Truncated { kind: u8, needed: usize, got: usize },
    /// Hello name is not UTF-8.
    BadName,
}

impl fmt::Display for Error {
    /// Formats the error for logs and dialogs.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::BadLength(len) => write!(f, "bad frame length {len}"),
            Error::Truncated { kind, needed, got } => {
                write!(f, "frame 0x{kind:02x} needs {needed} bytes, got {got}")
            }
            Error::BadName => write!(f, "hello name is not UTF-8"),
        }
    }
}

impl std::error::Error for Error {}

/// Encodes `frame` with its length prefix.
///
/// # Panics
/// Panics when asked to encode [`Frame::Unknown`], which has no payload layout.
pub fn encode(frame: &Frame) -> Vec<u8> {
    let mut body: Vec<u8> = Vec::with_capacity(32);
    match frame {
        Frame::Hello { version, token, name } => {
            body.push(TYPE_HELLO);
            body.push(*version);
            body.extend_from_slice(token);
            let bytes = truncate_utf8(name, 255);
            body.push(bytes.len() as u8);
            body.extend_from_slice(bytes);
        }
        Frame::Welcome { version, status } => body.extend_from_slice(&[TYPE_WELCOME, *version, *status]),
        Frame::PairCode { code } => {
            body.push(TYPE_PAIR_CODE);
            body.extend_from_slice(&code.to_be_bytes());
        }
        Frame::HostInfo { width, height } => {
            body.push(TYPE_HOST_INFO);
            body.extend_from_slice(&width.to_be_bytes());
            body.extend_from_slice(&height.to_be_bytes());
        }
        Frame::Mouse(m) => {
            body.push(TYPE_MOUSE);
            body.push(m.buttons);
            for v in [m.dx, m.dy, m.wheel, m.pan] {
                body.extend_from_slice(&v.to_be_bytes());
            }
        }
        Frame::Pen(p) => {
            body.push(TYPE_PEN);
            body.push(p.flags);
            for v in [p.x, p.y, p.pressure] {
                body.extend_from_slice(&v.to_be_bytes());
            }
            body.push(p.tilt_x as u8);
            body.push(p.tilt_y as u8);
            body.extend_from_slice(&p.twist.to_be_bytes());
        }
        Frame::Ping { nonce } => {
            body.push(TYPE_PING);
            body.extend_from_slice(&nonce.to_be_bytes());
        }
        Frame::Pong { nonce } => {
            body.push(TYPE_PONG);
            body.extend_from_slice(&nonce.to_be_bytes());
        }
        Frame::Bye => body.push(TYPE_BYE),
        Frame::Unknown { kind } => panic!("cannot encode unknown frame type 0x{kind:02x}"),
    }
    let mut out = Vec::with_capacity(body.len() + 2);
    out.extend_from_slice(&(body.len() as u16).to_be_bytes());
    out.extend_from_slice(&body);
    out
}

/// Longest prefix of `s` that is at most `max` bytes and ends on a character boundary.
fn truncate_utf8(s: &str, max: usize) -> &[u8] {
    if s.len() <= max {
        return s.as_bytes();
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s.as_bytes()[..end]
}

/// Decodes a frame body given its type byte and payload (without the length prefix).
pub fn decode_body(kind: u8, p: &[u8]) -> Result<Frame, Error> {
    let need = |n: usize| -> Result<(), Error> {
        if p.len() < n {
            Err(Error::Truncated { kind, needed: n, got: p.len() })
        } else {
            Ok(())
        }
    };
    let u16_at = |i: usize| u16::from_be_bytes([p[i], p[i + 1]]);
    let i16_at = |i: usize| i16::from_be_bytes([p[i], p[i + 1]]);
    let u32_at = |i: usize| u32::from_be_bytes([p[i], p[i + 1], p[i + 2], p[i + 3]]);
    Ok(match kind {
        TYPE_HELLO => {
            need(1 + TOKEN_LEN + 1)?;
            let mut token = [0u8; TOKEN_LEN];
            token.copy_from_slice(&p[1..1 + TOKEN_LEN]);
            let name_len = p[1 + TOKEN_LEN] as usize;
            let start = 2 + TOKEN_LEN;
            need(start + name_len)?;
            let name = std::str::from_utf8(&p[start..start + name_len]).map_err(|_| Error::BadName)?;
            Frame::Hello { version: p[0], token, name: name.to_owned() }
        }
        TYPE_WELCOME => {
            need(2)?;
            Frame::Welcome { version: p[0], status: p[1] }
        }
        TYPE_PAIR_CODE => {
            need(4)?;
            Frame::PairCode { code: u32_at(0) }
        }
        TYPE_HOST_INFO => {
            need(4)?;
            Frame::HostInfo { width: u16_at(0), height: u16_at(2) }
        }
        TYPE_MOUSE => {
            need(9)?;
            Frame::Mouse(MouseFrame { buttons: p[0], dx: i16_at(1), dy: i16_at(3), wheel: i16_at(5), pan: i16_at(7) })
        }
        TYPE_PEN => {
            need(11)?;
            Frame::Pen(PenFrame {
                flags: p[0],
                x: u16_at(1),
                y: u16_at(3),
                pressure: u16_at(5),
                tilt_x: p[7] as i8,
                tilt_y: p[8] as i8,
                twist: u16_at(9),
            })
        }
        TYPE_PING => {
            need(4)?;
            Frame::Ping { nonce: u32_at(0) }
        }
        TYPE_PONG => {
            need(4)?;
            Frame::Pong { nonce: u32_at(0) }
        }
        TYPE_BYE => Frame::Bye,
        other => Frame::Unknown { kind: other },
    })
}

/// Decodes exactly one complete frame including its length prefix.
pub fn decode_frame(bytes: &[u8]) -> Result<Frame, Error> {
    if bytes.len() < 3 {
        return Err(Error::BadLength(bytes.len()));
    }
    let len = u16::from_be_bytes([bytes[0], bytes[1]]) as usize;
    if len == 0 || len > MAX_LEN || bytes.len() != len + 2 {
        return Err(Error::BadLength(len));
    }
    decode_body(bytes[2], &bytes[3..])
}

/// Incremental decoder for a byte stream that may deliver partial frames.
#[derive(Debug, Default)]
pub struct FrameDecoder {
    buf: Vec<u8>,
}

impl FrameDecoder {
    /// Creates an empty decoder.
    pub fn new() -> Self {
        Self::default()
    }

    /// Appends received bytes.
    pub fn push(&mut self, bytes: &[u8]) {
        self.buf.extend_from_slice(bytes);
    }

    /// Returns the next complete frame, `Ok(None)` when more bytes are needed, or an error on bad framing.
    pub fn next_frame(&mut self) -> Result<Option<Frame>, Error> {
        if self.buf.len() < 2 {
            return Ok(None);
        }
        let len = u16::from_be_bytes([self.buf[0], self.buf[1]]) as usize;
        if len == 0 || len > MAX_LEN {
            return Err(Error::BadLength(len));
        }
        if self.buf.len() < len + 2 {
            return Ok(None);
        }
        let frame = decode_body(self.buf[2], &self.buf[3..len + 2]);
        self.buf.drain(..len + 2);
        frame.map(Some)
    }
}

/// Lowercase hex of `bytes`.
pub fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Parses hex into bytes; `None` on odd length or a non-hex digit.
pub fn from_hex(s: &str) -> Option<Vec<u8>> {
    if !s.len().is_multiple_of(2) {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(s.get(i..i + 2)?, 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Partial pushes yield frames only once complete, in order.
    #[test]
    fn decoder_handles_partial_input() {
        let mut bytes = encode(&Frame::Ping { nonce: 9 });
        bytes.extend(encode(&Frame::Bye));
        let mut d = FrameDecoder::new();
        d.push(&bytes[..3]);
        assert_eq!(d.next_frame(), Ok(None));
        d.push(&bytes[3..]);
        assert_eq!(d.next_frame(), Ok(Some(Frame::Ping { nonce: 9 })));
        assert_eq!(d.next_frame(), Ok(Some(Frame::Bye)));
        assert_eq!(d.next_frame(), Ok(None));
    }

    /// Oversized lengths are rejected.
    #[test]
    fn decoder_rejects_oversized_length() {
        let mut d = FrameDecoder::new();
        d.push(&[0x02, 0x01, 0x00]);
        assert_eq!(d.next_frame(), Err(Error::BadLength(513)));
    }

    /// Unknown types decode to `Unknown` so they can be skipped.
    #[test]
    fn unknown_type_is_skippable() {
        assert_eq!(decode_frame(&[0x00, 0x02, 0x7f, 0x00]), Ok(Frame::Unknown { kind: 0x7f }));
    }

    /// Long names are truncated on a character boundary.
    #[test]
    fn hello_name_truncates_on_char_boundary() {
        let name = "é".repeat(200);
        let bytes = encode(&Frame::Hello { version: 1, token: [0; TOKEN_LEN], name });
        match decode_frame(&bytes).unwrap() {
            Frame::Hello { name, .. } => assert_eq!(name.len(), 254),
            other => panic!("unexpected {other:?}"),
        }
    }

    /// Hex helpers round-trip and reject bad input.
    #[test]
    fn hex_round_trip() {
        assert_eq!(from_hex("00ff7a"), Some(vec![0, 255, 0x7a]));
        assert_eq!(to_hex(&[0, 255, 0x7a]), "00ff7a");
        assert_eq!(from_hex("0"), None);
        assert_eq!(from_hex("zz"), None);
    }
}
