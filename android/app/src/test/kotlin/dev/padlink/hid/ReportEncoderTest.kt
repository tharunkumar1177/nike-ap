// ReportEncoderTest.kt: verifies mouse splitting, wheel carry, and pen payload layout.
package dev.padlink.hid

import dev.padlink.input.MouseReport
import dev.padlink.input.PenReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportEncoderTest {

    /** Converts a payload to signed ints for readable assertions. */
    private fun ints(bytes: ByteArray) = bytes.map { it.toInt() }

    /** Large deltas split at ±127 with the same buttons in each payload. */
    @Test
    fun splitsLargeDeltas() {
        val out = ReportEncoder.mouse(MouseReport(MouseReport.LEFT, dx = 300, dy = -130), WheelCarry())
        assertEquals(3, out.size)
        assertEquals(listOf(1, 127, -127, 0, 0), ints(out[0]))
        assertEquals(listOf(1, 127, -3, 0, 0), ints(out[1]))
        assertEquals(listOf(1, 46, 0, 0, 0), ints(out[2]))
    }

    /** A button change with no motion still yields one payload. */
    @Test
    fun buttonOnlyReport() {
        val out = ReportEncoder.mouse(MouseReport(MouseReport.RIGHT), WheelCarry())
        assertEquals(listOf(listOf(2, 0, 0, 0, 0)), out.map(::ints))
    }

    /** Whole notches ride in the first payload; fractions carry to the next event. */
    @Test
    fun wheelCarry() {
        val carry = WheelCarry()
        assertTrue(ReportEncoder.mouse(MouseReport(0, wheel = 60), carry).isEmpty())
        val out = ReportEncoder.mouse(MouseReport(0, wheel = 60, pan = -240), carry)
        assertEquals(listOf(listOf(0, 0, 0, 1, -2)), out.map(::ints))
        assertEquals(0, carry.wheel)
        assertEquals(0, carry.pan)
    }

    /** Pen payload is flags, x, y, pressure little-endian, then tilt. */
    @Test
    fun penPayload() {
        val report = PenReport(
            flags = PenReport.IN_RANGE or PenReport.TIP or PenReport.BARREL,
            x = 0x1234, y = 0x7FFF, pressure = 0x0ABC, tiltX = -20, tiltY = 70,
        )
        val expected = byteArrayOf(0x13, 0x34, 0x12, 0xFF.toByte(), 0x7F, 0xBC.toByte(), 0x0A, (-20).toByte(), 60)
        assertArrayEquals(expected, ReportEncoder.pen(report))
    }

    /** Eraser contact sets the eraser bit instead of tip, and hover-only drops pressure. */
    @Test
    fun penEraserAndHover() {
        val eraser = PenReport(
            flags = PenReport.IN_RANGE or PenReport.TIP or PenReport.ERASER or PenReport.INVERT,
            x = 0, y = 0, pressure = 100,
        )
        assertEquals(0x1C, ReportEncoder.pen(eraser)[0].toInt())
        val hover = PenReport(flags = PenReport.IN_RANGE, x = 10, y = 10, pressure = 500)
        val bytes = ReportEncoder.pen(hover)
        assertEquals(0x10, bytes[0].toInt())
        assertEquals(0, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt())
    }
}
