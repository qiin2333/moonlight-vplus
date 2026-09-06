package com.limelight.binding.input.touch

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** Owns a touchscreen gesture from the third contact until its terminal event. */
internal class ThreeFingerPanZoomGesture(
    private val movementThreshold: Float,
    private val cancelHostTouches: () -> Unit,
    private val panZoom: (MotionEvent) -> Unit,
    private val toggleKeyboard: () -> Unit
) {
    private var active = false
    private var startTime = 0L
    private var keyboardTap = false
    private val pointerIds = IntArray(3)
    private val startX = FloatArray(3)
    private val startY = FloatArray(3)

    fun handle(event: MotionEvent, enabled: Boolean, keyboardFingers: Int): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancel()
        if (!active) {
            if (!enabled || event.actionMasked != MotionEvent.ACTION_POINTER_DOWN || event.pointerCount != 3) {
                return false
            }
            active = true
            startTime = event.eventTime
            keyboardTap = keyboardFingers == 3
            repeat(3) { index ->
                pointerIds[index] = event.getPointerId(index)
                startX[index] = event.getX(index)
                startY[index] = event.getY(index)
            }
            cancelHostTouches()
            beginPanZoom(event)
        }

        if (event.pointerCount > 3 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                event.flags and MotionEvent.FLAG_CANCELED != 0)
        ) {
            keyboardTap = false
        }
        if (keyboardTap) checkMovement(event)

        val finished = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        val shouldToggle = finished && event.actionMasked == MotionEvent.ACTION_UP && keyboardTap &&
            event.eventTime - startTime in 0 until KEYBOARD_TAP_TIMEOUT_MS
        if (finished) {
            active = false
            keyboardTap = false
        }
        panZoom(event)
        if (shouldToggle) toggleKeyboard()
        return true
    }

    fun cancel() {
        if (!active) return
        active = false
        keyboardTap = false
        val time = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        cancel.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            panZoom(cancel)
        } finally {
            cancel.recycle()
        }
    }

    private fun beginPanZoom(event: MotionEvent) {
        // The detectors did not see the first two contacts. Seed their DOWN sequence locally.
        val indices = (0 until event.pointerCount).filter { it != event.actionIndex }
        val properties = Array(indices.size) { index ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(indices[index], it) }
        }
        val coords = Array(indices.size) { index ->
            MotionEvent.PointerCoords().also { event.getPointerCoords(indices[index], it) }
        }
        for (count in 1..indices.size) {
            val action = if (count == 1) MotionEvent.ACTION_DOWN else {
                MotionEvent.ACTION_POINTER_DOWN or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            val initial = MotionEvent.obtain(
                event.downTime, event.eventTime, action, count, properties, coords,
                event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
                event.deviceId, event.edgeFlags, event.source, event.flags
            )
            try {
                panZoom(initial)
            } finally {
                initial.recycle()
            }
        }
    }

    private fun checkMovement(event: MotionEvent) {
        for (index in 0 until event.pointerCount) {
            val original = pointerIds.indexOf(event.getPointerId(index))
            if (original < 0) {
                keyboardTap = false
                return
            }
            // Include batched samples and lift coordinates, not only ACTION_MOVE endpoints.
            for (sample in 0..event.historySize) {
                val x = if (sample == event.historySize) event.getX(index) else event.getHistoricalX(index, sample)
                val y = if (sample == event.historySize) event.getY(index) else event.getHistoricalY(index, sample)
                val dx = x - startX[original]
                val dy = y - startY[original]
                if (dx * dx + dy * dy > movementThreshold * movementThreshold) {
                    keyboardTap = false
                    return
                }
            }
        }
    }

    companion object {
        private const val KEYBOARD_TAP_TIMEOUT_MS = 300L
    }
}
