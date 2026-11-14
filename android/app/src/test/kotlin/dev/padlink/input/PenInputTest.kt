// PenInputTest.kt: verifies pen flags, pointer mode, tool routing, and palm-rejection timing.
package dev.padlink.input

import dev.padlink.settings.PenAspect
import dev.padlink.settings.PenMode
import dev.padlink.settings.PenSettings
import dev.padlink.settings.SideButtonAction
import dev.padlink.transport.HostSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PenInputTest {

    /** Scheduler that runs actions only when asked. */
    private class ManualScheduler : Scheduler {
        val pending = mutableListOf<() -> Unit>()

        /** Records the action. */
        override fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable {
            pending += action
            return Cancellable { pending.remove(action) }
        }

        /** Runs every pending action. */
        fun runAll() {
            val copy = pending.toList()
            pending.clear()
            copy.forEach { it() }
        }
    }

    /** Gesture sink that records clicks and moves. */
    private class RecordingSink : GestureSink {
        val events = mutableListOf<String>()

        /** Records a move. */
        override fun onMove(dxPx: Float, dyPx: Float, dtMs: Long) {
            events += "move"
        }

        /** Records a scroll. */
        override fun onScroll(dxPx: Float, dyPx: Float) {
            events += "scroll"
        }

        /** Records a click. */
        override fun onClick(button: Int) {
            events += "click$button"
        }

        /** Records a drag start. */
        override fun onDragStart(button: Int) {
            events += "dragStart"
        }

        /** Records a drag end. */
        override fun onDragEnd(button: Int) {
            events += "dragEnd"
        }
    }

    private lateinit var scheduler: ManualScheduler
    private lateinit var pens: MutableList<PenReport>
    private lateinit var mice: MutableList<MouseReport>
    private lateinit var pen: PenController
    private lateinit var sink: RecordingSink
    private lateinit var router: TouchRouter

    /** Builds a 1000x500 tablet in stretch mode with recording outputs. */
    @Before
    fun setUp() {
        scheduler = ManualScheduler()
        pens = mutableListOf()
        mice = mutableListOf()
        val mouse = MouseController(1f, scheduler, { mice += it })
        pen = PenController(mouse) { pens += it }
        pen.setSurfaceSize(1000, 500)
        pen.configure(PenSettings(aspect = PenAspect.STRETCH), null)
        sink = RecordingSink()
        router = TouchRouter(GestureEngine(sink, GestureConfig(slopPx = 8f)), pen, scheduler)
    }

    /** Hover reports in range without tip; contact adds tip and curved pressure. */
    @Test
    fun hoverAndContactFlags() {
        pen.sample(StylusSample(500f, 250f, 0f, touching = false))
        pen.sample(StylusSample(500f, 250f, 0.5f, touching = true))
        assertEquals(PenReport.IN_RANGE, pens[0].flags)
        assertEquals(0, pens[0].pressure)
        assertEquals(PenReport.IN_RANGE or PenReport.TIP, pens[1].flags)
        assertEquals(2048, pens[1].pressure)
        assertEquals(16384, pens[1].x)
    }

    /** The side button maps to barrel or to eraser per settings; the eraser tool always erases. */
    @Test
    fun sideButtonAndEraser() {
        pen.sample(StylusSample(0f, 0f, 1f, touching = true, sideButton = true))
        assertTrue(pens.last().flags and PenReport.BARREL != 0)
        pen.configure(PenSettings(aspect = PenAspect.STRETCH, sideButton = SideButtonAction.ERASER), null)
        pen.sample(StylusSample(0f, 0f, 1f, touching = true, sideButton = true))
        val erasing = PenReport.IN_RANGE or PenReport.TIP or PenReport.ERASER or PenReport.INVERT
        assertEquals(erasing, pens.last().flags)
        pen.sample(StylusSample(0f, 0f, 1f, touching = true, eraserTool = true))
        assertEquals(erasing, pens.last().flags)
    }

    /** Leaving range emits exactly one out-of-range report. */
    @Test
    fun leaveEmitsOutOfRangeOnce() {
        pen.sample(StylusSample(10f, 10f, 0f, touching = false))
        pen.leave()
        pen.leave()
        assertEquals(PenReport.OUT_OF_RANGE, pens.last())
        assertEquals(2, pens.size)
    }

    /** Automatic aspect follows the wired host size. */
    @Test
    fun autoAspectUsesHostSize() {
        pen.configure(PenSettings(aspect = PenAspect.AUTO), HostSize(1000, 1000))
        assertEquals(1f, pen.targetAspect()!!, 0.0001f)
        assertEquals(500f, pen.area.width, 0.01f)
        pen.configure(PenSettings(aspect = PenAspect.AUTO), null)
        assertEquals(16f / 9f, pen.targetAspect()!!, 0.0001f)
    }

    /** Pointer mode moves relatively and holds left while touching. */
    @Test
    fun pointerModeMovesAndHoldsLeft() {
        pen.configure(PenSettings(mode = PenMode.POINTER), null)
        pen.sample(StylusSample(100f, 100f, 0f, touching = false))
        pen.sample(StylusSample(110f, 100f, 0.5f, touching = true))
        pen.sample(StylusSample(110f, 100f, 0f, touching = false))
        assertTrue(pens.isEmpty())
        assertTrue(mice.any { it.dx > 0 })
        assertTrue(mice.any { it.buttons == MouseReport.LEFT })
        assertEquals(0, mice.last().buttons)
    }

    /** Fingers are ignored while the pen is in range and for the palm hold after it leaves. */
    @Test
    fun palmRejectionTiming() {
        router.penSample(StylusSample(10f, 10f, 0f, touching = false))
        router.fingerDown(1, 50f, 50f, 1_000)
        router.fingerUp(1, 1_050)
        assertTrue(sink.events.isEmpty())
        router.penMaybeLeft(1_100)
        assertTrue(router.penInRange)
        scheduler.runAll()
        assertFalse(router.penInRange)
        val leftAt = 1_100 + TouchRouter.LEAVE_GRACE_MS
        assertFalse(router.fingersAllowed(leftAt + TouchRouter.PALM_HOLD_MS - 1))
        assertTrue(router.fingersAllowed(leftAt + TouchRouter.PALM_HOLD_MS))
        router.fingerDown(2, 50f, 50f, leftAt + 400)
        router.fingerUp(2, leftAt + 450)
        assertEquals(listOf("click1"), sink.events)
    }

    /** A sample during the leave grace keeps the pen in range (hover-exit before contact). */
    @Test
    fun hoverExitBeforeContactDoesNotLift() {
        router.penSample(StylusSample(10f, 10f, 0f, touching = false))
        router.penMaybeLeft(100)
        router.penSample(StylusSample(10f, 10f, 0.4f, touching = true))
        scheduler.runAll()
        assertTrue(router.penInRange)
        assertFalse(pens.contains(PenReport.OUT_OF_RANGE))
    }

    /** The pen arriving cancels an in-progress finger drag. */
    @Test
    fun penArrivalCancelsFingerGesture() {
        router.fingerDown(1, 0f, 0f, 0)
        router.fingerUp(1, 50)
        router.fingerDown(1, 0f, 0f, 100)
        router.fingerMove(listOf(TouchPoint(1, 40f, 0f)), 120)
        assertTrue("dragStart" in sink.events)
        router.penSample(StylusSample(10f, 10f, 0f, touching = false))
        assertEquals("dragEnd", sink.events.last())
        router.fingerMove(listOf(TouchPoint(1, 80f, 0f)), 140)
        assertEquals("dragEnd", sink.events.last())
    }
}
