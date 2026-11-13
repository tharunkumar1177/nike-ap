<!-- blueprints/stylus-pen.md: S Pen capture, tablet mapping, pen reports, ink trail, and Galaxy S Ultra tuning. -->

# Stylus pen

## Outcome or responsibility

The S Pen works as a pressure-sensitive pen tablet for the computer while fingers keep working as the trackpad. Hover moves the cursor, the tip draws with pressure, and the side button acts as the barrel button or eraser. A full-screen landscape pen canvas gives the largest drawing area.

## Current verified status

**Status:** Partial

Tool-type routing, palm rejection, tablet and pointer modes, pressure curves, tilt conversion, the ink trail, the landscape pen canvas, and the Galaxy S Ultra tuning are implemented. The pure logic is unit-tested in CI. Behavior on a Galaxy S Ultra has not been verified yet.

## Architecture dependencies

- [Input event model](../architecture.md#input-event-model): `PenController` emits `PenReport` with absolute 0..32767 coordinates over the active tablet area and pressure 0..4095 after the curve.
- [Settings contract](../architecture.md#settings-contract): pen mode, aspect, area, pressure curve, and side-button action come from the `pen_*` keys.
- [HID report contract](../architecture.md#hid-report-contract): over Bluetooth the pen uses report ID 4; this blueprint does not own the descriptor bytes.
- [Wired protocol](../architecture.md#wired-protocol): over USB the pen uses the Pen frame, and `HostInfo` supplies the target monitor size for automatic aspect.

## Local rules and implications

- **Routing**: each pointer is classified by `MotionEvent.getToolType`. Stylus and eraser pointers go to `StylusInput`; finger pointers go to the trackpad.
- **Palm rejection**: finger input is ignored while the pen hovers or touches and for 300 ms after the pen leaves; an in-progress finger gesture is cancelled when the pen arrives.
- **Latency**: `View.requestUnbufferedDispatch` is requested for stylus streams, and every historical sample is forwarded in order.
- **Modes**:
  - `tablet`: absolute mapping into the largest centered rectangle of the target aspect, scaled by `pen_area`. Points outside the area clamp to its edge.
  - `pointer`: relative motion through the pointer speed gain; tip contact holds the left button.
- **Aspect**: `auto` uses `HostInfo` when wired and 16:9 otherwise.
- **Pressure curve**: output = input^gamma, gamma 0.6 (`soft`), 1.0 (`normal`), 1.6 (`firm`).
- **Tilt**: derived from `AXIS_TILT` and `AXIS_ORIENTATION` when reported; zero otherwise.
- **Ink trail**: a front-buffered local stroke drawn while the tip is down, fading out after lift.
- **Galaxy S Ultra tuning**: request the highest refresh rate, exclude the trackpad edges from system back gestures, keep the screen on in the pen canvas, and show a hint about Air command, Air view, and Edge panels.

## Related blueprints

### Required

- [mouse-trackpad](mouse-trackpad.md): the pen shares the trackpad view and routes fingers back to its gesture engine.

### Impact checks

- [bluetooth-hid-transport](bluetooth-hid-transport.md): check when pen fields or ranges change.
- [windows-helper](windows-helper.md): check when Pen frame semantics change.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/input/StylusInput.kt` (MotionEvent adapter), `TouchRouter.kt` (routing and palm rejection), `PenController.kt`, `TabletMapping.kt`
- `android/app/src/main/kotlin/dev/padlink/ui/InkTrail.kt` (API 29+; older devices skip the trail), `PenCanvasScreen.kt`, `DisplayTuning.kt`, `android/app/src/main/kotlin/dev/padlink/PenCanvasActivity.kt`
- Tests: `android/app/src/test/kotlin/dev/padlink/input/PenInputTest.kt`, `TabletMappingTest.kt`

## Acceptance or verification criteria

- [x] Unit tests: tool-type routing, palm-rejection timing, tablet mapping with aspect lock and area, pressure curves, tilt conversion.
- [ ] On device: hover, pressure, and barrel in OneNote or Whiteboard over Bluetooth and wired on a Galaxy S Ultra.

## Remaining gaps and unknowns

- Whether Galaxy S Ultra devices report `AXIS_TILT` for the S Pen is unverified.
- Android sends hover-exit just before contact and contact-end just before hover. The pen therefore counts as gone only after 80 ms without a new sample. That grace period is a PadLink design value and has not been tuned on hardware.
- The tilt sign convention (orientation 0 means the pen points at the top of the screen) is unverified against Windows Ink.
- macOS does not give pressure for generic HID pens.
