package com.limelight.binding.audio

import android.content.Context
import androidx.core.content.edit

/** Device-local drag override; intentionally excluded from configuration sync. */
internal class MicrophoneButtonPositionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun customPosition(): MicrophoneButtonNormalizedPosition? {
        val values = preferences.all
        val x = values[KEY_CUSTOM_X] as? Float ?: return null
        val y = values[KEY_CUSTOM_Y] as? Float ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        return MicrophoneButtonNormalizedPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

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
        private const val PREFERENCES_NAME = "microphone_button_position"
        private const val LEGACY_KEY_POSITION = "position"
        private const val KEY_CUSTOM_X = "custom_x"
        private const val KEY_CUSTOM_Y = "custom_y"
    }
}
