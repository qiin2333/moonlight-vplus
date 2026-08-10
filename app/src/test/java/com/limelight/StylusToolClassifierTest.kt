package com.limelight

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusToolClassifierTest {
    @Test
    fun stylusToolIsDetected() {
        assertTrue(containsStylus(MotionEvent.TOOL_TYPE_STYLUS))
    }

    @Test
    fun eraserToolIsDetected() {
        assertTrue(containsStylus(MotionEvent.TOOL_TYPE_ERASER))
    }

    @Test
    fun stylusIsDetectedWhenItIsNotTheFirstPointer() {
        assertTrue(containsStylus(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS))
    }

    @Test
    fun fingerOnlyEventIsNotStylusInput() {
        assertFalse(containsStylus(MotionEvent.TOOL_TYPE_FINGER))
    }

    @Test
    fun mouseOnlyEventIsNotStylusInput() {
        assertFalse(containsStylus(MotionEvent.TOOL_TYPE_MOUSE))
    }

    private fun containsStylus(vararg toolTypes: Int): Boolean =
        containsStylusTool(toolTypes.size) { toolTypes[it] }
}
