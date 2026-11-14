<!-- blueprints/wired-usb-transport.md: phone-side TCP server for USB (ADB), USB (Tethering), and Wi-Fi (Phone hotspot). -->

# Wired USB transport

## Outcome or responsibility

The phone serves the wired protocol so the Windows helper can inject mouse and pen input. Over a USB cable this works through `adb forward` or a USB tethering network link. Without a cable it works over the phone's own Wi-Fi hotspot that the PC has joined.

## Current verified status

**Status:** Partial

The codec, the TCP server for all three modes, the pairing flow, the keepalive, and the tether and hotspot interface lookups are implemented. Codec golden vectors and the interface-name tests (tether and hotspot) pass in CI. Device tests with the helper have not been recorded yet for any mode, and hotspot mode has not been tried on a phone.

## Architecture dependencies

- [Wired protocol](../architecture.md#wired-protocol): the phone encodes Welcome, PairCode, Mouse, Pen, Ping, Pong, and Bye; it decodes Hello, HostInfo, Ping, Pong, and Bye.
- [Transport selection](../architecture.md#transport-selection): ADB mode binds loopback; tethering mode binds the tether interface address plus loopback (for `adb forward`); hotspot mode binds only the hotspot interface address.
- [Wired pairing and trust](../architecture.md#wired-pairing-and-trust): tethering and hotspot modes check the helper token and raise `PairingRequest` for unknown helpers.
- [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics): the sender thread drains the shared outbox into frames.

## Local rules and implications

- One client at a time; a new client replaces the old one after its Hello is accepted. Only one pairing can be pending; a second unknown helper is rejected while it waits.
- When a session ends, the transport records why: the helper went silent for 3 s, the helper closed the connection, no Hello arrived, a newer session replaced it, a protocol error, or a socket error. The waiting card shows that reason with the helper's name, and the reason is logged under the `PadLinkWired` tag.
- Socket writes never run on the main thread; `stop()` sends the release frames and Bye from a short-lived thread.
- Tethering and hotspot modes rescan network interfaces every 2 s until their address exists, and rebind when it changes. In tethering mode the loopback listener runs alongside and does not affect the hints.
- Do not replace the specific-address bind with a wildcard bind plus an interface check. On a Galaxy device that check refused real tethering connections, closing them silently during the handshake.
- A loopback local address (unmapped from `::ffff:127.0.0.1` when needed) marks an `adb forward` helper, which skips pairing. Hotspot connections never arrive on loopback, so they always go through token approval.
- Hotspot interface names: `swlan*` (Samsung), `softap*`, `ap*`, and `wlan1` and up. `wlan0` is excluded because it is the phone's own Wi-Fi client; binding it would expose the server on whatever network the phone has joined.
- While the hotspot server listens, `LinkState.listenAddress` carries the bound address and the waiting card shows it as "Hotspot address", so the user can match it with the helper's tray text.
- The header shows `Connecting` while waiting for the helper and `Connected` after Welcome status 0.
- Guidance card: ADB mode explains enabling USB debugging and running the helper. Tethering mode (`TETHER_OFF`) and hotspot mode (`HOTSPOT_OFF`) both offer a button to the system hotspot and tethering screen. The hotspot texts suggest the 5 GHz band, because 2.4 GHz adds noticeable jitter to pointer motion.

## Related blueprints

### Required

None

### Impact checks

- [windows-helper](windows-helper.md): check when frames, ports, or discovery change.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/wire/WireFrame.kt`
- `android/app/src/main/kotlin/dev/padlink/transport/wired/WiredTransport.kt`, `TetherInterfaces.kt`
- `android/app/src/main/kotlin/dev/padlink/ui/ConnectionCards.kt`, `MouseScreen.kt` (picker and guidance)
- `protocol/test-vectors.txt`
- Tests: `android/app/src/test/kotlin/dev/padlink/wire/WireFrameTest.kt`, `android/app/src/test/kotlin/dev/padlink/transport/wired/TetherInterfacesTest.kt`

## Acceptance or verification criteria

- [x] Unit tests: codec passes all golden vectors in both directions.
- [x] Unit tests: tether and hotspot interface-name filters accept and reject the expected names.
- [ ] On device: cursor control through USB (ADB) and USB (Tethering) with the helper.
- [ ] On device: cursor and pen control through Wi-Fi (Phone hotspot) on both the 2.4 GHz and 5 GHz bands.

## Remaining gaps and unknowns

- Tether interface names vary by vendor; the name filter covers `rndis`, `ncm`, and `usb` prefixes.
- Hotspot interface names vary by vendor and Android version. On a phone whose hotspot uses another name, the card stays on "Mobile Hotspot is off" (unverified beyond the listed names).
- On phones that use `wlan1` for a second Wi-Fi client (dual-band client mode), hotspot mode could bind that client interface instead (unverified).
