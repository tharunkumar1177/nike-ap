// TouchRouter.kt: splits finger and stylus input, with palm rejection while the pen is near.
package dev.padlink.input

/**
 * Routes fingers to the [GestureEngine] and stylus samples to the [PenController]. Fingers are
 * ignored while the pen is in range and for [palmHoldMs] after it leaves; a finger gesture in
 * progress is cancelled when the pen arrives. Must be used from a single thread.
 */
class TouchRouter(
    private val gestures: GestureEngine,
    private val pen: PenController,
    private val scheduler: Scheduler,
    private val palmHoldMs: Long = PALM_HOLD_MS,
    private val leaveGraceMs: Long = LEAVE_GRACE_MS,
) {
    private val tracked = HashSet<Int>()
    private val ignored = HashSet<Int>()
    private var penPresent = false
    private var penLeftAt = Long.MIN_VALUE / 2
    private var pendingLeave: Cancellable? = null

    /** Called whenever the pen enters or leaves range, for UI such as the ink trail. */
    var onPenPresence: (Boolean) -> Unit = {}

    /** True while the pen hovers or touches. */
    val penInRange: Boolean get() = penPresent

    /** True when fingers may drive the trackpad at [t] ms. */
    fun fingersAllowed(t: Long): Boolean = !penPresent && t - penLeftAt >= palmHoldMs

    /** A finger touched down. */
    fun fingerDown(id: Int, x: Float, y: Float, t: Long) {
        if (!fingersAllowed(t)) {
            ignored += id
            return
        }
        tracked += id
        gestures.down(id, x, y, t)
    }

    /** Fingers moved; untracked fingers are dropped. */
    fun fingerMove(points: List<TouchPoint>, t: Long) {
        if (tracked.isEmpty()) return
        gestures.move(points.filter { it.id in tracked }, t)
    }

    /** A finger lifted. */
    fun fingerUp(id: Int, t: Long) {
        ignored -= id
        if (tracked.remove(id)) gestures.up(id, t)
    }

    /** The system cancelled the touch stream. */
    fun cancelFingers() {
        if (tracked.isNotEmpty()) gestures.cancel()
        tracked.clear()
        ignored.clear()
    }

    /** A stylus hover or contact sample. */
    fun penSample(sample: StylusSample) {
        pendingLeave?.cancel()
        pendingLeave = null
        if (!penPresent) {
            penPresent = true
            if (tracked.isNotEmpty()) {
                gestures.cancel()
                ignored += tracked
                tracked.clear()
            }
            onPenPresence(true)
        }
        pen.sample(sample)
    }

    /**
     * The pen stopped hovering or lifted. Android sends hover-exit right before contact and contact
     * end right before hover, so leaving is confirmed only if no sample follows within the grace time.
     */
    fun penMaybeLeft(t: Long) {
        if (!penPresent) return
        pendingLeave?.cancel()
        pendingLeave = scheduler.postDelayed(leaveGraceMs) {
            pendingLeave = null
            penLeft(t + leaveGraceMs)
        }
    }

    /** The pen stream was cancelled; leave immediately. */
    fun penCancelled(t: Long) {
        pendingLeave?.cancel()
        pendingLeave = null
        penLeft(t)
    }

    /** Lifts the pen and starts the palm-rejection hold. */
    private fun penLeft(t: Long) {
        if (!penPresent) return
        penPresent = false
        penLeftAt = t
        pen.leave()
        onPenPresence(false)
    }

    /** Drops all state, lifting the pen and releasing finger gestures. */
    fun reset() {
        cancelFingers()
        pendingLeave?.cancel()
        pendingLeave = null
        if (penPresent) {
            penPresent = false
            pen.leave()
            onPenPresence(false)
        }
    }

    companion object {
        /** Finger lockout after the pen leaves. */
        const val PALM_HOLD_MS = 300L

        /** Time a hover-exit or lift waits for the next sample before the pen counts as gone. */
        const val LEAVE_GRACE_MS = 80L
    }
}
