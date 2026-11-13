//! pen.rs: maps Pen frames onto a monitor and derives Windows synthetic pen pointer steps.

use padlink_protocol::{pen_flags, PenFrame};

/// `POINTER_FLAG_INRANGE`.
pub const POINTER_FLAG_INRANGE: u32 = 0x0000_0002;
/// `POINTER_FLAG_INCONTACT`.
pub const POINTER_FLAG_INCONTACT: u32 = 0x0000_0004;
/// `POINTER_FLAG_FIRSTBUTTON`.
pub const POINTER_FLAG_FIRSTBUTTON: u32 = 0x0000_0010;
/// `POINTER_FLAG_SECONDBUTTON`.
pub const POINTER_FLAG_SECONDBUTTON: u32 = 0x0000_0020;
/// `POINTER_FLAG_DOWN`.
pub const POINTER_FLAG_DOWN: u32 = 0x0001_0000;
/// `POINTER_FLAG_UPDATE`.
pub const POINTER_FLAG_UPDATE: u32 = 0x0002_0000;
/// `POINTER_FLAG_UP`.
pub const POINTER_FLAG_UP: u32 = 0x0004_0000;

/// `PEN_FLAG_BARREL`.
pub const PEN_FLAG_BARREL: u32 = 0x1;
/// `PEN_FLAG_INVERTED`.
pub const PEN_FLAG_INVERTED: u32 = 0x2;
/// `PEN_FLAG_ERASER`.
pub const PEN_FLAG_ERASER: u32 = 0x4;

/// `PEN_MASK_PRESSURE | PEN_MASK_ROTATION | PEN_MASK_TILT_X | PEN_MASK_TILT_Y`.
pub const PEN_MASK_ALL: u32 = 0x1 | 0x2 | 0x4 | 0x8;

/// `POINTER_CHANGE_NONE`.
pub const CHANGE_NONE: i32 = 0;
/// `POINTER_CHANGE_FIRSTBUTTON_DOWN`.
pub const CHANGE_FIRST_DOWN: i32 = 1;
/// `POINTER_CHANGE_FIRSTBUTTON_UP`.
pub const CHANGE_FIRST_UP: i32 = 2;
/// `POINTER_CHANGE_SECONDBUTTON_DOWN`.
pub const CHANGE_SECOND_DOWN: i32 = 3;
/// `POINTER_CHANGE_SECONDBUTTON_UP`.
pub const CHANGE_SECOND_UP: i32 = 4;

/// Windows pen pressure range upper bound.
pub const WIN_MAX_PRESSURE: u32 = 1024;
/// Wire pressure upper bound.
pub const WIRE_MAX_PRESSURE: u32 = 4095;
/// Wire coordinate upper bound.
pub const WIRE_MAX_COORD: i64 = 32767;

/// Monitor rectangle in virtual-screen physical pixels; `right`/`bottom` are exclusive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rect {
    /// Left edge.
    pub left: i32,
    /// Top edge.
    pub top: i32,
    /// Exclusive right edge.
    pub right: i32,
    /// Exclusive bottom edge.
    pub bottom: i32,
}

impl Rect {
    /// Width in pixels.
    pub fn width(&self) -> i32 {
        self.right - self.left
    }

    /// Height in pixels.
    pub fn height(&self) -> i32 {
        self.bottom - self.top
    }
}

/// Maps wire coordinates (0..=32767) onto pixel positions inside `rect`.
pub fn map_point(x: u16, y: u16, rect: &Rect) -> (i32, i32) {
    let scale = |v: u16, origin: i32, span: i32| -> i32 {
        let span = i64::from(span.max(1) - 1);
        origin + ((i64::from(v).min(WIRE_MAX_COORD) * span + WIRE_MAX_COORD / 2) / WIRE_MAX_COORD) as i32
    };
    (scale(x, rect.left, rect.width()), scale(y, rect.top, rect.height()))
}

/// Scales wire pressure (0..=4095) to Windows pen pressure (0..=1024).
pub fn scale_pressure(p: u16) -> u32 {
    (u32::from(p).min(WIRE_MAX_PRESSURE) * WIN_MAX_PRESSURE + WIRE_MAX_PRESSURE / 2) / WIRE_MAX_PRESSURE
}

/// One `POINTER_PEN_INFO` injection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PenStep {
    /// `POINTER_INFO.pointerFlags`.
    pub pointer_flags: u32,
    /// `POINTER_INFO.ButtonChangeType`.
    pub button_change: i32,
    /// `POINTER_PEN_INFO.penFlags`.
    pub pen_flags: u32,
    /// `POINTER_PEN_INFO.penMask`.
    pub pen_mask: u32,
    /// 0..=1024.
    pub pressure: u32,
    /// Degrees 0..=359.
    pub rotation: u32,
    /// Degrees -90..=90.
    pub tilt_x: i32,
    /// Degrees -90..=90.
    pub tilt_y: i32,
    /// Pixel X.
    pub x: i32,
    /// Pixel Y.
    pub y: i32,
}

/// Tracks pen proximity, contact, and barrel so injected sequences are always well formed.
#[derive(Debug, Default)]
pub struct PenTracker {
    in_range: bool,
    contact: bool,
    barrel: bool,
    last: (i32, i32),
}

impl PenTracker {
    /// Creates a tracker with the pen out of range.
    pub fn new() -> Self {
        Self::default()
    }

    /// True while the pen is in range.
    #[cfg(test)]
    pub fn in_range(&self) -> bool {
        self.in_range
    }

    /// Steps for one frame mapped onto `rect`.
    pub fn apply(&mut self, f: &PenFrame, rect: &Rect) -> Vec<PenStep> {
        if f.flags & pen_flags::IN_RANGE == 0 {
            return self.release();
        }
        let (x, y) = map_point(f.x, f.y, rect);
        self.last = (x, y);
        let contact = f.flags & pen_flags::TIP != 0;
        let barrel = f.flags & pen_flags::BARREL != 0;
        let mut pen = 0;
        if barrel {
            pen |= PEN_FLAG_BARREL;
        }
        if f.flags & pen_flags::INVERT != 0 {
            pen |= PEN_FLAG_INVERTED;
        }
        if f.flags & pen_flags::ERASER != 0 {
            pen |= PEN_FLAG_ERASER;
        }
        let second = if barrel { POINTER_FLAG_SECONDBUTTON } else { 0 };
        let (pointer_flags, button_change, pressure) = match (self.contact, contact) {
            (false, true) => (
                POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON | second | POINTER_FLAG_DOWN,
                CHANGE_FIRST_DOWN,
                scale_pressure(f.pressure),
            ),
            (true, false) => (POINTER_FLAG_INRANGE | second | POINTER_FLAG_UP, CHANGE_FIRST_UP, 0),
            (true, true) => (
                POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON | second | POINTER_FLAG_UPDATE,
                self.barrel_change(barrel),
                scale_pressure(f.pressure),
            ),
            (false, false) => (POINTER_FLAG_INRANGE | second | POINTER_FLAG_UPDATE, self.barrel_change(barrel), 0),
        };
        self.in_range = true;
        self.contact = contact;
        self.barrel = barrel;
        vec![PenStep {
            pointer_flags,
            button_change,
            pen_flags: pen,
            pen_mask: PEN_MASK_ALL,
            pressure,
            rotation: u32::from(f.twist % 360),
            tilt_x: i32::from(f.tilt_x).clamp(-90, 90),
            tilt_y: i32::from(f.tilt_y).clamp(-90, 90),
            x,
            y,
        }]
    }

    /// Lifts the tip if down and takes the pen out of range (link lost, Bye, or out-of-range frame).
    pub fn release(&mut self) -> Vec<PenStep> {
        let mut out = Vec::with_capacity(2);
        let (x, y) = self.last;
        let base = PenStep {
            pointer_flags: 0,
            button_change: CHANGE_NONE,
            pen_flags: 0,
            pen_mask: PEN_MASK_ALL,
            pressure: 0,
            rotation: 0,
            tilt_x: 0,
            tilt_y: 0,
            x,
            y,
        };
        if self.contact {
            out.push(PenStep { pointer_flags: POINTER_FLAG_INRANGE | POINTER_FLAG_UP, button_change: CHANGE_FIRST_UP, ..base });
        }
        if self.in_range {
            out.push(PenStep { pointer_flags: POINTER_FLAG_UPDATE, ..base });
        }
        self.in_range = false;
        self.contact = false;
        self.barrel = false;
        out
    }

    /// Button change type for a barrel transition without a tip transition.
    fn barrel_change(&self, barrel: bool) -> i32 {
        match (self.barrel, barrel) {
            (false, true) => CHANGE_SECOND_DOWN,
            (true, false) => CHANGE_SECOND_UP,
            _ => CHANGE_NONE,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SCREEN: Rect = Rect { left: 0, top: 0, right: 2560, bottom: 1440 };

    /// Builds a pen frame.
    fn pen(flags: u8, x: u16, y: u16, pressure: u16) -> PenFrame {
        PenFrame { flags, x, y, pressure, tilt_x: 0, tilt_y: 0, twist: 0 }
    }

    /// Corners and center map inside the monitor, including offset monitors.
    #[test]
    fn maps_points() {
        assert_eq!(map_point(0, 0, &SCREEN), (0, 0));
        assert_eq!(map_point(32767, 32767, &SCREEN), (2559, 1439));
        assert_eq!(map_point(16384, 16384, &SCREEN), (1280, 720));
        let second = Rect { left: -1920, top: 0, right: 0, bottom: 1080 };
        assert_eq!(map_point(0, 32767, &second), (-1920, 1079));
    }

    /// Pressure scales 4095 to 1024.
    #[test]
    fn scales_pressure() {
        assert_eq!(scale_pressure(0), 0);
        assert_eq!(scale_pressure(4095), 1024);
        assert_eq!(scale_pressure(2048), 512);
        assert_eq!(scale_pressure(u16::MAX), 1024);
    }

    /// Hover, down, move, up, and leave produce the documented flag sequence.
    #[test]
    fn full_stroke_sequence() {
        let mut t = PenTracker::new();
        let r = pen_flags::IN_RANGE;
        let tip = pen_flags::TIP;
        let hover = t.apply(&pen(r, 100, 100, 0), &SCREEN)[0];
        assert_eq!(hover.pointer_flags, POINTER_FLAG_INRANGE | POINTER_FLAG_UPDATE);
        let down = t.apply(&pen(r | tip, 100, 100, 4095), &SCREEN)[0];
        assert_eq!(down.pointer_flags & POINTER_FLAG_DOWN, POINTER_FLAG_DOWN);
        assert_eq!(down.button_change, CHANGE_FIRST_DOWN);
        assert_eq!(down.pressure, 1024);
        let moved = t.apply(&pen(r | tip, 200, 100, 2048), &SCREEN)[0];
        assert_eq!(moved.pointer_flags, POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON | POINTER_FLAG_UPDATE);
        let up = t.apply(&pen(r, 200, 100, 0), &SCREEN)[0];
        assert_eq!(up.pointer_flags, POINTER_FLAG_INRANGE | POINTER_FLAG_UP);
        assert_eq!(up.button_change, CHANGE_FIRST_UP);
        let leave = t.apply(&pen(0, 0, 0, 0), &SCREEN);
        assert_eq!(leave.len(), 1);
        assert_eq!(leave[0].pointer_flags, POINTER_FLAG_UPDATE);
        assert!(!t.in_range());
    }

    /// Releasing during contact lifts the tip, then leaves range, at the last position.
    #[test]
    fn release_during_contact_lifts_then_leaves() {
        let mut t = PenTracker::new();
        t.apply(&pen(pen_flags::IN_RANGE | pen_flags::TIP, 32767, 0, 100), &SCREEN);
        let steps = t.release();
        assert_eq!(steps.len(), 2);
        assert_eq!(steps[0].pointer_flags, POINTER_FLAG_INRANGE | POINTER_FLAG_UP);
        assert_eq!(steps[1].pointer_flags, POINTER_FLAG_UPDATE);
        assert_eq!((steps[0].x, steps[0].y), (2559, 0));
        assert!(t.release().is_empty());
    }

    /// Eraser and barrel map to Windows pen flags; barrel transitions report second-button changes.
    #[test]
    fn eraser_and_barrel_flags() {
        let mut t = PenTracker::new();
        let r = pen_flags::IN_RANGE;
        let barrel = t.apply(&pen(r | pen_flags::BARREL, 0, 0, 0), &SCREEN)[0];
        assert_eq!(barrel.pen_flags, PEN_FLAG_BARREL);
        assert_eq!(barrel.button_change, CHANGE_SECOND_DOWN);
        assert_eq!(barrel.pointer_flags & POINTER_FLAG_SECONDBUTTON, POINTER_FLAG_SECONDBUTTON);
        let released = t.apply(&pen(r, 0, 0, 0), &SCREEN)[0];
        assert_eq!(released.button_change, CHANGE_SECOND_UP);
        let erase = t.apply(&pen(r | pen_flags::TIP | pen_flags::ERASER | pen_flags::INVERT, 0, 0, 10), &SCREEN)[0];
        assert_eq!(erase.pen_flags, PEN_FLAG_ERASER | PEN_FLAG_INVERTED);
    }
}
