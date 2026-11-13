// OutboxQueueTest.kt: verifies merge and barrier rules of the shared outbox.
package dev.padlink.transport

import dev.padlink.input.MouseReport
import dev.padlink.input.PenReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboxQueueTest {

    /** Same-button mouse reports sum into one. */
    @Test
    fun mergesMouseWithSameButtons() {
        val q = OutboxQueue()
        q.offer(Outgoing.Mouse(MouseReport(0, dx = 3, dy = 1)))
        q.offer(Outgoing.Mouse(MouseReport(0, dx = 4, dy = -2, wheel = 120)))
        assertEquals(Outgoing.Mouse(MouseReport(0, dx = 7, dy = -1, wheel = 120)), q.poll())
        assertNull(q.poll())
    }

    /** A button change is a barrier. */
    @Test
    fun buttonChangeIsBarrier() {
        val q = OutboxQueue()
        q.offer(Outgoing.Mouse(MouseReport(0, dx = 3)))
        q.offer(Outgoing.Mouse(MouseReport(MouseReport.LEFT)))
        q.offer(Outgoing.Mouse(MouseReport(MouseReport.LEFT, dx = 2)))
        assertEquals(2, q.size)
        assertEquals(Outgoing.Mouse(MouseReport(0, dx = 3)), q.poll())
        assertEquals(Outgoing.Mouse(MouseReport(MouseReport.LEFT, dx = 2)), q.poll())
    }

    /** Pen samples with equal flags collapse to the newest; flag changes are kept. */
    @Test
    fun penCollapsesToNewest() {
        val q = OutboxQueue()
        val hover = PenReport.IN_RANGE
        val touch = PenReport.IN_RANGE or PenReport.TIP
        q.offer(Outgoing.Pen(PenReport(hover, 1, 1, 0)))
        q.offer(Outgoing.Pen(PenReport(hover, 2, 2, 0)))
        q.offer(Outgoing.Pen(PenReport(touch, 3, 3, 100)))
        q.offer(Outgoing.Pen(PenReport(touch, 4, 4, 200)))
        assertEquals(Outgoing.Pen(PenReport(hover, 2, 2, 0)), q.poll())
        assertEquals(Outgoing.Pen(PenReport(touch, 4, 4, 200)), q.poll())
        assertNull(q.poll())
    }

    /** Merging stops before a sum would overflow the wired i16 fields. */
    @Test
    fun mergeRespectsI16() {
        val q = OutboxQueue()
        q.offer(Outgoing.Mouse(MouseReport(0, dx = 30000)))
        q.offer(Outgoing.Mouse(MouseReport(0, dx = 5000)))
        assertEquals(2, q.size)
    }
}
