// GestureEngine.kt: platform-free trackpad gesture recognizer (taps, drag lock, pointer moves, two-finger scroll).
package dev.padlink.input

import kotlin.math.hypot

/** Position of one finger in view pixels. */
data class TouchPoint(val id: Int, val x: Float, val y: Float)

/** Receives recognized gestures. Deltas are in view pixels. */
interface GestureSink {
    /** Pointer motion over [dtMs]. */
    fun onMove(dxPx: Float, dyPx: Float, dtMs: Long)

    /** Two-finger scroll motion of the finger centroid. */
    fun onScroll(dxPx: Float, dyPx: Float)

    /** A complete click of [button] (a [MouseReport] button bit). */
    fun onClick(button: Int)

    /** [button] goes down and stays held for a drag. */
    fun onDragStart(button: Int)

    /** [button] held by [onDragStart] is released. */
    fun onDragEnd(button: Int)
}

/** Timing and distance thresholds. */
data class GestureConfig(
    val slopPx: Float,
    val tapTimeoutMs: Long = 200,
    val doubleTapWindowMs: Long = 300,
    val multiTapTimeoutMs: Long = 250,
    val tapClicks: Boolean = true,
)

/** Recognizes trackpad gestures from finger down/move/up events with injected timestamps. */
class GestureEngine(private val sink: GestureSink, var config: GestureConfig) {

    private enum class Mode { NONE, POINTER, SCROLL, DRAG }

    private class Finger(val startX: Float, val startY: Float, var x: Float, var y: Float)

    private val fingers = LinkedHashMap<Int, Finger>()
    private var mode = Mode.NONE
    private var moved = false
    private var maxFingers = 0
    private var gestureStart = 0L
    private var lastEventTime = 0L
    private var lastTapUp = Long.MIN_VALUE / 2
    private var dragLockArmed = false
    private var anchorX = 0f
    private var anchorY = 0f

    /** True while any finger is down. */
    val active: Boolean get() = fingers.isNotEmpty()

    /** A finger touched down at ([x], [y]) at time [t] ms. */
    fun down(id: Int, x: Float, y: Float, t: Long) {
        if (fingers.isEmpty()) {
            gestureStart = t
            moved = false
            maxFingers = 0
            mode = Mode.NONE
            dragLockArmed = config.tapClicks && t - lastTapUp <= config.doubleTapWindowMs
        }
        fingers[id] = Finger(x, y, x, y)
        maxFingers = maxOf(maxFingers, fingers.size)
        if (fingers.size >= 2 && mode == Mode.POINTER) mode = Mode.SCROLL
        lastEventTime = t
        reanchor()
    }

    /** Fingers moved; [points] holds the current position of every finger that is down. */
    fun move(points: List<TouchPoint>, t: Long) {
        for (p in points) {
            val f = fingers[p.id] ?: continue
            f.x = p.x
            f.y = p.y
        }
        if (fingers.isEmpty()) return
        val dt = t - lastEventTime
        lastEventTime = t
        if (!moved) {
            val beyond = fingers.values.any { hypot(it.x - it.startX, it.y - it.startY) > config.slopPx }
            if (!beyond) return
            moved = true
            mode = when {
                fingers.size >= 2 -> Mode.SCROLL
                dragLockArmed -> {
                    sink.onDragStart(MouseReport.LEFT)
                    Mode.DRAG
                }
                else -> Mode.POINTER
            }
            reanchor()
            return
        }
        val (cx, cy) = anchorPoint()
        val dx = cx - anchorX
        val dy = cy - anchorY
        anchorX = cx
        anchorY = cy
        if (dx == 0f && dy == 0f) return
        when (mode) {
            Mode.POINTER, Mode.DRAG -> sink.onMove(dx, dy, dt)
            Mode.SCROLL -> sink.onScroll(dx, dy)
            Mode.NONE -> Unit
        }
    }

    /** A finger lifted at time [t] ms. */
    fun up(id: Int, t: Long) {
        if (fingers.remove(id) == null) return
        lastEventTime = t
        if (fingers.isNotEmpty()) {
            reanchor()
            return
        }
        if (mode == Mode.DRAG) sink.onDragEnd(MouseReport.LEFT)
        val duration = t - gestureStart
        var tapped = false
        if (!moved && config.tapClicks) {
            when {
                maxFingers == 1 && duration <= config.tapTimeoutMs -> {
                    sink.onClick(MouseReport.LEFT)
                    tapped = true
                }
                maxFingers == 2 && duration <= config.multiTapTimeoutMs -> sink.onClick(MouseReport.RIGHT)
                maxFingers >= 3 && duration <= config.multiTapTimeoutMs -> sink.onClick(MouseReport.MIDDLE)
            }
        }
        lastTapUp = if (tapped) t else Long.MIN_VALUE / 2
        mode = Mode.NONE
        dragLockArmed = false
    }

    /** Aborts the gesture (for example when the pen arrives); a held drag is released. */
    fun cancel() {
        if (mode == Mode.DRAG) sink.onDragEnd(MouseReport.LEFT)
        fingers.clear()
        mode = Mode.NONE
        moved = false
        dragLockArmed = false
        lastTapUp = Long.MIN_VALUE / 2
    }

    /** Resets the motion anchor so finger-count changes never produce a jump. */
    private fun reanchor() {
        val (x, y) = anchorPoint()
        anchorX = x
        anchorY = y
    }

    /** Primary finger for pointer and drag modes; centroid for scroll. */
    private fun anchorPoint(): Pair<Float, Float> {
        if (fingers.isEmpty()) return 0f to 0f
        if (mode == Mode.SCROLL) {
            val n = fingers.size
            return fingers.values.sumOf { it.x.toDouble() }.toFloat() / n to
                fingers.values.sumOf { it.y.toDouble() }.toFloat() / n
        }
        val f = fingers.values.first()
        return f.x to f.y
    }
}
