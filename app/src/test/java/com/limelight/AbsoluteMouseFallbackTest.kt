package com.limelight

import android.view.InputDevice
import android.view.MotionEvent
import com.limelight.preferences.ExternalMouseMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsoluteMouseFallbackTest {
    private val mouseSources = listOf(InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS,
        InputDevice.SOURCE_MOUSE_RELATIVE, InputDevice.SOURCE_TOUCHPAD)

    @Test
    fun captureRejectsAbsoluteMouseAndTouchpadReportsRegardlessOfFingerTool() {
        for (source in mouseSources) {
            for (tool in listOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_MOUSE, MotionEvent.TOOL_TYPE_UNKNOWN)) {
                assertFalse(allowsAbsoluteMouseFallback(ExternalMouseMode.RELATIVE_CAPTURE, source, tool))
                assertTrue(allowsAbsoluteMouseFallback(ExternalMouseMode.SYSTEM_POINTER, source, tool))
                assertTrue(allowsAbsoluteMouseFallback(ExternalMouseMode.OTHER_PRESET, source, tool))
            }
        }
    }

    @Test
    fun capturePreservesPenEraserAndNonMouseAbsoluteDevices() {
        for (source in mouseSources) {
            for (tool in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) {
                assertTrue(allowsAbsoluteMouseFallback(ExternalMouseMode.RELATIVE_CAPTURE, source, tool))
            }
        }
        for (source in listOf(InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_CLASS_POSITION)) {
            assertTrue(allowsAbsoluteMouseFallback(ExternalMouseMode.RELATIVE_CAPTURE, source, MotionEvent.TOOL_TYPE_FINGER))
        }
    }
}
