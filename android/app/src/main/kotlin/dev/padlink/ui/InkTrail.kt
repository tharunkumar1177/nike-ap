// InkTrail.kt: low-latency local S Pen trail drawn with a front-buffered renderer and motion prediction.
package dev.padlink.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.input.motionprediction.MotionEventPredictor
import dev.padlink.input.StylusInput

/**
 * Draws where the pen tip has been so the user sees their stroke while watching the computer.
 * Points fade out over [TRAIL_MS]; a predicted tail hides touch latency.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InkTrail(private val surface: SurfaceView, inkColor: Int) : CanvasFrontBufferedRenderer.Callback<InkTrail.Frame> {

    /** One trail point in view pixels with normalized pressure and uptime timestamp. */
    data class Point(val x: Float, val y: Float, val pressure: Float, val t: Long)

    /** Immutable snapshot rendered on the renderer thread. */
    class Frame(val points: List<Point>, val predicted: List<Point>, val now: Long)

    private val density = surface.resources.displayMetrics.density
    private val renderer = CanvasFrontBufferedRenderer(surface, this)
    private val predictor = MotionEventPredictor.newInstance(surface)
    private val points = ArrayDeque<Point>()
    private var touching = false
    private var ticking = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val tick = Runnable { onTick() }

    /** Feeds a touch event; only stylus pointers are drawn. */
    fun onTouchEvent(e: MotionEvent) {
        val i = (0 until e.pointerCount).firstOrNull { StylusInput.isPen(e, it) } ?: return
        predictor.record(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (e.actionIndex == i) {
                touching = true
                add(Point(e.getX(i), e.getY(i), e.getPressure(i), e.eventTime))
            }
            MotionEvent.ACTION_MOVE -> if (touching) {
                for (h in 0 until e.historySize) {
                    add(Point(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h), e.getHistoricalEventTime(h)))
                }
                add(Point(e.getX(i), e.getY(i), e.getPressure(i), e.eventTime))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.actionIndex == i) touching = false
            }
            else -> return
        }
        render(if (touching) predicted() else emptyList())
        startTicking()
    }

    /** Stops rendering and releases the renderer. */
    fun release() {
        surface.removeCallbacks(tick)
        ticking = false
        renderer.release(true)
    }

    /** Appends a point unless it duplicates the previous position. */
    private fun add(p: Point) {
        val last = points.lastOrNull()
        if (last != null && last.x == p.x && last.y == p.y) return
        points.addLast(p)
    }

    /** Predicted continuation of the stroke, at most a few points. */
    private fun predicted(): List<Point> {
        val event = predictor.predict() ?: return emptyList()
        return try {
            val i = (0 until event.pointerCount).firstOrNull { StylusInput.isPen(event, it) } ?: 0
            val out = ArrayList<Point>(event.historySize + 1)
            for (h in 0 until event.historySize) {
                out += Point(event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalPressure(i, h), event.getHistoricalEventTime(h))
            }
            out += Point(event.getX(i), event.getY(i), event.getPressure(i), event.eventTime)
            out
        } finally {
            event.recycle()
        }
    }

    /** Drops expired points and renders the current trail. */
    private fun render(predicted: List<Point>) {
        val now = SystemClock.uptimeMillis()
        while (points.isNotEmpty() && now - points.first().t > TRAIL_MS) points.removeFirst()
        renderer.renderFrontBufferedLayer(Frame(points.toList(), predicted, now))
    }

    /** Keeps fading the trail every frame until it is empty. */
    private fun startTicking() {
        if (ticking) return
        ticking = true
        surface.postOnAnimation(tick)
    }

    /** One fade frame. */
    private fun onTick() {
        render(emptyList())
        if (points.isEmpty() && !touching) {
            ticking = false
            renderer.clear()
        } else {
            surface.postOnAnimation(tick)
        }
    }

    /** Draws the whole trail into the front buffer, oldest segments most transparent. */
    override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Frame) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawPolyline(canvas, param.points, param.now, 1f)
        val tail = param.points.lastOrNull()
        if (tail != null && param.predicted.isNotEmpty()) {
            drawPolyline(canvas, listOf(tail) + param.predicted, param.now, PREDICTED_ALPHA)
        }
    }

    /** The trail never commits to the multi-buffered layer, so it only needs clearing. */
    override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Frame>) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }

    /** Draws consecutive segments with pressure width and age-based alpha. */
    private fun drawPolyline(canvas: Canvas, pts: List<Point>, now: Long, alphaScale: Float) {
        for (k in 1 until pts.size) {
            val a = pts[k - 1]
            val b = pts[k]
            val age = (now - b.t).coerceIn(0L, TRAIL_MS)
            val alpha = (1f - age.toFloat() / TRAIL_MS) * alphaScale
            if (alpha <= 0f) continue
            paint.alpha = (alpha * 255).toInt()
            paint.strokeWidth = density * (MIN_WIDTH_DP + (MAX_WIDTH_DP - MIN_WIDTH_DP) * b.pressure.coerceIn(0f, 1f))
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }

    private companion object {
        const val TRAIL_MS = 700L
        const val PREDICTED_ALPHA = 0.5f
        const val MIN_WIDTH_DP = 1.5f
        const val MAX_WIDTH_DP = 6f
    }
}
