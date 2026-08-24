package com.limelight.binding.audio

import android.content.Context
import androidx.core.content.edit

internal class MicrophoneButtonPositionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun hasCustomPosition(): Boolean =
        preferences.contains(KEY_CUSTOM_X) && preferences.contains(KEY_CUSTOM_Y)

    fun customX(): Float = preferences.getFloat(KEY_CUSTOM_X, DEFAULT_CUSTOM_X)

    fun customY(): Float = preferences.getFloat(KEY_CUSTOM_Y, DEFAULT_CUSTOM_Y)

    fun saveCustom(position: MicrophoneButtonNormalizedPosition) {
        preferences.edit {
            remove(LEGACY_KEY_POSITION)
            putFloat(KEY_CUSTOM_X, position.x)
            putFloat(KEY_CUSTOM_Y, position.y)
        }
    }

    fun clearCustomPosition() {
        preferences.edit { clear() }
    }

    companion object {
        const val PREFERENCE_KEY = "list_mic_button_position"
        private const val PREFERENCES_NAME = "microphone_button_position"
        private const val LEGACY_KEY_POSITION = "position"
        private const val KEY_CUSTOM_X = "custom_x"
        private const val KEY_CUSTOM_Y = "custom_y"
        private const val DEFAULT_CUSTOM_X = 1f
        private const val DEFAULT_CUSTOM_Y = 0.5f
    }
}
