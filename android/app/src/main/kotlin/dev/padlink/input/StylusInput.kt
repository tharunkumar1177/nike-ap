// StylusInput.kt: splits Android MotionEvents by tool type into finger gestures and S Pen samples.
package dev.padlink.input

import android.view.MotionEvent
import android.view.View

/**
 * Feeds a [TouchRouter] from a view's touch and hover events. Stylus streams request unbuffered
 * dispatch and forward every historical sample in order; fingers use historical batches too.
 */
class StylusInput(private val view: View, private val router: TouchRouter) {

    /** Handles a touch event; returns true when consumed. */
    fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (isPen(e, i)) {
                    view.requestUnbufferedDispatch(e)
                    router.penSample(sample(e, i, touching = true))
                } else if (isFinger(e, i)) {
                    router.fingerDown(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> dispatchMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                if (isPen(e, i)) {
                    router.penSample(sample(e, i, touching = false))
                    router.penMaybeLeft(e.eventTime)
                } else if (isFinger(e, i)) {
                    router.fingerUp(e.getPointerId(i), e.eventTime)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                router.cancelFingers()
                if ((0 until e.pointerCount).any { isPen(e, it) }) router.penCancelled(e.eventTime)
            }
            else -> return false
        }
        return true
    }

    /** Handles a hover event from the S Pen; returns true when consumed. */
    fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.pointerCount < 1 || !isPen(e, 0)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                for (h in 0 until e.historySize) router.penSample(historicalSample(e, 0, h, touching = false))
                router.penSample(sample(e, 0, touching = false))
            }
            MotionEvent.ACTION_HOVER_EXIT -> router.penMaybeLeft(e.eventTime)
            else -> return false
        }
        return true
    }

    /** Replays historical samples, then the current one, for every pointer of a move event. */
    private fun dispatchMove(e: MotionEvent) {
        val fingers = ArrayList<TouchPoint>(e.pointerCount)
        for (h in 0..e.historySize) {
            val current = h == e.historySize
            fingers.clear()
            for (i in 0 until e.pointerCount) {
                when {
                    isPen(e, i) -> router.penSample(
                        if (current) sample(e, i, touching = true) else historicalSample(e, i, h, touching = true),
                    )
                    isFinger(e, i) -> fingers += if (current) {
                        TouchPoint(e.getPointerId(i), e.getX(i), e.getY(i))
                    } else {
                        TouchPoint(e.getPointerId(i), e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    }
                }
            }
            if (fingers.isNotEmpty()) {
                router.fingerMove(fingers, if (current) e.eventTime else e.getHistoricalEventTime(h))
            }
        }
    }

    /** Current sample of pointer [i]. */
    private fun sample(e: MotionEvent, i: Int, touching: Boolean) = StylusSample(
        x = e.getX(i),
        y = e.getY(i),
        pressure = e.getPressure(i),
        tiltRad = e.getAxisValue(MotionEvent.AXIS_TILT, i),
        orientationRad = e.getOrientation(i),
        touching = touching,
        sideButton = sideButton(e),
        eraserTool = e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER,
    )

    /** Historical sample [h] of pointer [i]. */
    private fun historicalSample(e: MotionEvent, i: Int, h: Int, touching: Boolean) = StylusSample(
        x = e.getHistoricalX(i, h),
        y = e.getHistoricalY(i, h),
        pressure = e.getHistoricalPressure(i, h),
        tiltRad = e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h),
        orientationRad = e.getHistoricalOrientation(i, h),
        touching = touching,
        sideButton = sideButton(e),
        eraserTool = e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER,
    )

    /** True when the S Pen side button is held. */
    private fun sideButton(e: MotionEvent): Boolean =
        e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0

    companion object {
        /** True when pointer [i] is a stylus tip or eraser. */
        fun isPen(e: MotionEvent, i: Int): Boolean {
            val tool = e.getToolType(i)
            return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        }

        /** True when pointer [i] is a finger (unknown tools count as fingers). */
        fun isFinger(e: MotionEvent, i: Int): Boolean {
            val tool = e.getToolType(i)
            return tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_UNKNOWN
        }
    }
}
