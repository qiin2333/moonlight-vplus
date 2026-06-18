package com.limelight.binding.input.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadCompatibilityStoreTest {
    @Test
    fun roundTripsCompatibleDevices() {
        val device = TouchpadCompatibilityDevice(
            descriptor = "test-touchpad-descriptor",
            vendorId = 0x1234,
            productId = 0x5678,
            name = "Example Touchpad"
        )

        assertEquals(
            listOf(device),
            TouchpadCompatibilityStore.decode(
                TouchpadCompatibilityStore.encode(listOf(device))
            )
        )
    }
}
