package com.limelight.binding.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDs5ControllerPolicyTest {
    @Test
    fun activePrimaryControllerMustBeRemovedBeforeReannouncement() {
        assertTrue(ScreenDs5ControllerPolicy.shouldRemovePrimaryController(0b0101, false))
        assertEquals(0b0100, ScreenDs5ControllerPolicy.withoutPrimaryController(0b0101).toInt())
    }

    @Test
    fun sentArrivalIsRemovedEvenWhenPrimaryIsMissingFromComputedMask() {
        assertTrue(ScreenDs5ControllerPolicy.shouldRemovePrimaryController(0b0100, true))
        assertEquals(0b0100, ScreenDs5ControllerPolicy.withoutPrimaryController(0b0100).toInt())
    }

    @Test
    fun emptyPrimarySlotDoesNotNeedRemoval() {
        assertFalse(ScreenDs5ControllerPolicy.shouldRemovePrimaryController(0b0100, false))
    }

}
