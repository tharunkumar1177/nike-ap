<!-- blueprints/mouse-trackpad.md: finger trackpad, gestures, pointer math, Mouse screen, and settings sheet. -->

# Mouse trackpad

## Outcome or responsibility

The phone screen acts as a laptop-style trackpad: one finger moves the pointer, taps click, two fingers scroll, and on-screen buttons press and hold. The Mouse screen layout follows the original app's screenshots (see the [Mouse screen](../assets/wireframes/main-connected.svg) and the settings sheet's [Mouse](../assets/wireframes/settings-mouse.svg), [Pen](../assets/wireframes/settings-pen.svg), [Connection](../assets/wireframes/settings-connection.svg), and [Display](../assets/wireframes/settings-display.svg) tab wireframes).

## Current verified status

**Status:** Partial

Gestures, pointer and scroll math, click timing, the Mouse screen, scroll strip, buttons, settings sheet, and haptics are implemented. The unit tests listed below pass in CI. On-device acceptance with a Windows host has not been recorded yet.

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
- **Click timing**: press, hold 180 ms, then restore the held button mask. A new click during the hold releases first.
- **Pointer math** (`PointerAccel`): deltas are converted to dp, multiplied by `2^((speed-50)/25)` and a velocity factor (1.0 at or below 0.1 dp/ms rising linearly to 2.5 at 1.5 dp/ms), and sub-count remainders carry between events. Deltas larger than ±127 split across reports.
- **Gesture constants**: tap timeout 200 ms, double-tap window 300 ms, touch slop 8 dp, two-finger tap timeout 250 ms.
- **Scroll math**: 40 dp of finger travel equals one notch at speed 50, scaled by `2^((speed-50)/25)`; fractional notches carry.
- **Haptics**: a short tick on every click and button press when `haptic_feedback` is on.

## Related blueprints

### Required

None

### Impact checks

- [stylus-pen](stylus-pen.md): check when touch routing, palm rejection, or the trackpad view changes, because the pen shares the same surface.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/input/GestureEngine.kt`, `PointerAccel.kt`, `MouseController.kt`, `Scheduler.kt`
- `android/app/src/main/kotlin/dev/padlink/ui/MouseScreen.kt` (header, transport picker, layout), `TrackpadSurface.kt` (trackpad, scroll strip, buttons), `TrackpadView.kt`, `PadSession.kt`, `SettingsSheet.kt`, `ConnectionCards.kt`, `Haptics.kt`, `MainViewModel.kt`
- Tests: `android/app/src/test/kotlin/dev/padlink/input/GestureEngineTest.kt`, `PointerAccelTest.kt`, `MouseControllerTest.kt`

## Acceptance or verification criteria

- [x] Unit tests: tap, double-tap drag, two- and three-finger taps, slop rejection, two-finger scroll direction and invert.
- [x] Unit tests: acceleration curve and remainder carry-over.
- [ ] On device: pointer, clicks, scroll, and drag work on a Windows host over each transport.

## Remaining gaps and unknowns

- The gesture constants and acceleration curve are design values that have not been tuned on a device yet.
