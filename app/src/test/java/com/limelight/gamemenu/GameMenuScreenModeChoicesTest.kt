package com.limelight.gamemenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GameMenuScreenModeChoicesTest {
    private val labels = mapOf(
        "-1" to "Follow host", "0" to "No operation", "1" to "Activate",
        "2" to "Primary", "4" to "Secondary", "3" to "Exclusive"
    )

    @Test
    fun startupPoliciesDoNotBecomeReconnectActionsOrSelectAnUnrelatedAction() {
        for (current in listOf("-1", "0", "1")) {
            val choices = inStreamScreenModeChoices(labels, current)
            assertEquals(listOf("2", "4", "3"), choices.map { it.value })
            assertFalse(choices.any { it.selected })
        }
    }

    @Test
    fun concreteTopologyIsSelectedWithoutChangingItsProtocolValue() {
        val choices = inStreamScreenModeChoices(labels, "4")
        assertEquals("Secondary", choices.single { it.selected }.label)
        assertEquals("4", choices.single { it.selected }.value)
    }
}
