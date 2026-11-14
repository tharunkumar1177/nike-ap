// PointerAccel.kt: pixel-to-count pointer conversion and pixel-to-notch scroll conversion with remainders.
package dev.padlink.input

import kotlin.math.hypot
import kotlin.math.pow

/** Converts finger motion in pixels into relative mouse counts, carrying sub-count remainders. */
class PointerAccel(private val density: Float) {

    /** Pointer speed setting, 0..100. */
    var speed: Int = 50

    private var remX = 0f
    private var remY = 0f

    /** Converts a finger delta over [dtMs] into counts using speed gain and velocity acceleration. */
    fun convert(dxPx: Float, dyPx: Float, dtMs: Long): Pair<Int, Int> {
        val dxDp = dxPx / density
        val dyDp = dyPx / density
        val velocity = hypot(dxDp, dyDp) / dtMs.coerceAtLeast(1L)
        val factor = BASE_COUNTS_PER_DP * gain(speed) * velocityFactor(velocity)
        return carry(dxDp * factor, dyDp * factor)
    }

    /** Converts a delta with the speed gain only (no acceleration), for the relative pen mode. */
    fun convertLinear(dxPx: Float, dyPx: Float): Pair<Int, Int> {
        val factor = BASE_COUNTS_PER_DP * gain(speed)
        return carry(dxPx / density * factor, dyPx / density * factor)
    }

    /** Clears remainders, for example when a new gesture starts. */
    fun reset() {
        remX = 0f
        remY = 0f
    }

    /** Adds remainders, truncates toward zero, and keeps the new fractions. */
    private fun carry(fx: Float, fy: Float): Pair<Int, Int> {
        val tx = fx + remX
        val ty = fy + remY
        val ix = tx.toInt()
        val iy = ty.toInt()
        remX = tx - ix
        remY = ty - iy
        return ix to iy
    }

    companion object {
        /** Counts per dp at speed 50 without acceleration. */
        const val BASE_COUNTS_PER_DP = 1.5f

        /** Speed multiplier: 0.25x at 0, 1x at 50, 4x at 100. */
        fun gain(speed: Int): Float = 2f.pow((speed.coerceIn(0, 100) - 50) / 25f)

        /** Velocity factor: 1.0 at or below 0.1 dp/ms, rising linearly to 2.5 at 1.5 dp/ms and above. */
        fun velocityFactor(dpPerMs: Float): Float = when {
            dpPerMs <= 0.1f -> 1f
            dpPerMs >= 1.5f -> 2.5f
            else -> 1f + (dpPerMs - 0.1f) / (1.5f - 0.1f) * 1.5f
        }
    }
}

/** Converts scroll finger travel into 1/120 notch units, carrying fractions. */
class ScrollAccumulator(private val density: Float) {

    /** Scroll speed setting, 0..100. */
    var speed: Int = 50

    /** When true, both axes are inverted. */
    var invert: Boolean = false

    private var remWheel = 0f
    private var remPan = 0f

    /**
     * Converts finger travel into (wheel, pan). Finger moving down scrolls content down (negative
     * wheel); finger moving right scrolls right (positive pan).
     */
    fun convert(dxPx: Float, dyPx: Float): Pair<Int, Int> {
        val unitsPerDp = MouseReport.NOTCH / DP_PER_NOTCH * PointerAccel.gain(speed)
        val sign = if (invert) -1f else 1f
        val w = -dyPx / density * unitsPerDp * sign + remWheel
        val p = dxPx / density * unitsPerDp * sign + remPan
        val iw = w.toInt()
        val ip = p.toInt()
        remWheel = w - iw
        remPan = p - ip
        return iw to ip
    }

    /** Clears remainders. */
    fun reset() {
        remWheel = 0f
        remPan = 0f
    }

    companion object {
        /** Finger travel in dp that equals one notch at speed 50. */
        const val DP_PER_NOTCH = 40f
    }
}
