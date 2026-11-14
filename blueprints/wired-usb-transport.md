<!-- blueprints/wired-usb-transport.md: phone-side TCP server for the Windows helper, with the USB (ADB) and USB (Tethering) modes. -->

# Wired USB transport

## Outcome or responsibility

Over a USB cable, the phone serves the wired protocol so the Windows helper can inject mouse and pen input, either through `adb forward` or through a USB tethering network link. This blueprint also owns the TCP server, session handling, and pairing flow that [wifi-transport](wifi-transport.md) reuses for its hotspot mode.

## Current verified status

**Status:** Partial

The codec, the TCP server, the pairing flow, the keepalive, and the tether interface lookup are implemented. Codec golden vectors and the interface-name tests pass in CI. Cable tests with the helper have not been recorded yet.

## Architecture dependencies

- [Wired protocol](../architecture.md#wired-protocol): the phone encodes Welcome, PairCode, Mouse, Pen, Ping, Pong, and Bye; it decodes Hello, HostInfo, Ping, Pong, and Bye.
- [Transport selection](../architecture.md#transport-selection): ADB mode binds loopback; tethering mode binds the tether interface address plus loopback (for `adb forward`).
- [Wired pairing and trust](../architecture.md#wired-pairing-and-trust): tethering mode checks the helper token and raises `PairingRequest` for unknown helpers.
- [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics): the sender thread drains the shared outbox into frames.

## Local rules and implications

- One client at a time; a new client replaces the old one after its Hello is accepted. Only one pairing can be pending; a second unknown helper is rejected while it waits.
- When a session ends, the transport records why: the helper went silent for 3 s, the helper closed the connection, no Hello arrived, a newer session replaced it, a protocol error, or a socket error. The waiting card shows that reason with the helper's name, and the reason is logged under the `PadLinkWired` tag.
- Until the first connection reaches the server, `LinkState.helperReached` is false and the tethering and hotspot waiting cards say that no computer has reached the phone yet. This separates connections the PC never delivered (blocked by a VPN or security software) from ones the phone dropped. Each accepted connection is logged with its remote and local addresses under `PadLinkWired`.
- Socket writes never run on the main thread; `stop()` sends the release frames and Bye from a short-lived thread.
- Tethering mode rescans network interfaces every 2 s until a tether address exists, and rebinds when it changes. The loopback listener runs alongside it and does not affect the hints.
- Do not replace the specific-address bind with a wildcard bind plus an interface check. On a Galaxy device that check refused real tethering connections, closing them silently during the handshake.
- A loopback local address (unmapped from `::ffff:127.0.0.1` when needed) marks an `adb forward` helper, which skips pairing. Every other connection goes through token approval.
- The header shows `Connecting` while waiting for the helper and `Connected` after Welcome status 0.
- Guidance card: ADB mode explains enabling USB debugging and running the helper; tethering mode (`TETHER_OFF`) offers a button to the system hotspot and tethering screen.

## Related blueprints

### Required

None

### Impact checks

- [windows-helper](windows-helper.md): check when frames, ports, or discovery change.
- [wifi-transport](wifi-transport.md): check when the server loop, session handling, pairing flow, or drop reasons change, since hotspot mode runs on them.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/wire/WireFrame.kt`
- `android/app/src/main/kotlin/dev/padlink/transport/wired/WiredTransport.kt`, `TetherInterfaces.kt`
- `protocol/test-vectors.txt`
- Tests: `android/app/src/test/kotlin/dev/padlink/wire/WireFrameTest.kt`, `android/app/src/test/kotlin/dev/padlink/transport/wired/TetherInterfacesTest.kt`

## Acceptance or verification criteria

- [x] Unit tests: codec passes all golden vectors in both directions.
- [x] Unit tests: the tether interface-name filter accepts and rejects the expected names.
- [ ] On device: cursor control through USB (ADB) and USB (Tethering) with the helper.

## Remaining gaps and unknowns

- Tether interface names vary by vendor; the name filter covers `rndis`, `ncm`, and `usb` prefixes.
