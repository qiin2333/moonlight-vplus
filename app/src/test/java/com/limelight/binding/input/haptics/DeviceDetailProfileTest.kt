package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDetailProfileTest {
    @Test fun onlyTheMeasuredTickFirmwareGetsTheHigherDensity() {
        assertEquals(40L, DeviceDetailProfile.tickIntervalMs("meizu", "meizu 17", 33, "Flyme 10.5.0.0A"))
        assertEquals(90L, DeviceDetailProfile.tickIntervalMs("meizu", "meizu 17", 34, "Flyme 10.5.0.0A"))
        assertEquals(90L, DeviceDetailProfile.tickIntervalMs("meizu", "meizu 17", 33, "other firmware"))
        assertEquals(90L, DeviceDetailProfile.tickIntervalMs("other", "other", 33, "Flyme 10.5.0.0A"))
    }
}
