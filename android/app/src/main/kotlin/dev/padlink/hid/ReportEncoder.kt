// ReportEncoder.kt: converts transport-neutral reports into HID input report payloads.
package dev.padlink.hid

import dev.padlink.input.MouseReport
import dev.padlink.input.PenReport

/** Fractional wheel and pan remainders (1/120 units) carried between mouse reports. */
class WheelCarry {
    var wheel: Int = 0
    var pan: Int = 0

    /** Clears both remainders. */
    fun reset() {
        wheel = 0
        pan = 0
    }
}

/** Encodes report payloads for IDs 2 (mouse) and 4 (pen); payloads exclude the report ID byte. */
object ReportEncoder {

    private const val MAX_I8 = 127

    /**
     * Splits [report] into mouse payloads of `buttons, x, y, wheel, pan`. Deltas beyond ±127 become
     * several payloads with the same buttons; whole wheel/pan notches ride in the first payload and
     * fractions carry in [carry]. Returns an empty list for a purely fractional scroll.
     */
    fun mouse(report: MouseReport, carry: WheelCarry): List<ByteArray> {
        val wheelTotal = carry.wheel + report.wheel
        val wheelNotches = (wheelTotal / MouseReport.NOTCH).coerceIn(-MAX_I8, MAX_I8)
        carry.wheel = wheelTotal - wheelNotches * MouseReport.NOTCH
        val panTotal = carry.pan + report.pan
        val panNotches = (panTotal / MouseReport.NOTCH).coerceIn(-MAX_I8, MAX_I8)
        carry.pan = panTotal - panNotches * MouseReport.NOTCH

        val scrollOnly = report.dx == 0 && report.dy == 0 && (report.wheel != 0 || report.pan != 0)
        if (scrollOnly && wheelNotches == 0 && panNotches == 0) return emptyList()

        val buttons = (report.buttons and 0x1F).toByte()
        val out = ArrayList<ByteArray>(1)
        var remX = report.dx
        var remY = report.dy
        var first = true
        do {
            val cx = remX.coerceIn(-MAX_I8, MAX_I8)
            val cy = remY.coerceIn(-MAX_I8, MAX_I8)
            remX -= cx
            remY -= cy
            out += byteArrayOf(
                buttons,
                cx.toByte(),
                cy.toByte(),
                (if (first) wheelNotches else 0).toByte(),
                (if (first) panNotches else 0).toByte(),
            )
            first = false
        } while (remX != 0 || remY != 0)
        return out
    }

    /** Encodes a pen payload: flags, x u16, y u16, pressure u16, tiltX i8, tiltY i8 (little-endian). */
    fun pen(report: PenReport): ByteArray {
        val eraser = report.flags and PenReport.ERASER != 0
        val tip = report.tip
        var flags = 0
        if (tip && !eraser) flags = flags or 0x01
        if (report.flags and PenReport.BARREL != 0) flags = flags or 0x02
        if (tip && eraser) flags = flags or 0x04
        if (report.flags and PenReport.INVERT != 0) flags = flags or 0x08
        if (report.inRange) flags = flags or 0x10
        val x = report.x.coerceIn(0, PenReport.MAX_COORD)
        val y = report.y.coerceIn(0, PenReport.MAX_COORD)
        val p = if (tip) report.pressure.coerceIn(0, PenReport.MAX_PRESSURE) else 0
        return byteArrayOf(
            flags.toByte(),
            (x and 0xFF).toByte(), (x shr 8).toByte(),
            (y and 0xFF).toByte(), (y shr 8).toByte(),
            (p and 0xFF).toByte(), (p shr 8).toByte(),
            report.tiltX.coerceIn(-PenReport.MAX_TILT, PenReport.MAX_TILT).toByte(),
            report.tiltY.coerceIn(-PenReport.MAX_TILT, PenReport.MAX_TILT).toByte(),
        )
    }

    /** All-zero payload of [size] bytes, used to answer GET_REPORT and to release held state. */
    fun idle(size: Int): ByteArray = ByteArray(size)
}
