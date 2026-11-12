package com.limelight.binding.input.haptics

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Handler-equivalent delayed callbacks running on a shared vibration worker.
 *
 * The device stop chain (native wake, detail watchdog, lease refresh, touch completion) must
 * not wait behind the main loop, where game-side jank directly delays a vibration stop while
 * the backend waveform keeps playing. Posting onto the same single-thread executor that runs
 * [LatestWinsDispatcher] dispatch keeps every callback serialized with the writes it races.
 * Replacing a task cancels its previous run, matching every coordinator call site, which
 * always removes a callback before posting it again. Registration and a completed run's
 * deregistration share one lock, and schedule() is invoked under it: a task that starts
 * before post() returns blocks until it is registered, then drops its own entry — so no
 * completed run can retain one, with or without a racing replace/remove.
 */
internal class ExecutorDelayer(private val executor: ScheduledExecutorService) {
    private class Registration {
        @Volatile var future: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val registrations = HashMap<Runnable, Registration>()

    /** Pending entries; exposed for lifecycle assertions in tests. */
    val pendingCount: Int
        get() = synchronized(lock) { registrations.size }

    fun post(task: Runnable, delayMs: Long) {
        val entry = Registration()
        val run = Runnable {
            synchronized(lock) {
                // A replacement or remove() already owns the key; this completed run must
                // not drop their entry.
                if (registrations[task] === entry) registrations.remove(task)
            }
            task.run()
        }
        synchronized(lock) {
            entry.future = try {
                executor.schedule(run, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // The owning coordinator is closed; it already removed every pending callback.
                return
            }
            registrations[task]?.future?.cancel(false)
            registrations[task] = entry
        }
    }

    fun remove(task: Runnable) {
        synchronized(lock) {
            registrations.remove(task)?.future?.cancel(false)
        }
    }
}
