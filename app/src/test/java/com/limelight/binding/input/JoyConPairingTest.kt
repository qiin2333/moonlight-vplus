package com.limelight.binding.input

import org.junit.Assert.*
import org.junit.Test

class JoyConPairingTest {
    private val left = JoyConSide.LEFT
    private val right = JoyConSide.RIGHT

    @Test fun recognizesOnlyOriginalNintendoHalves() {
        assertEquals(left, joyConSide(0x057e, 0x2006))
        assertEquals(right, joyConSide(0x057e, 0x2007))
        assertNull(joyConSide(0x057e, 0x2009))
        assertNull(joyConSide(0x057e, 0x2008))
        assertNull(joyConSide(0x1234, 0x2006))
    }

    @Test fun pairsEitherConnectionOrderAndCountsOneLogicalController() {
        for (devices in listOf(mapOf(1 to left, 2 to right), mapOf(2 to right, 1 to left))) {
            val pairing = JoyConPairing().apply { update(devices) }
            assertEquals(2, pairing.partner(1))
            assertEquals(1, pairing.partner(2))
            assertEquals(1, devices.size - pairing.pairCount)
        }
    }

    @Test fun doesNotGuessWithMultipleUnpairedControllers() {
        val pairing = JoyConPairing()
        pairing.update(mapOf(1 to left, 2 to left, 3 to right))
        assertEquals(0, pairing.pairCount)
        assertNull(pairing.partner(3))
        pairing.update(mapOf(1 to left, 2 to left))
        assertEquals(0, pairing.pairCount)
    }

    @Test fun establishedPairIsNotStolenByAnotherPlayer() {
        val pairing = JoyConPairing()
        pairing.update(mapOf(1 to left, 2 to right))
        pairing.update(mapOf(1 to left, 2 to right, 3 to left, 4 to right))
        assertEquals(2, pairing.partner(1))
        assertEquals(4, pairing.partner(3))
        assertEquals(2, pairing.pairCount)
    }

    @Test fun reconnectWithNewAndroidDeviceIdRemovesStaleMembership() {
        val pairing = JoyConPairing()
        pairing.update(mapOf(1 to left, 2 to right))
        pairing.update(mapOf(2 to right))
        assertNull(pairing.partner(1))
        assertNull(pairing.partner(2))
        pairing.update(mapOf(3 to left, 2 to right))
        assertEquals(3, pairing.partner(2))
        assertNull(pairing.partner(1))
        pairing.update(emptyMap())
        assertEquals(0, pairing.pairCount)
    }
}
