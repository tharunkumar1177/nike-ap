<!-- blueprints/windows-helper.md: Rust Windows tray helper that injects wired mouse and pen input. -->

# Windows helper

## Outcome or responsibility

A small Windows tray program that finds the phone over USB or the phone's Wi-Fi hotspot, receives the wired protocol, and moves the Windows cursor and a synthetic Windows Ink pen.

## Current verified status

**Status:** Partial

The protocol crate, the ADB, tethering, and hotspot connection paths, mouse and synthetic pen injection, monitor selection, the tray, and release on disconnect are implemented. Unit tests, including a fake-phone session test, pass in CI, and CI builds the release zip. Acceptance on a Windows PC with a phone has not been recorded yet.

## Architecture dependencies

- [Wired protocol](../architecture.md#wired-protocol): the `padlink-protocol` crate implements the codec and must pass the shared golden vectors.
- [Wired pairing and trust](../architecture.md#wired-pairing-and-trust): the helper creates and keeps its token and shows the pairing code in a message box.
- [Input event model](../architecture.md#input-event-model): wheel and pan arrive in 1/120 notch units and map directly to `WHEEL_DELTA` units; pen ranges map to Windows pen ranges (pressure 0..1024, tilt degrees, rotation degrees).
- [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics): on disconnect the helper releases every held mouse button and lifts the pen.

## Local rules and implications

- **Mode**: the tray offers "USB (ADB)", "USB (Tethering)", and "Wi-Fi (Phone hotspot)" as exclusive choices, matching the phone's picker. The choice is saved in `%APPDATA%\PadLink\helper.conf` (`mode=adb|tether|hotspot`) next to the token. Changing it drops the current session.
- **Connect loop** (retry every second while disconnected): in ADB mode, find `adb.exe` next to the helper, then in its `platform-tools` subfolder, then on `PATH`. Pick the first device in the `device` state, or report "unauthorized" or "no phone", run `adb forward tcp:47800 tcp:47800`, and connect to `127.0.0.1`. In tethering mode, connect to the gateway of any adapter that is up and whose description matches RNDIS, NCM, or "Remote NDIS". In hotspot mode, connect to the IPv4 gateway of the first up IEEE 802.11 adapter, skipping Wi-Fi Direct and other virtual adapters. No Wi-Fi adapter with a gateway reports "connect this PC's Wi-Fi to the phone's hotspot". A refused or timed-out connection reports that the Wi-Fi network is not the phone's hotspot, since the gateway is then usually a home router.
- **Single instance**: a per-user named mutex (`Local\PadLinkHelper`) stops a second copy from starting. Two copies share one token, so each would replace the other's session on the phone in an endless loop.
- **Disconnect reasons**: the tray says whether a session ended during the handshake or after connecting, and whether the phone closed it, went silent for 3 s, or the socket failed.
- **Socket reads**: a dedicated thread does blocking reads with no timeout and passes decoded frames to the link loop over a channel. The link loop applies its deadlines (handshake, pairing, 3 s link timeout) to that channel. Never set a receive timeout on the socket. Winsock leaves a socket whose receive timed out in an indeterminate state that can drop bytes, which broke sessions right after pairing.
- **Mouse injection**: `SendInput` with relative moves, button down/up transitions, `MOUSEEVENTF_WHEEL`, and `MOUSEEVENTF_HWHEEL`.
- **Pen injection**: `CreateSyntheticPointerDevice(PT_PEN)` and `InjectSyntheticPointerInput` with `POINTER_PEN_INFO`; hover, down, update, up, and leave transitions follow the Windows pointer-injection rules. The process is per-monitor DPI aware so coordinates are physical pixels.
- **Monitor selection**: the "Pen display" submenu lists each monitor, with the primary monitor as the default. The chosen monitor's rectangle is the pen target, and its size is sent as `HostInfo`. Changing it updates the phone's aspect lock. Pen coverage across all monitors is not offered.
- **Tray**: shows status text, the mode choices, the pen-display submenu, and Quit. The release zip contains `padlink-helper.exe` and a `platform-tools/` folder with `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`, and Google's `NOTICE.txt`.
- **Release**: dropping the injector releases held buttons and lifts and destroys the synthetic pen, including on Quit.

## Related blueprints

### Required

- [wired-usb-transport](wired-usb-transport.md): the phone side defines when the server is reachable and how pairing is approved.

### Impact checks

None

## Relevant implementation and tests

- `helper/protocol/src/lib.rs` (crate `padlink-protocol`); golden vectors in `helper/protocol/tests/vectors.rs`
- `helper/app/src/` (crate `padlink-helper`): `link.rs` (connect, handshake, session), `mouse.rs`, `pen.rs`, `adb.rs`, `tether.rs`, `store.rs`, `win.rs` (Win32 injection, monitors, DPI), `tray.rs`, `main.rs`. Each module has an inline `#[cfg(test)]` module, except `win.rs`, `tray.rs`, and `main.rs`.

## Acceptance or verification criteria

- [x] Unit tests: golden vectors, mouse transition translation, pen transition translation, coordinate mapping, adb output parsing, tether and Wi-Fi adapter matching, `mode=hotspot` config round-trip, hotspot tray and wrong-network texts.
- [x] Unit tests: handshake, pairing, and input delivery against a fake phone over loopback TCP, including frames sent one byte at a time across many poll intervals.
- [ ] On a Windows PC: cursor, wheel, and pen pressure in OneNote via USB (ADB), USB (Tethering), and Wi-Fi (Phone hotspot).

## Remaining gaps and unknowns

- Windows may show a firewall prompt only for inbound sockets; the helper uses outbound connections only, which is expected to avoid it (unverified).
- Synthetic pen injection needs Windows 10 1809 or later. If the pen device cannot be created, the helper keeps mouse input and drops pen frames without telling the user (untested).
- With several ADB devices attached, the helper uses the first one in the `device` state. There is no device picker.
- With several Wi-Fi adapters up, hotspot mode uses the first one that has a gateway, which may not be the one joined to the phone (untested).
