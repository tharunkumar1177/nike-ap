// Reports.kt: transport-neutral mouse and pen reports (see "Input event model" in architecture.md).
package dev.padlink.input

/** Relative mouse report: [dx]/[dy] in counts, [wheel]/[pan] in 1/120 notch units. */
data class MouseReport(
    val buttons: Int,
    val dx: Int = 0,
    val dy: Int = 0,
    val wheel: Int = 0,
    val pan: Int = 0,
) {
    companion object {
        const val LEFT = 0x01
        const val RIGHT = 0x02
        const val MIDDLE = 0x04

        /** One wheel notch in report units. */
        const val NOTCH = 120
    }
}

/** Absolute pen report: [x]/[y] 0..32767 over the tablet area, [pressure] 0..4095. */
data class PenReport(
    val flags: Int,
    val x: Int,
    val y: Int,
    val pressure: Int,
    val tiltX: Int = 0,
    val tiltY: Int = 0,
    val twist: Int = 0,
) {
    /** True while the pen hovers or touches. */
    val inRange: Boolean get() = flags and IN_RANGE != 0

    /** True while the tip (or eraser) touches the surface. */
    val tip: Boolean get() = flags and TIP != 0

    companion object {
        const val IN_RANGE = 0x01
        const val TIP = 0x02
        const val BARREL = 0x04
        const val ERASER = 0x08
        const val INVERT = 0x10

        const val MAX_COORD = 32767
        const val MAX_PRESSURE = 4095
        const val MAX_TILT = 60

        /** Report that lifts the pen out of range. */
        val OUT_OF_RANGE = PenReport(flags = 0, x = 0, y = 0, pressure = 0)
    }
}
