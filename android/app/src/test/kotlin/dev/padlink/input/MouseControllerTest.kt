// MouseControllerTest.kt: verifies click timing, overlapping clicks, held buttons, and release-all.
package dev.padlink.input

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MouseControllerTest {

    private class ManualScheduler : Scheduler {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()

        /** Records the action instead of running it. */
        override fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable {
            val entry = delayMs to action
            pending += entry
            return Cancellable { pending.remove(entry) }
        }

        /** Runs every pending action. */
        fun runAll() {
            val copy = pending.toList()
            pending.clear()
            copy.forEach { it.second() }
        }
    }

    private lateinit var scheduler: ManualScheduler
    private lateinit var reports: MutableList<MouseReport>
    private lateinit var controller: MouseController

    /** Builds a controller that records its output. */
    @Before
    fun setUp() {
        scheduler = ManualScheduler()
        reports = mutableListOf()
        controller = MouseController(1f, scheduler, { reports += it })
    }

    /** A click presses immediately and releases after the hold delay. */
    @Test
    fun clickPressesThenReleases() {
        controller.onClick(MouseReport.LEFT)
        assertEquals(listOf(MouseReport(MouseReport.LEFT)), reports)
        assertEquals(MouseController.CLICK_HOLD_MS, scheduler.pending.single().first)
        scheduler.runAll()
        assertEquals(listOf(MouseReport(MouseReport.LEFT), MouseReport(0)), reports)
    }

    /** A second click during the hold releases the first before pressing again. */
    @Test
    fun overlappingClickReleasesFirst() {
        controller.onClick(MouseReport.LEFT)
        controller.onClick(MouseReport.LEFT)
        assertEquals(
            listOf(MouseReport(MouseReport.LEFT), MouseReport(0), MouseReport(MouseReport.LEFT)),
            reports,
        )
        assertEquals(1, scheduler.pending.size)
    }

    /** Moves carry the held button mask during a drag. */
    @Test
    fun heldButtonRidesOnMoves() {
        controller.press(MouseReport.LEFT)
        controller.onMove(10f, 0f, 1000)
        controller.release(MouseReport.LEFT)
        assertEquals(MouseReport(MouseReport.LEFT), reports[0])
        assertEquals(MouseReport.LEFT, reports[1].buttons)
        assertEquals(15, reports[1].dx)
        assertEquals(MouseReport(0), reports[2])
    }

    /** Arrow notches ignore invert and use whole notch units. */
    @Test
    fun scrollNotches() {
        controller.scrollNotches(-1)
        assertEquals(MouseReport(0, wheel = -MouseReport.NOTCH), reports.single())
    }

    /** Release-all clears held buttons and pending clicks with one report. */
    @Test
    fun releaseAllClearsEverything() {
        controller.press(MouseReport.RIGHT)
        controller.onClick(MouseReport.LEFT)
        reports.clear()
        controller.releaseAll()
        assertEquals(listOf(MouseReport(0)), reports)
        assertEquals(0, scheduler.pending.size)
    }
}
