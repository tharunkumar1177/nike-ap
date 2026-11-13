// TrackpadView.kt: transparent touch surface for fingers and the S Pen, hosting the local ink trail.
package dev.padlink.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.padlink.input.StylusInput

/**
 * SurfaceView above the window (translucent) so the ink trail renders with front-buffer latency.
 * Background art is drawn by Compose underneath. Excludes its side edges from system back gestures
 * and asks the display for its highest refresh rate while visible.
 */
@SuppressLint("ViewConstructor")
class TrackpadView(context: Context, inkColor: Int) : SurfaceView(context), SurfaceHolder.Callback {

    /** Receives touch and hover events; set by [PadSession.attach]. */
    var input: StylusInput? = null

    /** Called with the view size in pixels whenever it changes. */
    var sizeListener: (Int, Int) -> Unit = { _, _ -> }

    /** When true, the full view is excluded from system gestures (pen canvas). */
    var excludeAllGestures: Boolean = false
        set(value) {
            field = value
            updateExclusion()
        }

    private val ink: InkTrail?
    private val edgePx = (EDGE_EXCLUSION_DP * resources.displayMetrics.density).toInt()

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isFocusable = true
        ink = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) InkTrail(this, inkColor) else null
    }

    /** Routes touch events to the input layer and the ink trail. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ink?.onTouchEvent(event)
        return input?.onTouchEvent(event) ?: false
    }

    /** Routes S Pen hover events to the input layer. */
    override fun onHoverEvent(event: MotionEvent): Boolean =
        input?.onHoverEvent(event) ?: super.onHoverEvent(event)

    /** Reports the new size and refreshes gesture exclusion. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeListener(w, h)
        updateExclusion()
    }

    /** Requests the panel's highest refresh rate for this surface. */
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val rate = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return
            holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    /** No-op: the ink trail sizes its buffers itself. */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    /** No-op: rendering stops with the surface. */
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    /** Releases the ink renderer. */
    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ink?.release()
        super.onDetachedFromWindow()
    }

    /** Excludes the side edges (or everything) from back gestures so strokes are not interrupted. */
    private fun updateExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || width == 0) return
        systemGestureExclusionRects = if (excludeAllGestures) {
            listOf(Rect(0, 0, width, height))
        } else {
            listOf(Rect(0, 0, edgePx, height), Rect(width - edgePx, 0, width, height))
        }
    }

    private companion object {
        const val EDGE_EXCLUSION_DP = 32f
    }
}
