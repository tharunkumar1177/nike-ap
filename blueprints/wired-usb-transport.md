<!-- blueprints/wired-usb-transport.md: phone-side TCP server for USB (ADB) and USB (Tethering). -->

# Wired USB transport

## Outcome or responsibility

Over a USB cable, the phone serves the wired protocol so the Windows helper can inject mouse and pen input, either through `adb forward` or through a USB tethering network link.

## Current verified status

**Status:** Planned

No implementation exists yet.

## Architecture dependencies

- [Wired protocol](../architecture.md#wired-protocol): the phone encodes Welcome, PairCode, Mouse, Pen, Ping, Pong, and Bye; it decodes Hello, HostInfo, Ping, Pong, and Bye.
- [Transport selection](../architecture.md#transport-selection): ADB mode binds loopback; tethering mode binds the tether interface address only.
- [Wired pairing and trust](../architecture.md#wired-pairing-and-trust): tethering mode checks the helper token and raises `PairingRequest` for unknown helpers.
- [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics): the sender thread drains the shared outbox into frames.

## Local rules and implications

- One client at a time; a new client replaces the old one after its Hello is accepted.
- Tethering mode rescans network interfaces every 2 s until a tether address exists and rebinds when it changes.
- The header shows `Connecting` while waiting for the helper and `Connected` after Welcome status 0.
- Guidance card: ADB mode explains enabling USB debugging and running the helper; tethering mode offers a button to open the system tethering settings.

## Related blueprints

### Required

None

### Impact checks

- [windows-helper](windows-helper.md): check when frames, ports, or discovery change.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/wire/` (planned)
- `android/app/src/main/kotlin/dev/padlink/transport/wired/` (planned)
- `protocol/test-vectors.txt` (planned)

## Acceptance or verification criteria

- [ ] Unit tests: codec passes all golden vectors in both directions.
- [ ] On device: cursor control through USB (ADB) and USB (Tethering) with the helper.

## Remaining gaps and unknowns

- Tether interface names vary by vendor; the name filter covers `rndis`, `ncm`, and `usb` prefixes.
