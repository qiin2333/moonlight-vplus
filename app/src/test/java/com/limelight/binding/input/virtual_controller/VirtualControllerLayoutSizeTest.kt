package com.limelight.binding.input.virtual_controller

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualControllerLayoutSizeTest {
    @Test
    fun `measured container takes precedence over physical display metrics`() {
        val size = VirtualControllerLayoutSize.resolve(2659, 1280, 2800, 1280)

        assertEquals(2659, size.width)
        assertEquals(1280, size.height)
    }

    @Test
    fun `display metrics are used only before the container is measured`() {
        val size = VirtualControllerLayoutSize.resolve(0, 0, 1920, 1080)

        assertEquals(1920, size.width)
        assertEquals(1080, size.height)
    }

    @Test
    fun `fallback dimensions never allow zero-sized layout math`() {
        val size = VirtualControllerLayoutSize.resolve(0, 0, 0, 0)

        assertEquals(1, size.width)
        assertEquals(1, size.height)
    }
}
