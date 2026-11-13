// GestureEngineTest.kt: verifies tap, drag-lock, multi-finger tap, pointer, and scroll recognition.
package dev.padlink.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureEngineTest {

    private class Recorder : GestureSink {
        val events = mutableListOf<String>()
        var moveX = 0f
        var moveY = 0f
        var scrollX = 0f
        var scrollY = 0f

        /** Records accumulated pointer motion. */
        override fun onMove(dxPx: Float, dyPx: Float, dtMs: Long) {
            moveX += dxPx
            moveY += dyPx
            if (events.lastOrNull() != "move") events += "move"
        }

        /** Records accumulated scroll motion. */
        override fun onScroll(dxPx: Float, dyPx: Float) {
            scrollX += dxPx
            scrollY += dyPx
            if (events.lastOrNull() != "scroll") events += "scroll"
        }

        /** Records a click. */
        override fun onClick(button: Int) {
            events += "click$button"
        }

        /** Records a drag start. */
        override fun onDragStart(button: Int) {
            events += "dragStart$button"
        }

        /** Records a drag end. */
        override fun onDragEnd(button: Int) {
            events += "dragEnd$button"
        }
    }

    private lateinit var sink: Recorder
    private lateinit var engine: GestureEngine

    /** Builds a fresh engine with an 8 px slop. */
    @Before
    fun setUp() {
        sink = Recorder()
        engine = GestureEngine(sink, GestureConfig(slopPx = 8f))
    }

    /** A quick single-finger tap clicks left. */
    @Test
    fun tapClicksLeft() {
        engine.down(0, 100f, 100f, 0)
        engine.move(listOf(TouchPoint(0, 103f, 102f)), 40)
        engine.up(0, 90)
        assertEquals(listOf("click${MouseReport.LEFT}"), sink.events)
    }

    /** A press longer than the tap timeout does not click. */
    @Test
    fun slowPressDoesNotClick() {
        engine.down(0, 100f, 100f, 0)
        engine.up(0, 400)
        assertTrue(sink.events.isEmpty())
    }

    /** With tap clicks disabled nothing clicks. */
    @Test
    fun tapClicksCanBeDisabled() {
        engine.config = engine.config.copy(tapClicks = false)
        engine.down(0, 100f, 100f, 0)
        engine.up(0, 50)
        assertTrue(sink.events.isEmpty())
    }

    /** Motion beyond slop moves the pointer by the post-slop delta and suppresses the click. */
    @Test
    fun moveBeyondSlopMovesPointer() {
        engine.down(0, 100f, 100f, 0)
        engine.move(listOf(TouchPoint(0, 120f, 100f)), 16)
        engine.move(listOf(TouchPoint(0, 150f, 110f)), 32)
        engine.up(0, 48)
        assertEquals(listOf("move"), sink.events)
        assertEquals(30f, sink.moveX, 0.001f)
        assertEquals(10f, sink.moveY, 0.001f)
    }

    /** Two fingers tapped together click right. */
    @Test
    fun twoFingerTapClicksRight() {
        engine.down(0, 100f, 100f, 0)
        engine.down(1, 160f, 100f, 20)
        engine.up(0, 120)
        engine.up(1, 130)
        assertEquals(listOf("click${MouseReport.RIGHT}"), sink.events)
    }

    /** Three fingers tapped together click middle. */
    @Test
    fun threeFingerTapClicksMiddle() {
        engine.down(0, 100f, 100f, 0)
        engine.down(1, 160f, 100f, 10)
        engine.down(2, 220f, 100f, 20)
        engine.up(0, 100)
        engine.up(1, 110)
        engine.up(2, 120)
        assertEquals(listOf("click${MouseReport.MIDDLE}"), sink.events)
    }

    /** Two-finger drag scrolls by the centroid delta. */
    @Test
    fun twoFingerDragScrolls() {
        engine.down(0, 100f, 100f, 0)
        engine.down(1, 200f, 100f, 5)
        engine.move(listOf(TouchPoint(0, 100f, 120f), TouchPoint(1, 200f, 120f)), 20)
        engine.move(listOf(TouchPoint(0, 100f, 160f), TouchPoint(1, 200f, 160f)), 40)
        engine.up(0, 60)
        engine.up(1, 70)
        assertEquals(listOf("scroll"), sink.events)
        assertEquals(40f, sink.scrollY, 0.001f)
        assertEquals(0f, sink.scrollX, 0.001f)
    }

    /** Tap, then touch again and move: left is held for the drag and released on lift. */
    @Test
    fun tapThenDragHoldsLeft() {
        engine.down(0, 100f, 100f, 0)
        engine.up(0, 60)
        engine.down(0, 100f, 100f, 200)
        engine.move(listOf(TouchPoint(0, 130f, 100f)), 220)
        engine.move(listOf(TouchPoint(0, 160f, 100f)), 240)
        engine.up(0, 300)
        assertEquals(
            listOf("click${MouseReport.LEFT}", "dragStart${MouseReport.LEFT}", "move", "dragEnd${MouseReport.LEFT}"),
            sink.events,
        )
        assertEquals(30f, sink.moveX, 0.001f)
    }

    /** Two quick taps produce two clicks (a double-click). */
    @Test
    fun doubleTapClicksTwice() {
        engine.down(0, 100f, 100f, 0)
        engine.up(0, 60)
        engine.down(0, 100f, 100f, 150)
        engine.up(0, 210)
        assertEquals(listOf("click${MouseReport.LEFT}", "click${MouseReport.LEFT}"), sink.events)
    }

    /** Cancelling during a drag releases the held button. */
    @Test
    fun cancelReleasesDrag() {
        engine.down(0, 100f, 100f, 0)
        engine.up(0, 60)
        engine.down(0, 100f, 100f, 200)
        engine.move(listOf(TouchPoint(0, 130f, 100f)), 220)
        engine.cancel()
        assertEquals(listOf("click${MouseReport.LEFT}", "dragStart${MouseReport.LEFT}", "dragEnd${MouseReport.LEFT}"), sink.events)
    }
}
