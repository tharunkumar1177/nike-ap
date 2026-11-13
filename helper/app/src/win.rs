//! win.rs: Win32 glue: DPI awareness, monitor enumeration, SendInput mouse, synthetic pen, and message boxes.

use crate::link::Injector;
use crate::mouse::{MouseAction, MouseState};
use crate::pen::{PenStep, PenTracker, Rect};
use padlink_protocol::{buttons, MouseFrame, PenFrame};
use std::mem::{size_of, zeroed};
use std::ptr::{null, null_mut};
use windows_sys::Win32::Foundation::{BOOL, LPARAM, RECT};
use windows_sys::Win32::Graphics::Gdi::{EnumDisplayMonitors, GetMonitorInfoW, HDC, HMONITOR, MONITORINFO, MONITORINFOEXW};
use windows_sys::Win32::UI::Controls::{
    CreateSyntheticPointerDevice, DestroySyntheticPointerDevice, InjectSyntheticPointerInput, HSYNTHETICPOINTERDEVICE,
    POINTER_TYPE_INFO,
};
use windows_sys::Win32::UI::HiDpi::{SetProcessDpiAwarenessContext, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2};
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{SendInput, INPUT, INPUT_0, MOUSEINPUT};
use windows_sys::Win32::UI::Input::Pointer::POINTER_PEN_INFO;
use windows_sys::Win32::UI::WindowsAndMessaging::MessageBoxW;

const INPUT_MOUSE: u32 = 0;
const MOUSEEVENTF_MOVE: u32 = 0x0001;
const MOUSEEVENTF_LEFTDOWN: u32 = 0x0002;
const MOUSEEVENTF_LEFTUP: u32 = 0x0004;
const MOUSEEVENTF_RIGHTDOWN: u32 = 0x0008;
const MOUSEEVENTF_RIGHTUP: u32 = 0x0010;
const MOUSEEVENTF_MIDDLEDOWN: u32 = 0x0020;
const MOUSEEVENTF_MIDDLEUP: u32 = 0x0040;
const MOUSEEVENTF_WHEEL: u32 = 0x0800;
const MOUSEEVENTF_HWHEEL: u32 = 0x1000;

const PT_PEN: i32 = 3;
const POINTER_FEEDBACK_DEFAULT: i32 = 1;

const MB_ICONINFORMATION: u32 = 0x0000_0040;
const MB_ICONERROR: u32 = 0x0000_0010;
const MB_SETFOREGROUND: u32 = 0x0001_0000;
const MB_TOPMOST: u32 = 0x0004_0000;

/// Makes monitor rectangles and pen coordinates physical pixels on every monitor.
pub fn enable_dpi_awareness() {
    // SAFETY: plain Win32 call with a predefined context constant.
    unsafe {
        SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
}

/// One attached monitor.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Monitor {
    /// GDI device name such as `\\.\DISPLAY1`.
    pub name: String,
    /// Bounds in virtual-screen pixels.
    pub rect: Rect,
    /// True for the primary monitor.
    pub primary: bool,
}

/// Lists attached monitors, primary first.
pub fn monitors() -> Vec<Monitor> {
    /// EnumDisplayMonitors callback appending to the `Vec<Monitor>` passed in `data`.
    unsafe extern "system" fn collect(hmon: HMONITOR, _hdc: HDC, _clip: *mut RECT, data: LPARAM) -> BOOL {
        let list = &mut *(data as *mut Vec<Monitor>);
        let mut info: MONITORINFOEXW = zeroed();
        info.monitorInfo.cbSize = size_of::<MONITORINFOEXW>() as u32;
        if GetMonitorInfoW(hmon, &mut info as *mut MONITORINFOEXW as *mut MONITORINFO) != 0 {
            let r = info.monitorInfo.rcMonitor;
            let len = info.szDevice.iter().position(|&c| c == 0).unwrap_or(info.szDevice.len());
            list.push(Monitor {
                name: String::from_utf16_lossy(&info.szDevice[..len]),
                rect: Rect { left: r.left, top: r.top, right: r.right, bottom: r.bottom },
                primary: info.monitorInfo.dwFlags & 1 != 0,
            });
        }
        1
    }
    let mut list: Vec<Monitor> = Vec::new();
    // SAFETY: the callback only runs during this call and `list` outlives it.
    unsafe {
        EnumDisplayMonitors(null_mut(), null(), Some(collect), &mut list as *mut Vec<Monitor> as LPARAM);
    }
    list.sort_by_key(|m| (!m.primary, m.rect.left, m.rect.top));
    list
}

/// The monitor named `name`, else the primary, else the first.
pub fn pick_monitor<'a>(list: &'a [Monitor], name: Option<&str>) -> Option<&'a Monitor> {
    name.and_then(|n| list.iter().find(|m| m.name == n))
        .or_else(|| list.iter().find(|m| m.primary))
        .or_else(|| list.first())
}

/// NUL-terminated UTF-16.
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

/// Shows a topmost message box on its own thread so the caller never blocks.
fn message_box(text: String, flags: u32) {
    std::thread::spawn(move || {
        let text = wide(&text);
        let caption = wide("PadLink Helper");
        // SAFETY: both strings are NUL-terminated and live for the call.
        unsafe {
            MessageBoxW(null_mut(), text.as_ptr(), caption.as_ptr(), flags | MB_SETFOREGROUND | MB_TOPMOST);
        }
    });
}

/// Shows the tethering approval code the phone also displays.
pub fn show_pair_code(code: u32) {
    let digits = format!("{code:06}");
    message_box(
        format!(
            "Approve this computer on your phone.\n\nCheck that the phone shows the same code:\n\n{} {}",
            &digits[..3],
            &digits[3..]
        ),
        MB_ICONINFORMATION,
    );
}

/// Shows a fatal error.
pub fn error_box(text: &str) {
    let text = wide(text);
    let caption = wide("PadLink Helper");
    // SAFETY: both strings are NUL-terminated and live for the call.
    unsafe {
        MessageBoxW(null_mut(), text.as_ptr(), caption.as_ptr(), MB_ICONERROR | MB_SETFOREGROUND);
    }
}

/// Builds one mouse INPUT.
fn mouse_input(flags: u32, dx: i32, dy: i32, data: i32) -> INPUT {
    // SAFETY: INPUT is plain data; all-zero is a valid value.
    let mut input: INPUT = unsafe { zeroed() };
    input.r#type = INPUT_MOUSE as _;
    input.Anonymous = INPUT_0 {
        mi: MOUSEINPUT { dx, dy, mouseData: data as _, dwFlags: flags as _, time: 0, dwExtraInfo: 0 },
    };
    input
}

/// SendInput flag pair (down, up) for a button bit.
fn button_flags(bit: u8) -> (u32, u32) {
    match bit {
        buttons::RIGHT => (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        buttons::MIDDLE => (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
        _ => (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    }
}

/// Injects mouse actions in order with one SendInput call.
fn send_mouse(actions: &[MouseAction]) {
    if actions.is_empty() {
        return;
    }
    let inputs: Vec<INPUT> = actions
        .iter()
        .map(|a| match *a {
            MouseAction::Down(bit) => mouse_input(button_flags(bit).0, 0, 0, 0),
            MouseAction::Up(bit) => mouse_input(button_flags(bit).1, 0, 0, 0),
            MouseAction::Move(dx, dy) => mouse_input(MOUSEEVENTF_MOVE, dx, dy, 0),
            MouseAction::Wheel(w) => mouse_input(MOUSEEVENTF_WHEEL, 0, 0, w),
            MouseAction::HWheel(w) => mouse_input(MOUSEEVENTF_HWHEEL, 0, 0, w),
        })
        .collect();
    // SAFETY: `inputs` is a valid array of INPUT structures of the stated size.
    unsafe {
        SendInput(inputs.len() as u32, inputs.as_ptr(), size_of::<INPUT>() as i32);
    }
}

/// Windows Ink pen created with CreateSyntheticPointerDevice (Windows 10 1809+).
struct SyntheticPen {
    device: HSYNTHETICPOINTERDEVICE,
}

impl SyntheticPen {
    /// Creates the device, or `None` on Windows versions without synthetic pointers.
    fn new() -> Option<Self> {
        // SAFETY: plain Win32 call; a null handle signals failure.
        let device = unsafe { CreateSyntheticPointerDevice(PT_PEN as _, 1, POINTER_FEEDBACK_DEFAULT as _) };
        if device.is_null() {
            None
        } else {
            Some(Self { device })
        }
    }

    /// Injects one pen step.
    fn inject(&self, s: &PenStep) {
        // SAFETY: POINTER_PEN_INFO and POINTER_TYPE_INFO are plain data; all-zero is valid.
        let mut pen: POINTER_PEN_INFO = unsafe { zeroed() };
        pen.pointerInfo.pointerType = PT_PEN as _;
        pen.pointerInfo.pointerId = 0;
        pen.pointerInfo.pointerFlags = s.pointer_flags as _;
        pen.pointerInfo.ptPixelLocation.x = s.x;
        pen.pointerInfo.ptPixelLocation.y = s.y;
        pen.pointerInfo.ButtonChangeType = s.button_change as _;
        pen.penFlags = s.pen_flags as _;
        pen.penMask = s.pen_mask as _;
        pen.pressure = s.pressure;
        pen.rotation = s.rotation;
        pen.tiltX = s.tilt_x;
        pen.tiltY = s.tilt_y;
        // SAFETY: see above.
        let mut info: POINTER_TYPE_INFO = unsafe { zeroed() };
        info.r#type = PT_PEN as _;
        info.Anonymous.penInfo = pen;
        // SAFETY: `info` is a valid single-element array for the live device.
        unsafe {
            InjectSyntheticPointerInput(self.device, &info, 1);
        }
    }
}

impl Drop for SyntheticPen {
    /// Destroys the synthetic device.
    fn drop(&mut self) {
        // SAFETY: the handle came from CreateSyntheticPointerDevice and is destroyed once.
        unsafe { DestroySyntheticPointerDevice(self.device) }
    }
}

/// Injector backed by SendInput and a synthetic Windows Ink pen; releases everything on drop.
pub struct WinInjector {
    mouse: MouseState,
    pen: PenTracker,
    device: Option<SyntheticPen>,
    device_unavailable: bool,
    target: Rect,
}

impl WinInjector {
    /// Creates an injector targeting `target` for pen input.
    pub fn new(target: Rect) -> Self {
        Self { mouse: MouseState::new(), pen: PenTracker::new(), device: None, device_unavailable: false, target }
    }

    /// Injects pen steps, creating the synthetic pen on first use.
    fn inject_pen(&mut self, steps: &[PenStep]) {
        if steps.is_empty() || self.device_unavailable {
            return;
        }
        if self.device.is_none() {
            self.device = SyntheticPen::new();
            if self.device.is_none() {
                self.device_unavailable = true;
                return;
            }
        }
        if let Some(device) = &self.device {
            for step in steps {
                device.inject(step);
            }
        }
    }
}

impl Injector for WinInjector {
    /// Injects button changes, motion, and wheels.
    fn mouse(&mut self, frame: &MouseFrame) {
        let actions = self.mouse.apply(frame);
        send_mouse(&actions);
    }

    /// Injects hover, contact, and lift through the synthetic pen.
    fn pen(&mut self, frame: &PenFrame) {
        let steps = self.pen.apply(frame, &self.target);
        self.inject_pen(&steps);
    }

    /// Releases held buttons, lifts the tip, and takes the pen out of range.
    fn release_all(&mut self) {
        let actions = self.mouse.release_all();
        send_mouse(&actions);
        let steps = self.pen.release();
        self.inject_pen(&steps);
    }

    /// Changes the pen target monitor.
    fn set_target(&mut self, rect: Rect) {
        self.target = rect;
    }
}

impl Drop for WinInjector {
    /// Never leaves a button held or a stroke open when the helper exits.
    fn drop(&mut self) {
        self.release_all();
    }
}
