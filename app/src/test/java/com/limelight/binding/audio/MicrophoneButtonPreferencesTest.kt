package com.limelight.binding.audio

import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneButtonPreferencesTest {
    @Test
    fun missingVisibilityFollowsLegacyMenuAction() {
        assertTrue(
            MicrophoneButtonPreferences.resolveVisibility(
                explicitValue = null,
                legacyAction = PreferenceConfiguration.MIC_MENU_ACTION_SHOW_BUTTON
            )
        )
        assertFalse(
            MicrophoneButtonPreferences.resolveVisibility(
                explicitValue = null,
                legacyAction = PreferenceConfiguration.MIC_MENU_ACTION_TOGGLE_MIC
            )
        )
    }

    @Test
    fun explicitVisibilityAlwaysWins() {
        assertFalse(
            MicrophoneButtonPreferences.resolveVisibility(
                explicitValue = false,
                legacyAction = PreferenceConfiguration.MIC_MENU_ACTION_SHOW_BUTTON
            )
        )
        assertTrue(
            MicrophoneButtonPreferences.resolveVisibility(
                explicitValue = true,
                legacyAction = PreferenceConfiguration.MIC_MENU_ACTION_TOGGLE_MIC
            )
        )
    }
}
