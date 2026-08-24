package com.limelight.ui

import android.content.Context
import androidx.preference.PreferenceManager

internal class FloatBallPreferences(context: Context) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun presetPosition(): String = FloatingButtonPlacement.normalizePreset(
        preferences.getString(KEY_PRESET_POSITION, FloatingButtonPlacement.DEFAULT_POSITION)
    )

    companion object {
        const val KEY_PRESET_POSITION = "list_float_ball_position"
    }
}
