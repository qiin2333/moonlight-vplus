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
    private var runnable: Runnable? = null
    private var lastWriteMs: Long? = null
    private var lastWritten: T? = null

    fun submit(value: T) {
        pending = value
        // Compare with what the sink actually received, never flush an obsolete pending value
        // at its falling edge. Stops take priority; starts respect the edge floor.
        val boundary = lastWritten?.let { isZero(it) != isZero(value) } ?: true
        val interval = when {
            isZero(value) -> 0L
            boundary -> edgeFloorMs
            else -> pacingMs()
        }
        runnable?.let(removeCallbacks)
        val delay = lastWriteMs?.let {
            (interval - (clockMs() - it)).coerceAtLeast(0L)
        } ?: 0L
        val scheduled = Runnable {
            runnable = null
            val latest = pending ?: return@Runnable
            pending = null
            write(latest)
        }
        runnable = scheduled
        postDelayed(scheduled, delay)
    }

    fun cancel() {
        runnable?.let(removeCallbacks)
        runnable = null
        pending = null
    }

    private fun write(value: T) {
        if (value == lastWritten) return
        lastWritten = value
        lastWriteMs = clockMs()
        dispatch(value)
    }
}
