package com.limelight.binding.audio

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.limelight.preferences.PreferenceConfiguration

internal class MicrophoneButtonPreferences(context: Context) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun migrateLegacyVisibilityIfNeeded() {
        val explicitValue = if (preferences.contains(KEY_SHOW_BUTTON)) {
            preferences.getBoolean(KEY_SHOW_BUTTON, DEFAULT_SHOW_BUTTON)
        } else {
            null
        }
        val legacyAction = preferences.getString(
            PreferenceConfiguration.MIC_MENU_ACTION_MODE_PREF_STRING,
            PreferenceConfiguration.MIC_MENU_ACTION_SHOW_BUTTON
        )
        val resolvedVisibility = resolveVisibility(explicitValue, legacyAction)
        if (explicitValue != null) return

        preferences.edit {
            putBoolean(KEY_SHOW_BUTTON, resolvedVisibility)
        }
    }

    fun isButtonShownByDefault(): Boolean {
        migrateLegacyVisibilityIfNeeded()
        return preferences.getBoolean(KEY_SHOW_BUTTON, DEFAULT_SHOW_BUTTON)
    }

    fun presetPosition(): String = MicrophoneButtonPlacement.normalizePreset(
        preferences.getString(KEY_PRESET_POSITION, MicrophoneButtonPlacement.DEFAULT_POSITION)
    )

    companion object {
        const val KEY_SHOW_BUTTON = "checkbox_show_mic_button"
        const val KEY_PRESET_POSITION = "list_mic_button_position"
        private const val DEFAULT_SHOW_BUTTON = true

        internal fun resolveVisibility(explicitValue: Boolean?, legacyAction: String?): Boolean =
            explicitValue
                ?: (legacyAction == PreferenceConfiguration.MIC_MENU_ACTION_SHOW_BUTTON)
    }
}
