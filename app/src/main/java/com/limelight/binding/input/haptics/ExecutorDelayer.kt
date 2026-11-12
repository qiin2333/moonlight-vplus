package com.limelight.binding.input.haptics

import java.util.concurrent.ConcurrentHashMap
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
 * always removes a callback before posting it again.
 */
internal class ExecutorDelayer(private val executor: ScheduledExecutorService) {
    private val futures = ConcurrentHashMap<Runnable, ScheduledFuture<*>>()

    fun post(task: Runnable, delayMs: Long) {
        try {
            val future = executor.schedule(task, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            futures.put(task, future)?.cancel(false)
        } catch (_: RejectedExecutionException) {
            // The owning coordinator is closed; it already removed every pending callback.
        }
    }

    fun remove(task: Runnable) {
        futures.remove(task)?.cancel(false)
    }
}
