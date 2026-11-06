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
 */
internal class LatestWinsDispatcher<T>(
    minimumIntervalMs: Long,
    private val executor: ScheduledExecutorService,
    private val clockNanos: () -> Long = System::nanoTime,
    private val dispatch: (T) -> Unit,
    private val onError: (Exception) -> Unit = {},
    private val isUrgent: (T) -> Boolean = { false },
    private val intervalForValueMs: ((T) -> Long)? = null,
    private val intervalFromDispatchStart: (T) -> Boolean = { false }
) {
    private val minimumIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minimumIntervalMs)
    private val lock = Any()

    private var pending: T? = null
    private var active = false
    private var closed = false
    private var lastDelivered: T? = null
    private var lastAttemptNanos: Long? = null
    private var scheduledAtNanos = 0L
    private var scheduledTask: ScheduledFuture<*>? = null
    private var scheduleId = 0L

    fun submit(value: T) {
        synchronized(lock) {
            if (closed || pending == value || (!active && lastDelivered == value)) return
            pending = value
            if (active && scheduledTask != null && (isUrgent(value) || scheduledAtNanos > clockNanos() &&
                intervalForValueMs?.invoke(value) == 0L)) {
                // A stop/ownership transition must not wait behind a paced waveform refresh.
                scheduledTask?.cancel(false)
                scheduleId++
                scheduledTask = null
                active = false
            }
            scheduleIfIdleLocked()
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
            if (active && finalValue?.let(isUrgent) == true && scheduledTask != null) {
                if (scheduledTask!!.cancel(false)) { scheduleId++; scheduledTask = null; active = false }
            }
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
        val interval = intervalForValueMs?.let { select ->
            TimeUnit.MILLISECONDS.toNanos(select(pending!!).coerceAtLeast(0L))
        } ?: minimumIntervalNanos
        val delayNanos = if (isUrgent(pending!!)) 0L else lastAttemptNanos?.let { previous ->
            (interval - (now - previous)).coerceAtLeast(0L)
        } ?: 0L
        active = true
        scheduledAtNanos = now + delayNanos
        try {
            val id = ++scheduleId
            scheduledTask = executor.schedule({ dispatchLatest(id) }, delayNanos, TimeUnit.NANOSECONDS)
        } catch (error: Exception) {
            active = false
            pending = null
            onError(error)
            shutdownIfDrainedLocked()
        }
    }

    private fun dispatchLatest(id: Long) {
        val (value, plannedAtNanos) = synchronized(lock) {
            if (id != scheduleId) return
            scheduledTask = null
            (pending to scheduledAtNanos).also { pending = null }
        }
        var delivered = false
        val startedAtNanos = clockNanos()
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
            val finishedAtNanos = clockNanos()
            val interval = value?.let { intervalForValueMs?.invoke(it) }
                ?.let(TimeUnit.MILLISECONDS::toNanos) ?: minimumIntervalNanos
            // Native short effects keep source cadence when Binder returns promptly. Slow or
            // blocked calls resume conservatively from completion, without catch-up bursts.
            lastAttemptNanos = if (value != null && intervalFromDispatchStart(value) &&
                finishedAtNanos - startedAtNanos < interval &&
                startedAtNanos - plannedAtNanos < interval) {
                // Recover small clock error without making two physical ticks nearly adjacent.
                maxOf(plannedAtNanos, startedAtNanos - TimeUnit.MILLISECONDS.toNanos(5))
            } else finishedAtNanos
            active = false
            scheduleIfIdleLocked()
        }
    }

    private fun shutdownIfDrainedLocked() {
        if (closed && !active && pending == null) executor.shutdown()
    }
}
