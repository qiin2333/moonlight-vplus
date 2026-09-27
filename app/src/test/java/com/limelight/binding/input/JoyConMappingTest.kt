package com.limelight.binding.input

import android.view.KeyEvent
import android.view.MotionEvent.*
import org.junit.Assert.*
import org.junit.Test

class JoyConMappingTest {
    @Test fun unmappedLeftDpadUsesLinuxScanCodes() {
        val directions = mapOf(
            0x220 to KeyEvent.KEYCODE_DPAD_UP,
            0x221 to KeyEvent.KEYCODE_DPAD_DOWN,
            0x222 to KeyEvent.KEYCODE_DPAD_LEFT,
            0x223 to KeyEvent.KEYCODE_DPAD_RIGHT
        )
        directions.forEach { (scanCode, expected) ->
            assertEquals(expected, JoyConMapping.key(JoyConSide.LEFT, KeyEvent.KEYCODE_UNKNOWN, scanCode))
        }
    }

    @Test fun scanCodeFallbackPreservesExplicitSystemMappings() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_A,
            JoyConMapping.key(JoyConSide.LEFT, KeyEvent.KEYCODE_BUTTON_A, 0x220))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT,
            JoyConMapping.key(JoyConSide.LEFT, KeyEvent.KEYCODE_DPAD_LEFT, 0x222))
    }

    @Test fun scanCodeFallbackDoesNotHijackOtherButtonsOrControllers() {
        assertEquals(KeyEvent.KEYCODE_UNKNOWN,
            JoyConMapping.key(JoyConSide.RIGHT, KeyEvent.KEYCODE_UNKNOWN, 0x220))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN,
            JoyConMapping.key(null, KeyEvent.KEYCODE_UNKNOWN, 0x220))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN,
            JoyConMapping.key(JoyConSide.LEFT, KeyEvent.KEYCODE_UNKNOWN, 0x2c4))
    }

    @Test fun rightHalfAcceptsBothAndroidAndKernelStickLayouts() {
        assertEquals(AXIS_X to AXIS_Y, JoyConMapping.stickAxes(JoyConSide.RIGHT, setOf(AXIS_X, AXIS_Y)))
        assertEquals(AXIS_Z to AXIS_RZ, JoyConMapping.stickAxes(JoyConSide.RIGHT, setOf(AXIS_Z, AXIS_RZ)))
        assertEquals(AXIS_RX to AXIS_RY, JoyConMapping.stickAxes(JoyConSide.RIGHT, setOf(AXIS_RX, AXIS_RY)))
        assertEquals(AXIS_Z to AXIS_RZ, JoyConMapping.stickAxes(JoyConSide.RIGHT, setOf(AXIS_X, AXIS_Y, AXIS_Z, AXIS_RZ)))
    }

    @Test fun missingAxesAreNotInventedAndLeftNeverUsesRightAxes() {
        assertNull(JoyConMapping.stickAxes(JoyConSide.LEFT, setOf(AXIS_Z, AXIS_RZ)))
        assertNull(JoyConMapping.stickAxes(JoyConSide.RIGHT, setOf(AXIS_X, AXIS_RZ)))
        assertNull(JoyConMapping.stickAxes(JoyConSide.RIGHT, emptySet()))
    }

    @Test fun faceButtonsUsePhysicalPositionsOnlyForRightHalf() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, JoyConMapping.key(JoyConSide.RIGHT, KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, JoyConMapping.key(JoyConSide.RIGHT, KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, JoyConMapping.key(JoyConSide.RIGHT, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, JoyConMapping.key(null, KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, JoyConMapping.key(JoyConSide.LEFT, KeyEvent.KEYCODE_DPAD_UP))
    }
}
