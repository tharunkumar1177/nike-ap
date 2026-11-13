// Outbox.kt: coalescing report queue and its sender thread ("Input delivery and release semantics").
package dev.padlink.transport

import java.util.ArrayDeque

/**
 * Pure queue that merges consecutive mouse reports with equal buttons and collapses consecutive
 * pen reports with equal flags. Button or flag changes are barriers and are never merged away.
 */
class OutboxQueue(private val capacity: Int = 256) {

    private val items = ArrayDeque<Outgoing>()

    /** Number of queued items. */
    val size: Int get() = items.size

    /** Adds an item, merging it into the tail when the contract allows. */
    fun offer(item: Outgoing) {
        val tail = items.peekLast()
        when {
            item is Outgoing.Mouse && tail is Outgoing.Mouse && mergeable(tail, item) -> {
                items.pollLast()
                items.addLast(Outgoing.Mouse(sum(tail, item)))
            }
            item is Outgoing.Pen && tail is Outgoing.Pen && tail.report.flags == item.report.flags -> {
                items.pollLast()
                items.addLast(item)
            }
            else -> {
                if (items.size >= capacity) dropOldestMergeable()
                items.addLast(item)
            }
        }
    }

    /** Removes and returns the head, or null when empty. */
    fun poll(): Outgoing? = items.pollFirst()

    /** Drops everything queued. */
    fun clear() = items.clear()

    /** True when two mouse reports carry the same buttons and their sum stays in i16 range. */
    private fun mergeable(a: Outgoing.Mouse, b: Outgoing.Mouse): Boolean {
        if (a.report.buttons != b.report.buttons) return false
        val s = sum(a, b).report
        return s.dx in I16 && s.dy in I16 && s.wheel in I16 && s.pan in I16
    }

    /** Field-wise sum of two mouse reports with the same buttons. */
    private fun sum(a: Outgoing.Mouse, b: Outgoing.Mouse): Outgoing.Mouse = Outgoing.Mouse(
        a.report.copy(
            dx = a.report.dx + b.report.dx,
            dy = a.report.dy + b.report.dy,
            wheel = a.report.wheel + b.report.wheel,
            pan = a.report.pan + b.report.pan,
        ),
    )

    /** Under overload, drops the oldest pen sample that is followed by one with the same flags. */
    private fun dropOldestMergeable() {
        val list = items.toMutableList()
        for (i in 0 until list.size - 1) {
            val a = list[i]
            val b = list[i + 1]
            if (a is Outgoing.Pen && b is Outgoing.Pen && a.report.flags == b.report.flags) {
                list.removeAt(i)
                items.clear()
                items.addAll(list)
                return
            }
        }
    }

    private companion object {
        val I16 = Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()
    }
}

/**
 * Single sender thread that drains an [OutboxQueue] into [deliver]. [deliver] may block (for
 * example until a BLE notification completes) and returns false when the item could not be sent.
 */
class Outbox(name: String, private val deliver: (Outgoing) -> Boolean) {

    private val lock = Object()
    private val queue = OutboxQueue()

    @Volatile
    private var running = true

    private val thread = Thread({ loop() }, "outbox-$name").apply {
        priority = Thread.MAX_PRIORITY
        isDaemon = true
        start()
    }

    /** Queues an item and wakes the sender. */
    fun offer(item: Outgoing) {
        synchronized(lock) {
            queue.offer(item)
            lock.notifyAll()
        }
    }

    /** Drops queued items (used when the link drops). */
    fun clear() {
        synchronized(lock) { queue.clear() }
    }

    /** Stops the sender thread after the current delivery. */
    fun close() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread.interrupt()
    }

    /** Sender loop: waits for items and delivers them in order. */
    private fun loop() {
        while (running) {
            val next = synchronized(lock) {
                while (running && queue.size == 0) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                queue.poll()
            } ?: continue
            try {
                deliver(next)
            } catch (_: InterruptedException) {
                return
            } catch (_: RuntimeException) {
                // A failed delivery must not kill the sender; link state reports the failure.
            }
        }
    }
}
