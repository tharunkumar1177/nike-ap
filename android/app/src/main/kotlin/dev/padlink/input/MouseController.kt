// MouseController.kt: turns gestures and on-screen buttons into MouseReports with held-button state.
package dev.padlink.input

import dev.padlink.settings.MouseSettings

/**
 * Owns the held-button mask and converts gestures into [MouseReport]s. Every emitted report carries
 * the full current button mask. Must be used from a single thread.
 */
class MouseController(
    density: Float,
    private val scheduler: Scheduler,
    private val output: (MouseReport) -> Unit,
    private val feedback: () -> Unit = {},
) : GestureSink {

    private val accel = PointerAccel(density)
    private val scroll = ScrollAccumulator(density)
    private var held = 0
    private var clickMask = 0
    private var pendingRelease: Cancellable? = null

    /** Applies speed, scroll, and invert settings. */
    fun applySettings(settings: MouseSettings) {
        accel.speed = settings.pointerSpeed
        scroll.speed = settings.scrollSpeed
        scroll.invert = settings.invertScroll
    }

    /** Current button mask (held buttons plus an in-progress click). */
    val buttons: Int get() = held or clickMask

    /** Converts pointer motion into counts and emits a move report. */
    override fun onMove(dxPx: Float, dyPx: Float, dtMs: Long) {
        val (dx, dy) = accel.convert(dxPx, dyPx, dtMs)
        if (dx != 0 || dy != 0) output(MouseReport(buttons, dx, dy))
    }

    /** Moves the pointer without acceleration (relative pen mode). */
    fun moveLinear(dxPx: Float, dyPx: Float) {
        val (dx, dy) = accel.convertLinear(dxPx, dyPx)
        if (dx != 0 || dy != 0) output(MouseReport(buttons, dx, dy))
    }

    /** Converts scroll motion into wheel and pan units and emits a scroll report. */
    override fun onScroll(dxPx: Float, dyPx: Float) {
        val (wheel, pan) = scroll.convert(dxPx, dyPx)
        if (wheel != 0 || pan != 0) output(MouseReport(buttons, wheel = wheel, pan = pan))
    }

    /** Presses [button], then releases it after [CLICK_HOLD_MS]; an earlier click is finished first. */
    override fun onClick(button: Int) {
        if (clickMask != 0) finishClick()
        clickMask = button
        output(MouseReport(buttons))
        feedback()
        pendingRelease = scheduler.postDelayed(CLICK_HOLD_MS) { finishClick() }
    }

    /** Holds [button] for a tap-and-drag. */
    override fun onDragStart(button: Int) = press(button)

    /** Releases [button] at the end of a tap-and-drag. */
    override fun onDragEnd(button: Int) = release(button)

    /** Holds [button] down (on-screen button press or drag). */
    fun press(button: Int) {
        if (held and button != 0) return
        held = held or button
        output(MouseReport(buttons))
        feedback()
    }

    /** Releases a held [button]. */
    fun release(button: Int) {
        if (held and button == 0) return
        held = held and button.inv()
        output(MouseReport(buttons))
    }

    /** Scrolls exactly [notches] wheel notches (positive scrolls content up), ignoring invert. */
    fun scrollNotches(notches: Int) {
        output(MouseReport(buttons, wheel = notches * MouseReport.NOTCH))
        feedback()
    }

    /** Releases every held button and any in-progress click. */
    fun releaseAll() {
        pendingRelease?.cancel()
        pendingRelease = null
        val hadButtons = buttons != 0
        held = 0
        clickMask = 0
        accel.reset()
        scroll.reset()
        if (hadButtons) output(MouseReport(0))
    }

    /** Ends the current click by clearing its bit. */
    private fun finishClick() {
        pendingRelease?.cancel()
        pendingRelease = null
        if (clickMask == 0) return
        clickMask = 0
        output(MouseReport(buttons))
    }

    companion object {
        /** Click press duration from the original app. */
        const val CLICK_HOLD_MS = 180L
    }
}
