package com.limelight

/** Coordinates overlay clicks and long presses on the UI thread. */
internal class PerfOverlayGestureController(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onSingleTap: (Float, Float) -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onPressHighlight: () -> Unit,
    private val longPressTimeoutMs: Long,
) {
    private data class PendingTap(
        val time: Long,
        val rawX: Float,
        val rawY: Float,
        val x: Float,
        val y: Float,
        val singleEnabled: Boolean,
    )

    private var pendingTap: PendingTap? = null
    private var singleTask: Runnable? = null
    private var longPressTask: Runnable? = null
    private var highlightTask: Runnable? = null
    private var active = false
    private var moved = false
    private var longPressed = false
    private var secondTap = false
    private var downTime = 0L
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var singleEnabled = false
    private var doubleEnabled = false
    private var cancellationGeneration = 0

    fun down(
        time: Long, rawX: Float, rawY: Float, x: Float, y: Float, doubleTapSlop: Int,
        single: Boolean, double: Boolean, longPress: Boolean,
    ) {
        if (active) cancel()
        val previous = pendingTap
        val dx = rawX - (previous?.rawX ?: rawX)
        val dy = rawY - (previous?.rawY ?: rawY)
        // Only a nearby second down suspends the first single while long press is undecided.
        secondTap = double && previous != null && time >= previous.time && time - previous.time < DOUBLE_TAP_MS &&
            dx * dx + dy * dy <= doubleTapSlop.toFloat() * doubleTapSlop
        if (secondTap) {
            cancelSingleTask()
        } else if (previous != null) {
            val generation = cancellationGeneration
            finishPendingTap()
            if (generation != cancellationGeneration) return
        }
        active = true
        moved = false
        longPressed = false
        downTime = time
        downRawX = rawX
        downRawY = rawY
        downX = x
        downY = y
        singleEnabled = single
        doubleEnabled = double
        if (longPress) {
            val highlight = Runnable {
                if (active && !moved && !longPressed) onPressHighlight()
            }
            val longTask = Runnable {
                if (active && !moved && !longPressed) {
                    longPressed = true
                    clearPendingTap()
                    onLongPress()
                }
            }
            highlightTask = highlight
            longPressTask = longTask
            postDelayed(highlight, longPressTimeoutMs / 3)
            postDelayed(longTask, longPressTimeoutMs)
        }
    }

    fun moveBeyondSlop() {
        if (!active || moved || longPressed) return
        moved = true
        cancelPressTasks()
        clearPendingTap()
    }

    fun up(time: Long) {
        if (!active) return
        active = false
        cancelPressTasks()
        if (moved || longPressed || time < downTime || time - downTime >= MAX_TAP_MS) {
            clearPendingTap()
            return
        }
        if (secondTap) {
            clearPendingTap()
            onDoubleTap()
            return
        }
        if (doubleEnabled) {
            val tap = PendingTap(time, downRawX, downRawY, downX, downY, singleEnabled)
            pendingTap = tap
            val task = Runnable {
                if (pendingTap === tap && !secondTap) finishPendingTap()
            }
            singleTask = task
            postDelayed(task, DOUBLE_TAP_MS)
        } else if (singleEnabled) {
            onSingleTap(downX, downY)
        }
    }

    fun cancel() {
        cancellationGeneration++
        active = false
        moved = false
        longPressed = false
        secondTap = false
        cancelPressTasks()
        clearPendingTap()
    }

    private fun finishPendingTap() {
        val tap = pendingTap
        clearPendingTap()
        if (tap?.singleEnabled == true) onSingleTap(tap.x, tap.y)
    }

    private fun clearPendingTap() {
        cancelSingleTask()
        pendingTap = null
        secondTap = false
    }

    private fun cancelSingleTask() {
        singleTask?.let(removeCallbacks)
        singleTask = null
    }

    private fun cancelPressTasks() {
        longPressTask?.let(removeCallbacks)
        highlightTask?.let(removeCallbacks)
        longPressTask = null
        highlightTask = null
    }

    companion object {
        private const val DOUBLE_TAP_MS = 300L
        private const val MAX_TAP_MS = 500L
    }
}
