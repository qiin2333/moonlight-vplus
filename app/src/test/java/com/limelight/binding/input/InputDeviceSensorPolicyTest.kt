package com.limelight.binding.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDeviceSensorPolicyTest {
    @Test
    fun android12And12LNeverCreateInputDeviceSensorManager() {
        assertFalse(InputDeviceSensorPolicy.shouldUse(sdkInt = 31, enabled = true))
        assertFalse(InputDeviceSensorPolicy.shouldUse(sdkInt = 32, enabled = true))
    }

    @Test
    fun android13AndLaterHonorThePreference() {
        assertTrue(InputDeviceSensorPolicy.shouldUse(sdkInt = 33, enabled = true))
        assertTrue(InputDeviceSensorPolicy.shouldUse(sdkInt = 36, enabled = true))
        assertFalse(InputDeviceSensorPolicy.shouldUse(sdkInt = 33, enabled = false))
    }
}
