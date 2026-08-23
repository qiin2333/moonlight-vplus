package com.limelight.binding.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartWheelOwnerGateTest {
    @Test
    fun onlyOneControllerOwnsTheWheel() {
        val gate = StartWheelOwnerGate()
        val first = Any()
        val second = Any()

        assertTrue(gate.tryClaim(first))
        assertTrue(gate.isOwner(first))
        assertFalse(gate.tryClaim(second))
        assertFalse(gate.isOwner(second))
    }

    @Test
    fun ownerCanReenterAndOnlyOwnerCanRelease() {
        val gate = StartWheelOwnerGate()
        val owner = Any()
        val other = Any()

        assertTrue(gate.tryClaim(owner))
        assertTrue(gate.tryClaim(owner))
        assertFalse(gate.release(other))
        assertTrue(gate.isOwner(owner))
        assertTrue(gate.release(owner))
        assertFalse(gate.isOwner(owner))
    }

    @Test
    fun releasedWheelCanMoveBetweenSystemAndUsbControllers() {
        val gate = StartWheelOwnerGate()
        val systemController = Any()
        val usbController = Any()

        assertTrue(gate.tryClaim(systemController))
        assertTrue(gate.release(systemController))
        assertTrue(gate.tryClaim(usbController))
        assertTrue(gate.isOwner(usbController))
    }

    @Test
    fun clearDropsAStaleOwner() {
        val gate = StartWheelOwnerGate()
        val owner = Any()

        assertTrue(gate.tryClaim(owner))
        assertTrue(gate.clear())
        assertFalse(gate.isOwner(owner))
        assertFalse(gate.clear())
    }
}
