package com.limelight.gamemenu

import com.limelight.nvstream.HostGamepadSelection
import com.limelight.preferences.SensaStrengthPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensaHapticsCardStateTest {
    @Test
    fun serviceReconciliationTracksBothEnableAndDisableRequests() {
        val enabling = SensaHapticsCardState(requestedEnabled = true, appliedEnabled = false)
        assertTrue(enabling.pending)
        assertTrue(enabling.copy(appliedEnabled = null).pending)
        val enabled = enabling.copy(appliedEnabled = true)
        assertFalse(enabled.pending)
        val disabling = enabled.copy(requestedEnabled = false)
        assertTrue(disabling.pending)
        assertFalse(disabling.copy(appliedEnabled = false).pending)
    }

    @Test
    fun liveRumbleNeedsNoRestartButNewAuthoredPcmDoes() {
        val enabled = SensaHapticsCardState(requestedEnabled = true, appliedEnabled = true, waveformControllerPresent = true)
        assertTrue(enabled.pendingRestart)
        assertFalse(enabled.copy(authoredPcmRequested = true).pendingRestart)
        assertFalse(enabled.copy(mode = SensaStrengthPreferences.RUMBLE_ONLY).pendingRestart)
        assertFalse(enabled.copy(requestedEnabled = false).pendingRestart)
        assertTrue(enabled.copy(mode = SensaStrengthPreferences.ONLY_HAPTIC).pendingRestart)
    }
    @Test
    fun restartNoticeRequiresAnEligibleNextLaunch() {
        val enabled = SensaHapticsCardState(
            requestedEnabled = true, appliedEnabled = true, waveformControllerPresent = true)
        for (host in HostGamepadSelection.entries) {
            val state = enabled.copy(hostGamepad = host)
            if (host == HostGamepadSelection.XBOX || host == HostGamepadSelection.DS4) {
                assertFalse(state.pendingRestart)
            } else {
                assertTrue(state.pendingRestart)
            }
            assertFalse(state.copy(controllerOutputEnabled = false).pendingRestart)
            assertFalse(state.copy(waveformControllerPresent = false).pendingRestart)
            assertFalse(state.copy(authoredPcmRequested = true).pendingRestart)
        }
    }
}
