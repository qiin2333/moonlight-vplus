package com.limelight.binding.input.touchpad

import android.view.InputDevice
import android.view.MotionEvent

/** Let Android choose the local control or stream receiver, retaining the original stream frame. */
internal class CompatibilityTouchpadDispatch {
    private var original: MotionEvent? = null
    private var streamDown: MotionEvent? = null
    private var localButtonDevice = -1

    // Called before any device's DOWN, including devices outside compatibility mode.
    fun onTouchDown() { localButtonDevice = -1 }

    fun dispatch(event: MotionEvent, touch: Boolean,
                 position: CompatibilityTouchpadGesture.Action.Position? = null,
                 dispatchToViews: (MotionEvent) -> Boolean): Boolean {
        if (touch && event.actionMasked == MotionEvent.ACTION_DOWN) localButtonDevice = event.deviceId
        val previous = original
        val routed = if (position == null || (position.x == event.rawX && position.y == event.rawY)) {
            MotionEvent.obtain(event)
        } else {
            // offsetLocation alone leaves rawX/rawY unchanged. Local draggable
            // controls need the same logical screen coordinates as hit testing.
            val properties = Array(event.pointerCount) { MotionEvent.PointerProperties().also { p -> event.getPointerProperties(it, p) } }
            val coords = Array(event.pointerCount) { MotionEvent.PointerCoords().also { p ->
                event.getPointerCoords(it, p)
                p.x += position.x - event.x
                p.y += position.y - event.y
            } }
            MotionEvent.obtain(event.downTime, event.eventTime, event.action, event.pointerCount,
                properties, coords, event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
                event.deviceId, event.edgeFlags, event.source, event.flags).apply {
                offsetLocation(event.x - event.rawX, event.y - event.rawY)
            }
        }
        // OEM-rewritten TOUCHSCREEN frames still belong to one pointer device.
        // Android's mouse dispatch keeps all contacts on the initial target view.
        routed.source = InputDevice.SOURCE_MOUSE
        original = event
        return try {
            dispatchToViews(routed)
        } finally {
            if (touch && event.deviceId == localButtonDevice &&
                (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                localButtonDevice = -1
            }
            original = previous
            routed.recycle()
        }
    }

    fun handleStream(event: MotionEvent, handle: (MotionEvent) -> Boolean): Boolean {
        val frame = original ?: event
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // A new device's DOWN can cancel the old target. Cancel that target's
            // device, even when the new event bypasses compatibility dispatch.
            val target = streamDown?.takeIf {
                it.deviceId != frame.deviceId || frame.actionMasked == MotionEvent.ACTION_DOWN
            } ?: frame
            val cancel = MotionEvent.obtain(target)
            cancel.action = MotionEvent.ACTION_CANCEL
            finishStreamTouch()
            return try { handle(cancel) } finally { cancel.recycle() }
        }
        // ViewGroup synthesizes hover enter/exit around a MOVE; do not replay it.
        if (event.actionMasked != frame.actionMasked) return true
        if (frame.actionMasked == MotionEvent.ACTION_DOWN) {
            localButtonDevice = -1
            finishStreamTouch()
            if (original != null) streamDown = MotionEvent.obtain(frame)
        } else if (frame.actionMasked == MotionEvent.ACTION_UP) finishStreamTouch()
        // Generic BUTTON_PRESS/RELEASE may fall through a local control. Its touch
        // sequence already owns the click, so do not also press a host button.
        if (original != null && frame.deviceId == localButtonDevice && (frame.buttonState != 0 ||
                frame.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                frame.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE)) return true
        return handle(frame)
    }

    private fun finishStreamTouch() {
        streamDown?.recycle()
        streamDown = null
    }

    fun reset() {
        finishStreamTouch()
        localButtonDevice = -1
    }
}
