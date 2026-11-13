// TabletMapping.kt: tablet-area letterboxing, absolute coordinate mapping, pressure curve, and tilt conversion.
package dev.padlink.input

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Active tablet rectangle in view pixels. */
data class TabletArea(val left: Float, val top: Float, val width: Float, val height: Float) {
    /** Right edge in view pixels. */
    val right: Float get() = left + width

    /** Bottom edge in view pixels. */
    val bottom: Float get() = top + height
}

/** Pure mapping functions for the S Pen tablet mode. */
object TabletMapping {

    /** Largest pen tilt the device can report before tan() becomes unstable, in radians (~85°). */
    private const val MAX_TILT_RAD = 1.48f

    /**
     * Largest rectangle of [aspect] (width / height; null fills the view) centered in the view,
     * then scaled about its center by [areaPercent] (50..100).
     */
    fun area(viewWidth: Float, viewHeight: Float, aspect: Float?, areaPercent: Int): TabletArea {
        if (viewWidth <= 0f || viewHeight <= 0f) return TabletArea(0f, 0f, 0f, 0f)
        var w = viewWidth
        var h = viewHeight
        if (aspect != null && aspect > 0f) {
            if (viewWidth / viewHeight > aspect) w = viewHeight * aspect else h = viewWidth / aspect
        }
        val scale = areaPercent.coerceIn(50, 100) / 100f
        w *= scale
        h *= scale
        return TabletArea((viewWidth - w) / 2f, (viewHeight - h) / 2f, w, h)
    }

    /** Maps a view point into 0..[PenReport.MAX_COORD] on both axes, clamping to the area edges. */
    fun map(x: Float, y: Float, area: TabletArea): Pair<Int, Int> {
        if (area.width <= 0f || area.height <= 0f) return 0 to 0
        val nx = ((x - area.left) / area.width).coerceIn(0f, 1f)
        val ny = ((y - area.top) / area.height).coerceIn(0f, 1f)
        return (nx * PenReport.MAX_COORD).roundToInt() to (ny * PenReport.MAX_COORD).roundToInt()
    }

    /** Applies the pressure curve to a normalized Android pressure and scales to 0..4095. */
    fun pressure(normalized: Float, gamma: Float): Int {
        val p = normalized.coerceIn(0f, 1f)
        return (p.pow(gamma) * PenReport.MAX_PRESSURE).roundToInt().coerceIn(0, PenReport.MAX_PRESSURE)
    }

    /**
     * Converts Android `AXIS_TILT` (angle from vertical) and `AXIS_ORIENTATION` (0 = pen pointing to
     * the top of the screen, positive clockwise) into HID X/Y tilt degrees, clamped to ±60. Positive X
     * leans the pen top right; positive Y leans it toward the user.
     */
    fun tilt(tiltRad: Float, orientationRad: Float): Pair<Int, Int> {
        if (tiltRad <= 0f) return 0 to 0
        val t = tan(tiltRad.coerceAtMost(MAX_TILT_RAD))
        val x = Math.toDegrees(atan(t * sin(orientationRad)).toDouble())
        val y = Math.toDegrees(atan(-t * cos(orientationRad)).toDouble())
        val max = PenReport.MAX_TILT
        return x.roundToInt().coerceIn(-max, max) to y.roundToInt().coerceIn(-max, max)
    }
}
