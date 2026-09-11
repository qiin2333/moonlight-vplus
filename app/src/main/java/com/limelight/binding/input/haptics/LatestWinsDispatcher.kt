package com.limelight.binding.input.haptics

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Serializes a potentially blocking sink without allowing its work queue to grow.
 *
 * At most one task is scheduled or executing. While that task is in flight, newer values replace
 * the single pending value. This is important for vendor services that may block indefinitely:
 * callers remain responsive and memory use remains bounded even when the sink stops responding.
 *
 * The minimum interval spaces out reprogramming of long-running effects, which some vendor
 * vibrator services cannot tolerate at packet rate. Values reported by [isUrgent] skip that
 * spacing: they are short self-terminating one-shots, not reprogramming, and must not sit behind
 * the interval or their timing information is destroyed.
 */
internal class LatestWinsDispatcher<T>(
    minimumIntervalMs: Long,
    private val executor: ScheduledExecutorService,
    private val clockNanos: () -> Long = System::nanoTime,
    private val dispatch: (T) -> Unit,
    private val onError: (Exception) -> Unit = {},
    private val isUrgent: (T) -> Boolean = { false }
) {
    private val minimumIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minimumIntervalMs)
    private val lock = Any()

    private var pending: T? = null
    private var active = false
    private var closed = false
    private var lastDelivered: T? = null
    private var lastAttemptNanos: Long? = null
    private var scheduledFuture: ScheduledFuture<*>? = null

    fun submit(value: T) {
        synchronized(lock) {
            if (closed || pending == value || (!active && lastDelivered == value)) return
            pending = value
            if (active && isUrgent(value)) {
                promoteUrgentLocked()
            } else {
                scheduleIfIdleLocked()
            }
        }
    }

    /** Drops work that has not entered the native sink yet. An in-flight call is never awaited. */
    fun clearPending() {
        synchronized(lock) {
            pending = null
        }
    }

    /**
     * Rejects future submissions and optionally emits one final value before releasing the worker.
     */
    fun close(finalValue: T? = null) {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending = finalValue
            if (finalValue != null) lastDelivered = null
            scheduleIfIdleLocked()
            shutdownIfDrainedLocked()
        }
    }

    private fun scheduleIfIdleLocked() {
        if (active) return
        if (pending == lastDelivered) pending = null
        if (pending == null) {
            shutdownIfDrainedLocked()
            return
        }

        val now = clockNanos()
        val delayNanos = if (pending?.let(isUrgent) == true) {
            0L
        } else {
            lastAttemptNanos?.let { previous ->
                (minimumIntervalNanos - (now - previous)).coerceAtLeast(0L)
            } ?: 0L
        }
        active = true
        try {
            scheduledFuture = executor.schedule(::dispatchLatest, delayNanos, TimeUnit.NANOSECONDS)
        } catch (error: Exception) {
            active = false
            scheduledFuture = null
            pending = null
            onError(error)
            shutdownIfDrainedLocked()
        }
    }

    /**
     * An urgent value must not sit behind the pacing wait of an already-scheduled task:
     * cancel the parked task and redispatch at zero delay. A task that already started
     * executing cannot be cancelled - it picks up the urgent pending value itself.
     */
    private fun promoteUrgentLocked() {
        val future = scheduledFuture ?: return
        if (future.cancel(false)) {
            active = false
            scheduledFuture = null
            scheduleIfIdleLocked()
        }
    }

    private fun dispatchLatest() {
        val value = synchronized(lock) {
            scheduledFuture = null
            pending.also { pending = null }
        }
        var delivered = false
        if (value != null) {
            try {
                dispatch(value)
                delivered = true
            } catch (error: Exception) {
                onError(error)
            }
        }

        synchronized(lock) {
            if (delivered) lastDelivered = value
            lastAttemptNanos = clockNanos()
            active = false
            scheduleIfIdleLocked()
        }
    }

    private fun shutdownIfDrainedLocked() {
        if (closed && !active && pending == null) executor.shutdown()
    }
}
