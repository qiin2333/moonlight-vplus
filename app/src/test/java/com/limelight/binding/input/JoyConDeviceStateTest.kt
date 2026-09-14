package com.limelight.binding.input

import android.view.KeyEvent
import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.*
import org.junit.Test

class JoyConDeviceStateTest {
    @Test fun otherControllerFamiliesNeverCreateJoyConState() {
        val devices = listOf(
            0x045e to 0x02fd, // Xbox One S
            0x054c to 0x09cc, // DualShock 4
            0x054c to 0x0ce6, // DualSense
            0x057e to 0x2009, // Switch Pro
            0x057e to 0x2008, // already combined Joy-Con device
            0x057e to 0x2066, // not a first-generation Joy-Con
            0 to 0
        )
        for (combine in listOf(false, true)) {
            devices.forEach { (vendor, product) ->
                assertNull(JoyConDeviceState.create(vendor, product, combine))
            }
        }
    }

    @Test fun leftIdentityAndDpadWorkaroundSurviveDisablingPairing() {
        for (combine in listOf(false, true)) {
            val state = JoyConDeviceState.create(0x057e, 0x2006, combine)!!
            assertEquals(JoyConSide.LEFT, state.side)
            assertEquals(combine, state.combineEnabled)
            assertNotNull(state.dpad)
            assertEquals(KeyEvent.KEYCODE_DPAD_UP, state.remapKey(KeyEvent.KEYCODE_UNKNOWN, 0x220))
            assertFalse(state.preferMotionSource)
        }
    }

    @Test fun rightHasNoDpadStateAndOnlyRemapsInCombinedMode() {
        val independent = JoyConDeviceState.create(0x057e, 0x2007, false)!!
        val combined = JoyConDeviceState.create(0x057e, 0x2007, true)!!
        assertEquals(JoyConSide.RIGHT, independent.side)
        assertNull(independent.dpad)
        assertNull(combined.dpad)
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, independent.remapKey(KeyEvent.KEYCODE_BUTTON_X, 0))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, combined.remapKey(KeyEvent.KEYCODE_BUTTON_X, 0))
        assertFalse(independent.preferMotionSource)
        assertTrue(combined.preferMotionSource)
    }

    @Test fun separateDevicesAndReconnectionsDoNotShareHeldDirections() {
        val first = JoyConDeviceState.create(0x057e, 0x2006, true)!!.dpad!!
        val second = JoyConDeviceState.create(0x057e, 0x2006, true)!!.dpad!!
        first.key(KeyEvent.KEYCODE_DPAD_UP, ControllerPacket.UP_FLAG, true)
        assertEquals(ControllerPacket.UP_FLAG, first.applyTo(0))
        assertEquals(0, second.applyTo(0))
        val reconnected = JoyConDeviceState.create(0x057e, 0x2006, true)!!.dpad!!
        assertEquals(0, reconnected.applyTo(0))
    }
}
