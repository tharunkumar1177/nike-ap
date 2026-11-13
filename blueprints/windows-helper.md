<!-- blueprints/windows-helper.md: Rust Windows tray helper that injects wired mouse and pen input. -->

# Windows helper

## Outcome or responsibility

A small Windows tray program that finds the phone over USB, receives the wired protocol, and moves the Windows cursor and a synthetic Windows Ink pen.

## Current verified status

**Status:** Planned

No implementation exists yet.

## Architecture dependencies

- [Wired protocol](../architecture.md#wired-protocol): the `padlink-protocol` crate implements the codec and must pass the shared golden vectors.
- [Wired pairing and trust](../architecture.md#wired-pairing-and-trust): the helper creates and keeps its token and shows the pairing code in a message box.
- [Input event model](../architecture.md#input-event-model): wheel and pan arrive in 1/120 notch units and map directly to `WHEEL_DELTA` units; pen ranges map to Windows pen ranges (pressure 0..1024, tilt degrees, rotation degrees).
- [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics): on disconnect the helper releases every held mouse button and lifts the pen.

## Local rules and implications

- **Discovery loop** (every second while disconnected): first ADB (bundled `adb.exe`, then `PATH`, then the Android SDK default path), using `adb forward tcp:47800 tcp:47800` and `127.0.0.1`; then tethering, connecting to the gateway of any up adapter whose description matches RNDIS or NCM.
- **Mouse injection**: `SendInput` with relative moves, button down/up transitions, `MOUSEEVENTF_WHEEL`, and `MOUSEEVENTF_HWHEEL`.
- **Pen injection**: `CreateSyntheticPointerDevice(PT_PEN)` and `InjectSyntheticPointerInput` with `POINTER_PEN_INFO`; hover, down, update, up, and leave transitions follow the Windows pointer-injection rules. The process is per-monitor DPI aware so coordinates are physical pixels.
- **Monitor selection**: the tray menu lists monitors plus "All monitors"; the chosen rectangle is the pen target and its size is sent as `HostInfo`.
- **Tray**: shows status text and offers Quit; the release zip bundles `adb.exe`, `AdbWinApi.dll`, and `AdbWinUsbApi.dll`.

## Related blueprints

### Required

- [wired-usb-transport](wired-usb-transport.md): the phone side defines when the server is reachable and how pairing is approved.

### Impact checks

None

## Relevant implementation and tests

- `helper/protocol/` (planned)
- `helper/padlink-helper/` (planned)

## Acceptance or verification criteria

- [ ] Unit tests: golden vectors, mouse transition translation, pen transition translation, coordinate mapping, adb output parsing, adapter matching.
- [ ] On a Windows PC: cursor, wheel, and pen pressure in OneNote via USB (ADB) and USB (Tethering).

## Remaining gaps and unknowns

- Windows may show a firewall prompt only for inbound sockets; the helper uses outbound connections only, which is expected to avoid it (unverified).
