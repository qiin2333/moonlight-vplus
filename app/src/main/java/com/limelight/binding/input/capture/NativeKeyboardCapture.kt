package com.limelight.binding.input.capture

import android.os.Build
import android.view.Window

/** Window-local capture. Returns false so callers can retain their legacy fallback. */
object NativeKeyboardCapture {
    fun apply(window: Window, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
        ) {
            val attributes = window.attributes
            if (attributes.hasKeyboardCapture() != enabled) {
                attributes.setKeyboardCaptureEnabled(enabled)
                window.attributes = attributes
            }
            return true
        }
        return false
    }
}
