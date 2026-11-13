<!-- blueprints/wired-usb-transport.md: phone-side TCP server for USB (ADB) and USB (Tethering). -->

# Wired USB transport

## Outcome or responsibility

Over a USB cable, the phone serves the wired protocol so the Windows helper can inject mouse and pen input, either through `adb forward` or through a USB tethering network link.

## Current verified status

**Status:** Partial

The codec, the TCP server for both modes, the pairing flow, the keepalive, and the tether interface lookup are implemented. Codec golden vectors and interface-name tests pass in CI. Cable tests with the helper have not been recorded yet.

## Architecture dependencies

- [Wired protocol](../architecture.md#wired-protocol): the phone encodes Welcome, PairCode, Mouse, Pen, Ping, Pong, and Bye; it decodes Hello, HostInfo, Ping, Pong, and Bye.
- [Transport selection](../architecture.md#transport-selection): ADB mode binds loopback; tethering mode listens on all interfaces and accepts only loopback and tether-interface connections.
- [Wired pairing and trust](../architecture.md#wired-pairing-and-trust): tethering mode checks the helper token and raises `PairingRequest` for unknown helpers.
- [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics): the sender thread drains the shared outbox into frames.

## Local rules and implications

- One client at a time; a new client replaces the old one after its Hello is accepted. Only one pairing can be pending; a second unknown helper is rejected while it waits.
- When a session ends, the transport records why: the helper went silent for 3 s, the helper closed the connection, no Hello arrived, a newer session replaced it, a protocol error, or a socket error. The waiting card shows that reason with the helper's name, and the reason is logged under the `PadLinkWired` tag.
- Socket writes never run on the main thread; `stop()` sends the release frames and Bye from a short-lived thread.
- Tethering mode checks the network interfaces every 2 s to show or clear the "USB tethering is off" hint. It never rebinds, so a new tether address that Android picks takes effect immediately. Local addresses may arrive IPv4-mapped (`::ffff:a.b.c.d`) and are unmapped before the loopback and tether checks.
- The header shows `Connecting` while waiting for the helper and `Connected` after Welcome status 0.
- Guidance card: ADB mode explains enabling USB debugging and running the helper; tethering mode offers a button to open the system tethering settings.

## Related blueprints

### Required

None

### Impact checks

- [windows-helper](windows-helper.md): check when frames, ports, or discovery change.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/wire/WireFrame.kt`
- `android/app/src/main/kotlin/dev/padlink/transport/wired/WiredTransport.kt`, `TetherInterfaces.kt`
- `protocol/test-vectors.txt`
- Tests: `android/app/src/test/kotlin/dev/padlink/wire/WireFrameTest.kt`, `android/app/src/test/kotlin/dev/padlink/transport/wired/TetherInterfacesTest.kt`

## Acceptance or verification criteria

- [x] Unit tests: codec passes all golden vectors in both directions.
- [ ] On device: cursor control through USB (ADB) and USB (Tethering) with the helper.

## Remaining gaps and unknowns

- Tether interface names vary by vendor; the name filter covers `rndis`, `ncm`, and `usb` prefixes.
