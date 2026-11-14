// Scheduler.kt: delayed-action abstraction so timing logic stays unit-testable.
package dev.padlink.input

import android.os.Handler
import android.os.Looper

/** Handle to a scheduled action. */
fun interface Cancellable {
    /** Prevents the action from running if it has not run yet. */
    fun cancel()
}

/** Runs actions after a delay on the input thread. */
fun interface Scheduler {
    /** Schedules [action] after [delayMs] milliseconds. */
    fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable
}

/** [Scheduler] backed by the main looper, where all touch input is processed. */
class MainThreadScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    /** Posts [action] on the main thread after [delayMs]. */
    override fun postDelayed(delayMs: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
