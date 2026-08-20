package com.limelight.binding.input.haptics

import java.util.concurrent.ScheduledExecutorService
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
    private val onError: (Exception) -> Unit = {}
) {
    private val minimumIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minimumIntervalMs)
    private val lock = Any()

    private var pending: T? = null
    private var active = false
    private var closed = false
    private var lastDelivered: T? = null
    private var lastAttemptNanos: Long? = null

    fun submit(value: T) {
        synchronized(lock) {
            if (closed || pending == value || (!active && lastDelivered == value)) return
            pending = value
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
        val delayNanos = lastAttemptNanos?.let { previous ->
            (minimumIntervalNanos - (now - previous)).coerceAtLeast(0L)
        } ?: 0L
        active = true
        try {
            executor.schedule(::dispatchLatest, delayNanos, TimeUnit.NANOSECONDS)
        } catch (error: Exception) {
            active = false
            pending = null
            onError(error)
            shutdownIfDrainedLocked()
        }
    }

    private fun dispatchLatest() {
        val value = synchronized(lock) {
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
