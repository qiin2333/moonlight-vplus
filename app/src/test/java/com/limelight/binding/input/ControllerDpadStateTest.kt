package com.limelight.binding.input

import android.view.KeyEvent
import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerDpadStateTest {
    @Test fun joyConUnknownKeySurvivesNeutralMotionUntilKeyUp() {
        val state = ControllerDpadState()
        val key = JoyConMapping.key(JoyConSide.LEFT, KeyEvent.KEYCODE_UNKNOWN, 0x220)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, key)
        state.key(key, ControllerPacket.UP_FLAG, true)
        repeat(10) { state.hat(0f, 0f) }
        assertEquals(ControllerPacket.A_FLAG or ControllerPacket.UP_FLAG,
            state.applyTo(ControllerPacket.A_FLAG))
        state.key(key, ControllerPacket.UP_FLAG, false)
        assertEquals(ControllerPacket.A_FLAG, state.applyTo(ControllerPacket.A_FLAG or ControllerPacket.UP_FLAG))
    }

    @Test fun releasingOneDirectionKeepsOtherHeldDirection() {
        val state = ControllerDpadState()
        state.key(19, ControllerPacket.UP_FLAG, true)
        state.key(21, ControllerPacket.LEFT_FLAG, true)
        assertEquals(ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG, state.applyTo(0))
        state.key(19, ControllerPacket.UP_FLAG, false)
        assertEquals(ControllerPacket.LEFT_FLAG, state.applyTo(0))
        state.key(21, ControllerPacket.LEFT_FLAG, false)
        assertEquals(0, state.applyTo(0))
    }

    @Test fun duplicateHatAndKeyReleaseIndependently() {
        val state = ControllerDpadState()
        state.key(21, ControllerPacket.LEFT_FLAG, true)
        state.hat(-1f, 0f)
        state.key(21, ControllerPacket.LEFT_FLAG, false)
        assertEquals(ControllerPacket.LEFT_FLAG, state.applyTo(0))
        state.key(21, ControllerPacket.LEFT_FLAG, true)
        state.hat(0f, 0f)
        assertEquals(ControllerPacket.LEFT_FLAG, state.applyTo(0))
        state.key(21, ControllerPacket.LEFT_FLAG, false)
        assertEquals(0, state.applyTo(0))
    }

    @Test fun overlappingDiagonalAndCardinalKeysDoNotReleaseEachOther() {
        val state = ControllerDpadState()
        state.key(268, ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG, true)
        state.key(19, ControllerPacket.UP_FLAG, true)
        state.key(268, ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG, false)
        assertEquals(ControllerPacket.UP_FLAG, state.applyTo(0))
    }
}
