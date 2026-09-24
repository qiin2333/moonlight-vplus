package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class EmulatedButtonHoldsTest {
    @Test
    fun releasingOneControllerKeepsTheOtherControllersArrowHeld() {
        val holds = EmulatedButtonHolds()
        val first = Any()
        val second = Any()
        val up = ControllerPacket.UP_FLAG

        assertEquals(up, holds.update(first, up))
        assertEquals(0, holds.update(second, up))
        assertEquals(0, holds.update(first, 0))
        assertEquals(up, holds.heldMask)
        assertEquals(up, holds.update(second, 0))
        assertEquals(0, holds.heldMask)
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

    @Test
    fun releasingOneControllerKeepsTheOtherControllersMouseButtonHeld() {
        val holds = EmulatedButtonHolds()
        val first = Any()
        val second = Any()
        val leftButton = ControllerPacket.A_FLAG

        assertEquals(leftButton, holds.update(first, leftButton))
        assertEquals(0, holds.update(second, leftButton))
        assertEquals(0, holds.update(first, 0))
        assertEquals(leftButton, holds.heldMask)
        assertEquals(leftButton, holds.update(second, 0))
    }
}
