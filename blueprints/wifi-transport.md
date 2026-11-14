<!-- blueprints/wifi-transport.md: phone-side Wi-Fi (Phone hotspot) mode for the Windows helper. -->

# Wi-Fi transport

## Outcome or responsibility

Without a cable, the phone serves the wired protocol over its own Mobile Hotspot, and the Windows helper on a PC joined to that hotspot injects mouse and pen input. This blueprint owns the hotspot-specific behavior on the phone: which interface the server binds, what the user sees while it waits, and the radio guidance. The TCP server, sessions, pairing flow, and codec it runs on are owned by [wired-usb-transport](wired-usb-transport.md).

## Current verified status

**Status:** Partial

`WiredMode.HOTSPOT`, the hotspot interface lookup, the `HOTSPOT_OFF` guidance, the listen-address display, and the picker entry are implemented. The interface-name tests pass in CI. No device test with a phone and a Windows PC has been recorded yet.

## Architecture dependencies

- [Transport selection](../architecture.md#transport-selection): hotspot mode binds only the hotspot interface address, never loopback or `wlan0`, and shared networks such as a home router are out of scope.
- [Wired pairing and trust](../architecture.md#wired-pairing-and-trust): every hotspot helper goes through token approval, using the same `approved_helpers` set as tethering.
- [Wired protocol](../architecture.md#wired-protocol): frames, port 47800, keepalive, and timeouts are identical to the USB modes; nothing is Wi-Fi specific on the wire.
- [Settings contract](../architecture.md#settings-contract): the mode is persisted as `transport=wifi_hotspot`.

## Local rules and implications

- Hotspot interface names: `swlan*` (Samsung), `softap*`, `ap*`, and `wlan1` and up. `wlan0` is excluded because it is the phone's own Wi-Fi client; binding it would expose the server on whatever network the phone has joined.
- The server rescans interfaces every 2 s until a hotspot address exists, and rebinds when it changes (for example after the hotspot restarts with a new subnet). It never falls back to a wildcard bind.
- There is no loopback listener in this mode, so a helper using `adb forward` cannot connect and every hotspot connection needs approval.
- While the server listens, `LinkState.listenAddress` carries the bound address, and the waiting card shows it as "Hotspot address" so the user can match it with the address in the helper's tray text.
- Guidance: `HOTSPOT_OFF` shows "Mobile Hotspot is off" with a button to the system hotspot and tethering screen. The waiting card and the off card both recommend the 5 GHz hotspot band, because 2.4 GHz adds noticeable jitter to pointer motion. The band itself is a system setting; the app does not read or change it.
- Company PCs often route `10.x` addresses through a VPN or zero-trust agent, and Samsung often picks a `10.x` hotspot subnet. When the helper reports "closed before the phone answered" and the phone's card still says no computer has reached it, the connection never left the PC. The helper's adapter binding is the mitigation; the shared "not reached" indicator is described in [wired-usb-transport](wired-usb-transport.md).
- The picker labels the mode "Wi-Fi (Phone hotspot)" with the Wi-Fi icon, matching the helper's tray item.

## Related blueprints

### Required

- [wired-usb-transport](wired-usb-transport.md): this mode runs on its TCP server, session handling, pairing flow, and drop reasons.

### Impact checks

- [windows-helper](windows-helper.md): check when the hotspot address, port, or discovery rules change, since the helper finds the phone as its Wi-Fi gateway.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/transport/wired/TetherInterfaces.kt` (`isHotspotName`, `findHotspotAddress`)
- `android/app/src/main/kotlin/dev/padlink/transport/wired/WiredTransport.kt` (`WiredMode.HOTSPOT`, `listenAddress`)
- `android/app/src/main/kotlin/dev/padlink/transport/Transport.kt` (`TransportStack.WIFI_HOTSPOT`, `LinkIssue.HOTSPOT_OFF`)
- `android/app/src/main/kotlin/dev/padlink/ui/ConnectionCards.kt`, `MouseScreen.kt` (guidance, picker)
- Tests: `android/app/src/test/kotlin/dev/padlink/transport/wired/TetherInterfacesTest.kt`
- Wireframe: [main-transport-picker.svg](../assets/wireframes/main-transport-picker.svg)

## Acceptance or verification criteria

- [x] Unit tests: the hotspot interface-name filter accepts `swlan0`, `ap0`, `softap0`, `wlan1`, and rejects `wlan0`, USB tether, cellular, loopback, and P2P names.
- [ ] On device: cursor and pen control through Wi-Fi (Phone hotspot) on both the 2.4 GHz and 5 GHz bands.
- [ ] On device: turning the hotspot off shows "Mobile Hotspot is off", and turning it back on reconnects without restarting the app.

## Remaining gaps and unknowns

- Hotspot interface names vary by vendor and Android version. On a phone whose hotspot uses another name, the card stays on "Mobile Hotspot is off" (unverified beyond the listed names).
- On phones that use `wlan1` for a second Wi-Fi client (dual-band client mode), this mode could bind that client interface instead (unverified).
- First field report: the helper reached `10.83.23.214:47800` and the connection closed before any reply while the phone kept waiting. That points to interception on the PC; whether adapter binding fixes it is unverified.
- Latency and jitter on 2.4 GHz versus 5 GHz have not been measured.
- Shared-router mode (discovery plus an encrypted, mutually authenticated link) and Wi-Fi Direct are not planned yet.
