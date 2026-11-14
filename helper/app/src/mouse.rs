//! mouse.rs: turns Mouse frames into ordered injection actions while tracking held buttons.

use padlink_protocol::{buttons, MouseFrame};

/// One injectable mouse action.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MouseAction {
    /// Press a button ([`buttons`] bit).
    Down(u8),
    /// Release a button ([`buttons`] bit).
    Up(u8),
    /// Relative motion in counts.
    Move(i32, i32),
    /// Vertical wheel in 1/120 notches, positive up.
    Wheel(i32),
    /// Horizontal wheel in 1/120 notches, positive right.
    HWheel(i32),
}

const ALL_BUTTONS: [u8; 3] = [buttons::LEFT, buttons::RIGHT, buttons::MIDDLE];

/// Held-button tracker so every press has a matching release.
#[derive(Debug, Default)]
pub struct MouseState {
    held: u8,
}

impl MouseState {
    /// Creates a tracker with nothing held.
    pub fn new() -> Self {
        Self::default()
    }

    /// Buttons currently held.
    #[cfg(test)]
    pub fn held(&self) -> u8 {
        self.held
    }

    /// Actions for one frame: presses first (so a drag starts before it moves), then motion,
    /// then releases, then wheels.
    pub fn apply(&mut self, f: &MouseFrame) -> Vec<MouseAction> {
        let mut out = Vec::with_capacity(4);
        let wanted = f.buttons & (buttons::LEFT | buttons::RIGHT | buttons::MIDDLE);
        for bit in ALL_BUTTONS {
            if wanted & bit != 0 && self.held & bit == 0 {
                out.push(MouseAction::Down(bit));
            }
        }
        if f.dx != 0 || f.dy != 0 {
            out.push(MouseAction::Move(f.dx.into(), f.dy.into()));
        }
        for bit in ALL_BUTTONS {
            if wanted & bit == 0 && self.held & bit != 0 {
                out.push(MouseAction::Up(bit));
            }
        }
        if f.wheel != 0 {
            out.push(MouseAction::Wheel(f.wheel.into()));
        }
        if f.pan != 0 {
            out.push(MouseAction::HWheel(f.pan.into()));
        }
        self.held = wanted;
        out
    }

    /// Releases every held button (link lost or helper exiting).
    pub fn release_all(&mut self) -> Vec<MouseAction> {
        let out = ALL_BUTTONS
            .iter()
            .filter(|&&bit| self.held & bit != 0)
            .map(|&bit| MouseAction::Up(bit))
            .collect();
        self.held = 0;
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a frame.
    fn frame(buttons: u8, dx: i16, dy: i16, wheel: i16, pan: i16) -> MouseFrame {
        MouseFrame { buttons, dx, dy, wheel, pan }
    }

    /// A press with motion presses before moving; a release with motion moves before releasing.
    #[test]
    fn orders_presses_moves_and_releases() {
        let mut s = MouseState::new();
        assert_eq!(s.apply(&frame(1, 5, 0, 0, 0)), vec![MouseAction::Down(1), MouseAction::Move(5, 0)]);
        assert_eq!(s.apply(&frame(1, 3, 0, 0, 0)), vec![MouseAction::Move(3, 0)]);
        assert_eq!(s.apply(&frame(0, 2, 0, 0, 0)), vec![MouseAction::Move(2, 0), MouseAction::Up(1)]);
        assert_eq!(s.held(), 0);
    }

    /// Wheel and pan pass through in notch units.
    #[test]
    fn passes_wheels() {
        let mut s = MouseState::new();
        assert_eq!(
            s.apply(&frame(0, 0, 0, 120, -240)),
            vec![MouseAction::Wheel(120), MouseAction::HWheel(-240)]
        );
    }

    /// Releasing all lifts exactly the held buttons once.
    #[test]
    fn release_all_lifts_held_buttons() {
        let mut s = MouseState::new();
        s.apply(&frame(buttons::LEFT | buttons::MIDDLE, 0, 0, 0, 0));
        assert_eq!(s.release_all(), vec![MouseAction::Up(buttons::LEFT), MouseAction::Up(buttons::MIDDLE)]);
        assert!(s.release_all().is_empty());
    }

    /// Unknown button bits are ignored.
    #[test]
    fn ignores_unknown_bits() {
        let mut s = MouseState::new();
        assert!(s.apply(&frame(0x18, 0, 0, 0, 0)).is_empty());
        assert_eq!(s.held(), 0);
    }
}
