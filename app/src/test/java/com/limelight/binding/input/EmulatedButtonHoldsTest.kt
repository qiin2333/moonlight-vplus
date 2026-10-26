package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class EmulatedButtonHoldsTest {
    @Test
    fun releasingOneControllerKeepsOtherControllersArrowOrMouseButtonHeld() {
        for (button in listOf(ControllerPacket.UP_FLAG, ControllerPacket.A_FLAG)) {
            val holds = EmulatedButtonHolds()
            val first = Any()
            val second = Any()

            assertEquals(button, holds.update(first, button))
            assertEquals(0, holds.update(second, button))
            assertEquals(0, holds.update(first, 0))
            assertEquals(button, holds.heldMask)
            assertEquals(button, holds.update(second, 0))
            assertEquals(0, holds.heldMask)
        }
    }

    @Test
    fun releasingOneDirectionDoesNotReleaseAnother() {
        val holds = EmulatedButtonHolds()
        val context = Any()
        val up = ControllerPacket.UP_FLAG
        val right = ControllerPacket.RIGHT_FLAG

        assertEquals(up or right, holds.update(context, up or right))
        assertEquals(up, holds.update(context, right))
        assertEquals(right, holds.heldMask)
    }
}
