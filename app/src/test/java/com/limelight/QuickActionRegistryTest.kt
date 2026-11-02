package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionRegistryTest {
    @Test
    fun onlyLocalKeyboardRunsWithGameFocus() {
        val focusedActionIds = QuickActionRegistry.getAllActions(null)
            .values
            .filter(StreamAction::isWithGameFocus)
            .map(StreamAction::id)

        assertEquals(listOf("toggle_keyboard"), focusedActionIds)
    }

    @Test
    fun defaultsKeepCoreActionsAndFollowEachEnabledFeature() {
        val coreActions = setOf(
            "send_esc", "send_win", "send_alt_tab", "toggle_keyboard", "toggle_perf", "quit"
        )
        for (flags in 0..7) {
            val hdr = flags and 1 != 0
            val mic = flags and 2 != 0
            val controller = flags and 4 != 0
            val ids = QuickActionRegistry.defaultIdsFor(
                enableHdr = hdr,
                enableMic = mic,
                enableVirtualController = controller
            )

            assertTrue("Missing core actions with flags=$flags", ids.containsAll(coreActions))
            assertEquals("Duplicate defaults with flags=$flags", ids.size, ids.toSet().size)
            assertEquals("HDR with flags=$flags", hdr, "toggle_hdr" in ids)
            assertEquals("Microphone with flags=$flags", mic, "toggle_mic" in ids)
            assertEquals("Controller with flags=$flags", controller, "toggle_controller" in ids)
        }
    }
}
