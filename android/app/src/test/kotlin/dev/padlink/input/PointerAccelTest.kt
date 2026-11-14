// PointerAccelTest.kt: verifies speed gain, velocity factor, remainder carry, and scroll conversion.
package dev.padlink.input

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerAccelTest {

    /** Gain doubles every 25 speed points around 1x at 50. */
    @Test
    fun gainCurve() {
        assertEquals(1f, PointerAccel.gain(50), 1e-6f)
        assertEquals(2f, PointerAccel.gain(75), 1e-6f)
        assertEquals(0.5f, PointerAccel.gain(25), 1e-6f)
        assertEquals(4f, PointerAccel.gain(100), 1e-6f)
        assertEquals(0.25f, PointerAccel.gain(0), 1e-6f)
    }

    /** Velocity factor is flat when slow, capped when fast, and linear between. */
    @Test
    fun velocityFactorCurve() {
        assertEquals(1f, PointerAccel.velocityFactor(0.05f), 1e-6f)
        assertEquals(2.5f, PointerAccel.velocityFactor(3f), 1e-6f)
        assertEquals(1.75f, PointerAccel.velocityFactor(0.8f), 1e-4f)
    }

    /** Sub-count motion accumulates instead of being lost. */
    @Test
    fun remaindersCarry() {
        val accel = PointerAccel(density = 1f)
        var total = 0
        repeat(10) { total += accel.convert(1f, 0f, 100).first }
        assertEquals(15, total)
    }

    /** Negative motion truncates toward zero and carries symmetrically. */
    @Test
    fun negativeRemaindersCarry() {
        val accel = PointerAccel(density = 1f)
        var total = 0
        repeat(10) { total += accel.convert(0f, -1f, 100).second }
        assertEquals(-15, total)
    }

    /** Density converts pixels to dp before applying gain. */
    @Test
    fun densityNormalizes() {
        val accel = PointerAccel(density = 2f)
        assertEquals(15 to 0, accel.convert(20f, 0f, 1000))
    }

    /** Forty dp of downward finger travel is one notch down at speed 50; invert flips it. */
    @Test
    fun scrollConversion() {
        val scroll = ScrollAccumulator(density = 1f)
        assertEquals(-120 to 0, scroll.convert(0f, 40f))
        scroll.invert = true
        assertEquals(120 to 0, scroll.convert(0f, 40f))
        assertEquals(0 to -120, scroll.convert(40f, 0f))
    }
}
