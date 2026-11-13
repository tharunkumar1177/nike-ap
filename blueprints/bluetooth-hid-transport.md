<!-- blueprints/bluetooth-hid-transport.md: Classic HID and BLE HID-over-GATT transports. -->

# Bluetooth HID transport

## Outcome or responsibility

The phone appears to any Bluetooth computer as a standard HID combo device (keyboard, mouse, consumer control, pen), using Classic Bluetooth HID when available and BLE HID over GATT otherwise. No software is needed on the host.

## Current verified status

**Status:** Partial

The Classic and BLE stacks, the combined descriptor, the report encoding, and the automatic Classic-to-BLE fallback in `TransportRouter` are implemented. Descriptor and encoding tests pass in CI. Pairing with real hosts has not been verified yet.

## Architecture dependencies

- [HID report contract](../architecture.md#hid-report-contract): both stacks register the same descriptor and send the same report payloads; BLE prepends nothing because each report has its own characteristic.
- [Transport selection](../architecture.md#transport-selection): `bt_mode` decides Classic, BLE, or automatic fallback.
- [Connection state model](../architecture.md#connection-state-model): Classic reports `ProfileNotConnected` when the ACL link to the target is up but the HID connection did not follow.
- [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics): both stacks use the shared outbox; BLE waits for `onNotificationSent` before the next notification.

## Local rules and implications

- **Classic**: `BluetoothHidDevice.registerApp` with SDP settings from the contract and out QoS `(service type 1, token rate 800, bucket 32, peak 2000, latency -1, delay variation -1)` taken from the original app. After registration the transport connects to `bt_target` and retries every 5 s while active.
- **Classic `ProfileNotConnected`**: raised when the target's ACL is connected and HID stays disconnected for 8 s after a connect attempt.
- **BLE**: GATT server with HID service 0x1812 (HID Information, Report Map with offset reads, HID Control Point, Protocol Mode, one Report characteristic per input report with Report Reference and CCCD), Battery service 0x180F with the real battery level, and Device Information 0x180A. Encrypted read and write permissions trigger bonding. Advertising is connectable, low-latency, high TX power, with service UUID 0x1812.
- **Discoverability**: the picker's "Pair a new computer" item and the guidance card open the system Bluetooth settings, which keep the phone discoverable while open. A host that connects by itself becomes the stored `bt_target` without restarting the transport.
- **Registration conflict**: if another app holds the HID Device profile, Classic fails registration; `auto` then falls back to BLE.

## Related blueprints

### Required

None

### Impact checks

- [stylus-pen](stylus-pen.md): check when the descriptor or report encoding changes.

## Relevant implementation and tests

- `android/app/src/main/kotlin/dev/padlink/hid/HidDescriptor.kt`, `HidDescriptorParser.kt`, `ReportEncoder.kt`
- `android/app/src/main/kotlin/dev/padlink/transport/bt/ClassicHidTransport.kt`, `BleHidTransport.kt`, `BluetoothSupport.kt`
- `android/app/src/main/kotlin/dev/padlink/transport/TransportRouter.kt` (stack selection and fallback), `Outbox.kt`
- Tests: `android/app/src/test/kotlin/dev/padlink/hid/HidDescriptorTest.kt`, `ReportEncoderTest.kt`; `android/app/src/test/kotlin/dev/padlink/transport/OutboxQueueTest.kt`

## Acceptance or verification criteria

- [x] Unit tests: descriptor parses to report sizes 7, 5, 2, 9 bytes for IDs 1 to 4; mouse splitting and wheel carry.
- [ ] On device: Classic pairing and control with Windows; BLE pairing and control with Windows and macOS.

## Remaining gaps and unknowns

- The BLE Device Information PnP ID uses the open pid.codes vendor 0x1209 with test product 0x0001 until a product ID is registered.
