package com.limelight.binding.input.capture

import android.app.Activity
import android.view.MotionEvent

open class InputCaptureProvider {
    @JvmField protected var isCapturing = false
    @JvmField protected var isCursorVisible = false

    open fun enableCapture() {
        isCapturing = true
        hideCursor()
    }

    open fun disableCapture() {
        isCapturing = false
        showCursor()
    }

    open fun destroy() {}

    open fun isCapturingEnabled(): Boolean = isCapturing

    open fun isCapturingActive(): Boolean = isCapturing

    open fun showCursor() {
        isCursorVisible = true
    }

    open fun hideCursor() {
        isCursorVisible = false
    }

    open fun eventHasRelativeMouseAxes(event: MotionEvent): Boolean = false

    open fun getRelativeAxisX(event: MotionEvent): Float = 0f

    open fun getRelativeAxisY(event: MotionEvent): Float = 0f

    open fun onWindowFocusChanged(focusActive: Boolean) {}
}
