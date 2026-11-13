// Settings.kt: typed settings model for the trackpad, pen, and connection (keys defined in architecture.md).
package dev.padlink.settings

/** Mouse buttons that can be shown on the Mouse screen, with their persisted set values. */
enum class MouseButtonId(val key: String, val mask: Int) {
    LEFT("left", 0x01),
    RIGHT("right", 0x02),
    MIDDLE("middle", 0x04);

    companion object {
        /** Resolves persisted keys into buttons, ignoring unknown entries. */
        fun fromKeys(keys: Set<String>): Set<MouseButtonId> = entries.filter { it.key in keys }.toSet()
    }
}

/** Which transport carries input to the computer. */
enum class TransportKind(val key: String) {
    BLUETOOTH("bluetooth"),
    USB_ADB("usb_adb"),
    USB_TETHER("usb_tether");

    companion object {
        /** Parses a persisted key, falling back to Bluetooth. */
        fun fromKey(key: String?): TransportKind = entries.firstOrNull { it.key == key } ?: BLUETOOTH
    }
}

/** Which Bluetooth HID stack to use. */
enum class BtMode(val key: String) {
    AUTO("auto"),
    CLASSIC("classic"),
    BLE("ble");

    companion object {
        /** Parses a persisted key, falling back to automatic selection. */
        fun fromKey(key: String?): BtMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** How the S Pen drives the computer. */
enum class PenMode(val key: String) {
    TABLET("tablet"),
    POINTER("pointer");

    companion object {
        /** Parses a persisted key, falling back to tablet mode. */
        fun fromKey(key: String?): PenMode = entries.firstOrNull { it.key == key } ?: TABLET
    }
}

/** Aspect ratio of the active tablet area; [ratio] is width / height, null for auto or stretch. */
enum class PenAspect(val key: String, val ratio: Float?) {
    AUTO("auto", null),
    WIDE_16_9("16:9", 16f / 9f),
    WIDE_16_10("16:10", 16f / 10f),
    RATIO_3_2("3:2", 3f / 2f),
    ULTRAWIDE_21_9("21:9", 21f / 9f),
    STRETCH("stretch", null);

    companion object {
        /** Parses a persisted key, falling back to automatic aspect. */
        fun fromKey(key: String?): PenAspect = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** Pressure response curve; output = input ^ [gamma]. */
enum class PressureCurve(val key: String, val gamma: Float) {
    SOFT("soft", 0.6f),
    NORMAL("normal", 1.0f),
    FIRM("firm", 1.6f);

    companion object {
        /** Parses a persisted key, falling back to the linear curve. */
        fun fromKey(key: String?): PressureCurve = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/** What the S Pen side button does. */
enum class SideButtonAction(val key: String) {
    BARREL("barrel"),
    ERASER("eraser");

    companion object {
        /** Parses a persisted key, falling back to the barrel button. */
        fun fromKey(key: String?): SideButtonAction = entries.firstOrNull { it.key == key } ?: BARREL
    }
}

/** Trackpad and display settings inherited from the original app. */
data class MouseSettings(
    val pointerSpeed: Int = 50,
    val scrollSpeed: Int = 50,
    val invertScroll: Boolean = false,
    val touchClicks: Boolean = true,
    val buttonsTop: Boolean = false,
    val buttonsBottom: Boolean = true,
    val visibleButtons: Set<MouseButtonId> = setOf(MouseButtonId.LEFT, MouseButtonId.RIGHT),
    val scrollBarLeft: Boolean = false,
    val scrollBarRight: Boolean = true,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = false,
)

/** S Pen tablet settings. */
data class PenSettings(
    val mode: PenMode = PenMode.TABLET,
    val aspect: PenAspect = PenAspect.AUTO,
    val areaPercent: Int = 100,
    val pressure: PressureCurve = PressureCurve.NORMAL,
    val sideButton: SideButtonAction = SideButtonAction.BARREL,
)

/** Transport choice and Bluetooth target. */
data class ConnectionSettings(
    val transport: TransportKind = TransportKind.BLUETOOTH,
    val btMode: BtMode = BtMode.AUTO,
    val btTarget: String? = null,
)

/** Complete settings snapshot. */
data class Settings(
    val mouse: MouseSettings = MouseSettings(),
    val pen: PenSettings = PenSettings(),
    val connection: ConnectionSettings = ConnectionSettings(),
    val samsungHintDismissed: Boolean = false,
)
