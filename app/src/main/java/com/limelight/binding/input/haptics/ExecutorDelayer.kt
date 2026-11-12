package com.limelight.binding.input.haptics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Handler-equivalent delayed callbacks running on a shared vibration worker.
 *
 * The device stop chain (native wake, detail watchdog, lease refresh, touch completion) must
 * not wait behind the main loop, where game-side jank directly delays a vibration stop while
 * the backend waveform keeps playing. Posting onto the same single-thread executor that runs
 * [LatestWinsDispatcher] dispatch keeps every callback serialized with the writes it races.
 * Replacing a task cancels its previous run, matching every coordinator call site, which
 * always removes a callback before posting it again. A completed run drops its own map entry
 * (only for the exact future that completed, so a replacement survives), because coordinator
 * callbacks clear their stored handle on completion without calling [remove].
 */
internal class ExecutorDelayer(private val executor: ScheduledExecutorService) {
    private val futures = ConcurrentHashMap<Runnable, ScheduledFuture<*>>()

    fun post(task: Runnable, delayMs: Long) {
        try {
            val scheduled = AtomicReference<ScheduledFuture<*>>()
            val future = executor.schedule(Runnable {
                futures.remove(task, scheduled.get())
                task.run()
            }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            scheduled.set(future)
            futures.put(task, future)?.cancel(false)
        } catch (_: RejectedExecutionException) {
            // The owning coordinator is closed; it already removed every pending callback.
        }
    }

    fun remove(task: Runnable) {
        futures.remove(task)?.cancel(false)
    }
}
