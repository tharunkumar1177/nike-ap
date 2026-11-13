// TabletMappingTest.kt: verifies aspect letterboxing, area scaling, clamping, pressure curves, and tilt.
package dev.padlink.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TabletMappingTest {

    /** A 16:9 target in a 2:1 landscape view is pillarboxed and centered. */
    @Test
    fun pillarboxesWideView() {
        val a = TabletMapping.area(2000f, 1000f, 16f / 9f, 100)
        assertEquals(1777.78f, a.width, 0.01f)
        assertEquals(1000f, a.height, 0.01f)
        assertEquals(111.11f, a.left, 0.01f)
        assertEquals(0f, a.top, 0.01f)
    }

    /** A 16:9 target in a portrait view is letterboxed. */
    @Test
    fun letterboxesTallView() {
        val a = TabletMapping.area(900f, 2000f, 16f / 9f, 100)
        assertEquals(900f, a.width, 0.01f)
        assertEquals(506.25f, a.height, 0.01f)
        assertEquals(746.875f, a.top, 0.01f)
    }

    /** Stretch uses the whole view; area percent shrinks about the center. */
    @Test
    fun stretchAndAreaScale() {
        val full = TabletMapping.area(1000f, 500f, null, 100)
        assertEquals(TabletArea(0f, 0f, 1000f, 500f), full)
        val half = TabletMapping.area(1000f, 500f, null, 50)
        assertEquals(TabletArea(250f, 125f, 500f, 250f), half)
        assertEquals(half, TabletMapping.area(1000f, 500f, null, 10))
    }

    /** Mapping covers 0..32767 and clamps outside points to the edges. */
    @Test
    fun mapsAndClamps() {
        val a = TabletArea(100f, 100f, 1000f, 500f)
        assertEquals(0 to 0, TabletMapping.map(100f, 100f, a))
        assertEquals(PenReport.MAX_COORD to PenReport.MAX_COORD, TabletMapping.map(1100f, 600f, a))
        assertEquals(16384 to 16384, TabletMapping.map(600f, 350f, a))
        assertEquals(0 to PenReport.MAX_COORD, TabletMapping.map(-50f, 9000f, a))
    }

    /** Pressure curves: linear midpoint, soft raises, firm lowers, and inputs above 1 clamp. */
    @Test
    fun pressureCurves() {
        assertEquals(2048, TabletMapping.pressure(0.5f, 1.0f))
        assertEquals(2702, TabletMapping.pressure(0.5f, 0.6f))
        assertEquals(1351, TabletMapping.pressure(0.5f, 1.6f))
        assertEquals(PenReport.MAX_PRESSURE, TabletMapping.pressure(1.3f, 1.0f))
        assertEquals(0, TabletMapping.pressure(-0.1f, 1.0f))
    }

    /** Tilt converts by orientation and clamps to ±60 degrees. */
    @Test
    fun tiltConversion() {
        val deg30 = Math.toRadians(30.0).toFloat()
        assertEquals(0 to 0, TabletMapping.tilt(0f, 1f))
        assertEquals(30 to 0, TabletMapping.tilt(deg30, (Math.PI / 2).toFloat()))
        assertEquals(0 to -30, TabletMapping.tilt(deg30, 0f))
        assertEquals(-60 to 0, TabletMapping.tilt(Math.toRadians(80.0).toFloat(), (-Math.PI / 2).toFloat()))
    }
}
