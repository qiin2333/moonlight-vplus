package com.limelight.binding.input.haptics

/**
 * Coalesces level changes while prioritizing start/stop boundaries. Starts obey [edgeFloorMs];
 * stops cancel pending levels immediately, even inside the floor. Expired pulses are not replayed.
 * All calls run on the thread that owns [postDelayed]'s queue.
 */
internal class RumbleOutputSlot<T : Any>(
    private val pacingMs: () -> Long,
    private val edgeFloorMs: Long,
    private val isZero: (T) -> Boolean,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val clockMs: () -> Long,
    private val dispatch: (T) -> Unit
) {
    private var pending: T? = null
    private var scheduledAtMs: Long? = null
    private var lastWriteMs: Long? = null
    private var lastWritten: T? = null
    private val runnable = Runnable {
        scheduledAtMs = null
        val latest = pending ?: return@Runnable
        pending = null
        write(latest)
    }

    fun submit(value: T) {
        if (value == lastWritten) {
            // The pending write would be dropped by write()'s dedupe anyway.
            cancel()
            return
        }
        pending = value
        // Compare with what the sink actually received, never flush an obsolete pending value
        // at its falling edge. Stops take priority; starts respect the edge floor.
        val boundary = lastWritten?.let { isZero(it) != isZero(value) } ?: true
        val interval = when {
            isZero(value) -> 0L
            boundary -> edgeFloorMs
            else -> pacingMs()
        }
        val now = clockMs()
        val delay = lastWriteMs?.let {
            (interval - (now - it)).coerceAtLeast(0L)
        } ?: 0L
        val dueAtMs = now + delay
        // Paced updates share one deadline; keep the queued callback instead of reposting it.
        if (scheduledAtMs == dueAtMs) return
        if (scheduledAtMs != null) removeCallbacks(runnable)
        scheduledAtMs = dueAtMs
        postDelayed(runnable, delay)
    }

    fun cancel() {
        if (scheduledAtMs != null) removeCallbacks(runnable)
        scheduledAtMs = null
        pending = null
    }

    /** Source ownership changes must observe the latest genuine ordinary value, even if paced. */
    fun flushPending() {
        if (scheduledAtMs != null) removeCallbacks(runnable)
        scheduledAtMs = null
        val latest = pending ?: return
        pending = null
        write(latest)
    }

    private fun write(value: T) {
        if (value == lastWritten) return
        lastWritten = value
        lastWriteMs = clockMs()
        dispatch(value)
    }
}
