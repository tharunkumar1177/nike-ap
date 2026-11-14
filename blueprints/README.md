<!-- blueprints/README.md: manifest that routes tasks to the smallest sufficient blueprint set. -->

# Blueprint Manifest

## Loading protocol

1. Match the task by intent and the synonyms in the router.
2. Load one primary blueprint and only its linked `architecture.md` headings.
3. Inspect the listed implementation and tests.
4. Load an impact-check blueprint only if its stated trigger applies.
5. For a shared-contract or ambiguous change, load the complete [architecture](../architecture.md) and every entry below that links the affected heading.
6. Do not load unrelated blueprints.

## Router

| Concepts and synonyms | Responsibility type | Primary blueprint | Required architecture contracts | Required blueprints | Impact checks | Principal implementation and tests |
|---|---|---|---|---|---|---|
| trackpad, touchpad, mouse, gestures, tap, click, scroll strip, pointer speed, acceleration, mouse buttons, haptics, Mouse screen, settings sheet | Capability | [mouse-trackpad](mouse-trackpad.md) | [Input event model](../architecture.md#input-event-model), [Settings contract](../architecture.md#settings-contract), [Connection state model](../architecture.md#connection-state-model) | None | [stylus-pen](stylus-pen.md) when touch routing or palm rejection changes | `android/app/src/main/kotlin/dev/padlink/input/`, `android/app/src/main/kotlin/dev/padlink/ui/`; `android/app/src/test/kotlin/dev/padlink/input/` |
| S Pen, stylus, pen tablet, pressure, hover, tilt, palm rejection, ink trail, pen canvas, tablet area, Galaxy S Ultra tuning | Capability | [stylus-pen](stylus-pen.md) | [Input event model](../architecture.md#input-event-model), [Settings contract](../architecture.md#settings-contract), [HID report contract](../architecture.md#hid-report-contract), [Wired protocol](../architecture.md#wired-protocol) | [mouse-trackpad](mouse-trackpad.md) | [bluetooth-hid-transport](bluetooth-hid-transport.md) when the pen report changes; [windows-helper](windows-helper.md) when Pen frames change | `android/app/src/main/kotlin/dev/padlink/input/StylusInput.kt`, `TouchRouter.kt`, `PenController.kt`, `TabletMapping.kt`, `ui/InkTrail.kt`, `ui/PenCanvasScreen.kt`, `PenCanvasActivity.kt`; `android/app/src/test/kotlin/dev/padlink/input/` |
| Bluetooth, Classic HID, BluetoothHidDevice, BLE, HOGP, GATT, pairing, descriptor, report map, discoverable, connection needs update | Integration | [bluetooth-hid-transport](bluetooth-hid-transport.md) | [HID report contract](../architecture.md#hid-report-contract), [Transport selection](../architecture.md#transport-selection), [Connection state model](../architecture.md#connection-state-model), [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics) | None | [stylus-pen](stylus-pen.md) when the descriptor changes | `android/app/src/main/kotlin/dev/padlink/transport/bt/`, `hid/`; `android/app/src/test/kotlin/dev/padlink/hid/` |
| wired, USB, adb, tethering, Wi-Fi, hotspot, 2.4 GHz, 5 GHz, TCP server, pairing code, wire protocol phone side | Integration | [wired-usb-transport](wired-usb-transport.md) | [Wired protocol](../architecture.md#wired-protocol), [Transport selection](../architecture.md#transport-selection), [Wired pairing and trust](../architecture.md#wired-pairing-and-trust), [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics) | None | [windows-helper](windows-helper.md) when frames or discovery change | `android/app/src/main/kotlin/dev/padlink/transport/wired/`, `wire/`; `android/app/src/test/kotlin/dev/padlink/wire/`; `protocol/test-vectors.txt` |
| Windows helper, tray, SendInput, synthetic pen, pointer injection, adb discovery, tether or Wi-Fi gateway discovery, monitor selection, Rust | Component | [windows-helper](windows-helper.md) | [Wired protocol](../architecture.md#wired-protocol), [Wired pairing and trust](../architecture.md#wired-pairing-and-trust), [Input event model](../architecture.md#input-event-model), [Input delivery and release semantics](../architecture.md#input-delivery-and-release-semantics) | [wired-usb-transport](wired-usb-transport.md) | None | `helper/protocol/`, `helper/app/`; `helper/protocol/tests/`, inline `#[cfg(test)]` modules |
| CI, GitHub Actions, build, workflow, artifacts, release zip | Workflow | [ci-pipeline](ci-pipeline.md) | [Verification boundaries](../architecture.md#verification-boundaries) | None | None | `.github/workflows/ci.yml` |

## Status vocabulary

- `Implemented`: verified in current code and tests.
- `Partial`: some acceptance criteria are verified; gaps are named.
- `Planned`: required behavior is not implemented.
- `Deprecated`: retained only for migration or compatibility.
- `Unknown`: evidence is insufficient; inspect before changing.
- `Superseded`: replaced by a named blueprint.

## Assets

- Diagrams: [assets/diagrams/](../assets/diagrams/)
- Wireframes: [assets/wireframes/](../assets/wireframes/)
