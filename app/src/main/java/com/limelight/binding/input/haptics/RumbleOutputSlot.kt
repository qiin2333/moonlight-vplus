package com.limelight.binding.input.haptics

/**
 * Paces a rumble sink while preserving zero <-> non-zero boundaries.
 *
 * Values submitted faster than the pacing interval merge latest-wins, except that a value which
 * would overwrite a not-yet-dispatched boundary (zero <-> non-zero) is flushed immediately,
 * rate-bounded by [edgeFloorMs]. Without the flush, a pulse shorter than the pacing interval -
 * on and off both arriving between dispatch ticks - would never reach the sink.
 *
 * Not synchronized: all calls must arrive on the thread that owns [postDelayed]'s queue.
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
        val previous = pending
        pending = value
        if (runnable == null) {
            scheduleNextDispatch()
            return
        }

        // A dispatch is already scheduled and would have taken `previous`. If that overwrite
        // crosses a zero boundary, the boundary must reach the sink now, not at the next tick.
        if (previous != null && isZero(previous) != isZero(value)) {
            val lastWrite = lastWriteMs
            if (lastWrite == null || clockMs() - lastWrite >= edgeFloorMs) {
                write(previous)
            }
        }
    }

    fun cancel() {
        runnable?.let(removeCallbacks)
        runnable = null
        pending = null
    }

    private fun scheduleNextDispatch() {
        val now = clockMs()
        val pacing = pacingMs()
        val delay = lastWriteMs
            ?.let { lastWrite -> (pacing - (now - lastWrite)).coerceAtLeast(0L) }
            ?: 0L
        val scheduled = Runnable {
            runnable = null
            val latest = pending ?: return@Runnable
            pending = null
            write(latest)
            if (pending != null && runnable == null) scheduleNextDispatch()
        }
        runnable = scheduled
        postDelayed(scheduled, delay)
    }

    private fun write(value: T) {
        if (value == lastWritten) return
        lastWritten = value
        lastWriteMs = clockMs()
        dispatch(value)
    }
}
