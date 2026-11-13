<!-- blueprints/mouse-trackpad.md: finger trackpad, gestures, pointer math, Mouse screen, and settings sheet. -->

# Mouse trackpad

## Outcome or responsibility

The phone screen acts as a laptop-style trackpad: one finger moves the pointer, taps click, two fingers scroll, and on-screen buttons press and hold. The Mouse screen layout follows the original app's screenshots (see [the wireframe](../assets/wireframes/mouse-screen.svg)).

## Current verified status

**Status:** Planned

No implementation exists yet.

## Architecture dependencies

- [Input event model](../architecture.md#input-event-model): the gesture layer produces `MouseReport` values only; pixel-to-count and pixel-to-notch conversion happens in `MouseController` before reports leave the input layer.
- [Settings contract](../architecture.md#settings-contract): pointer speed, scroll speed, invert scroll, tap clicks, button visibility and position, scroll bar side, and haptics are read from the listed keys with the listed defaults.
- [Connection state model](../architecture.md#connection-state-model): the header subtitle and the info card render `LinkState` without adding new states.

## Local rules and implications

- **Gestures** (`GestureEngine`, pure Kotlin, time injected):
  - One finger beyond touch slop moves the pointer.
  - Tap (lift within the tap timeout, no slop exceeded) clicks left when `touch_click_enabled` is on.
  - Tap then touch again within the double-tap window and move: left button held for the drag, released on lift.
  - Two-finger tap clicks right; three-finger tap clicks middle.
  - Two-finger drag scrolls: vertical centroid motion to `wheel`, horizontal to `pan`. Finger moving down scrolls content down (negative `wheel`), like dragging a scrollbar; `mouse_invert_scroll` flips both axes.
- **Scroll strip**: dragging scrolls with the same convention; tapping the up or down arrow sends exactly one notch.
- **Buttons**: pressing an on-screen button holds that bit until release, so another finger can drag.
- **Click timing**: press, hold 180 ms, then release (from the original app). A new click during the hold releases first.
- **Pointer math** (`PointerAccel`): deltas are converted to dp, multiplied by `2^((speed-50)/25)` and a velocity factor (1.0 at or below 0.1 dp/ms rising linearly to 2.5 at 1.5 dp/ms), and sub-count remainders carry between events.
- **Scroll math**: 40 dp of finger travel equals one notch at speed 50, scaled by `2^((speed-50)/25)`; fractional notches carry.
- **Haptics**: a short tick on every click and button press when `haptic_feedback` is on.

## Related blueprints

### Required

None

### Impact checks

- [stylus-pen](stylus-pen.md): check when touch routing, palm rejection, or the trackpad view changes, because the pen shares the same surface.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/input/GestureEngine.kt` (planned)
- `android/app/src/main/kotlin/dev/padlink/input/PointerAccel.kt` (planned)
- `android/app/src/main/kotlin/dev/padlink/input/MouseController.kt` (planned)
- `android/app/src/main/kotlin/dev/padlink/ui/` (planned)

## Acceptance or verification criteria

- [ ] Unit tests: tap, double-tap drag, two- and three-finger taps, slop rejection, two-finger scroll direction and invert.
- [ ] Unit tests: acceleration curve and remainder carry-over.
- [ ] On device: pointer, clicks, scroll, and drag work on a Windows host over each transport.

## Values taken from the original app

Recovered from the decompiled reference (`references/`, gitignored; search it with `rg --no-ignore`):

- Slider settings (`mouse_pointer_speed`, `mouse_scroll_speed`) are 0..100 sliders with default 50 (`km3.java` toString, `of3.java` lines 152-153).
- `touch_click_enabled` defaults to true, `mouse_invert_scroll` to false, `haptic_feedback` to true, `show_mouse_buttons` to {bottom}, `visible_mouse_buttons` to {left, right}, `show_scroll_bar` to {right} (`of3.java`, `vl4.java`).
- Click: press, wait 180 ms, restore the held mask (`h40.java`). Large deltas split at ±127 (`i40.java`).

## Remaining gaps and unknowns

- The original's tap timeout, slop, and acceleration curve live in obfuscated Compose pointer-input lambdas and could not be recovered (`el5.java` and `ba.java` are androidx framework classes, not app code). The values above under Local rules are PadLink design constants: tap timeout 200 ms, double-tap window 300 ms, slop 8 dp, two-finger tap timeout 250 ms.
