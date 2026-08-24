package com.limelight.binding.audio

import android.content.Context
import androidx.core.content.edit

internal class MicrophoneButtonPositionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun position(): String = preferences
        .getString(KEY_POSITION, MicrophoneButtonPlacement.DEFAULT_POSITION)
        ?.takeIf { it in VALID_POSITIONS }
        ?: MicrophoneButtonPlacement.DEFAULT_POSITION

    fun customX(): Float = preferences.getFloat(KEY_CUSTOM_X, DEFAULT_CUSTOM_X)

    fun customY(): Float = preferences.getFloat(KEY_CUSTOM_Y, DEFAULT_CUSTOM_Y)

    fun savePreset(position: String) {
        preferences.edit {
            putString(
                KEY_POSITION,
                position.takeIf { it in VALID_POSITIONS } ?: MicrophoneButtonPlacement.DEFAULT_POSITION
            )
        }
    }

    fun saveCustom(position: MicrophoneButtonNormalizedPosition) {
        preferences.edit {
            putString(KEY_POSITION, MicrophoneButtonPlacement.POSITION_CUSTOM)
            putFloat(KEY_CUSTOM_X, position.x)
            putFloat(KEY_CUSTOM_Y, position.y)
        }
    }

    companion object {
        const val PREFERENCE_KEY = "list_mic_button_position"
        private const val PREFERENCES_NAME = "microphone_button_position"
        private const val KEY_POSITION = "position"
        private const val KEY_CUSTOM_X = "custom_x"
        private const val KEY_CUSTOM_Y = "custom_y"
        private const val DEFAULT_CUSTOM_X = 1f
        private const val DEFAULT_CUSTOM_Y = 0.5f
        private val VALID_POSITIONS = setOf(
            MicrophoneButtonPlacement.POSITION_TOP_LEFT,
            MicrophoneButtonPlacement.POSITION_TOP_CENTER,
            MicrophoneButtonPlacement.POSITION_TOP_RIGHT,
            MicrophoneButtonPlacement.POSITION_CENTER_LEFT,
            MicrophoneButtonPlacement.POSITION_CENTER_RIGHT,
            MicrophoneButtonPlacement.POSITION_BOTTOM_LEFT,
            MicrophoneButtonPlacement.POSITION_BOTTOM_CENTER,
            MicrophoneButtonPlacement.POSITION_BOTTOM_RIGHT,
            MicrophoneButtonPlacement.POSITION_CUSTOM
        )
    }
}
