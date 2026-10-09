package com.limelight.binding.input.virtual_controller

import android.content.Context
import androidx.core.content.edit
import com.limelight.ui.FloatingButtonNormalizedPosition

/** Stream overlay placement and appearance, stored separately from global preferences. */
internal class OscSettingsButtonStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var dragEnabled: Boolean
        get() = preferences.getBoolean(DRAG_ENABLED_KEY, false)
        set(value) { preferences.edit { putBoolean(DRAG_ENABLED_KEY, value) } }

    var sizeScale: Float
        get() = preferences.getFloat(SIZE_SCALE_KEY, DEFAULT_SIZE_SCALE)
            .takeIf { it.isFinite() }?.coerceIn(MIN_SIZE_SCALE, MAX_SIZE_SCALE) ?: DEFAULT_SIZE_SCALE
        set(value) { preferences.edit { putFloat(SIZE_SCALE_KEY, value.coerceIn(MIN_SIZE_SCALE, MAX_SIZE_SCALE)) } }

    fun position(): FloatingButtonNormalizedPosition? {
        val values = preferences.all
        val x = values[POSITION_X_KEY] as? Float ?: return null
        val y = values[POSITION_Y_KEY] as? Float ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        return FloatingButtonNormalizedPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    fun save(position: FloatingButtonNormalizedPosition) {
        preferences.edit {
            putFloat(POSITION_X_KEY, position.x)
            putFloat(POSITION_Y_KEY, position.y)
        }
    }

    fun resetPosition() {
        preferences.edit { remove(POSITION_X_KEY); remove(POSITION_Y_KEY) }
    }

    companion object {
        const val PREFERENCES_NAME = "osc_settings_button"
        const val DRAG_ENABLED_KEY = "drag_enabled"
        const val POSITION_X_KEY = "x"
        const val POSITION_Y_KEY = "y"
        const val SIZE_SCALE_KEY = "size_scale"
        const val DEFAULT_SIZE_SCALE = 1f
        const val MIN_SIZE_SCALE = 0.5f
        const val MAX_SIZE_SCALE = 2f

        val PORTABLE_PREFERENCE_KEYS = setOf(
            DRAG_ENABLED_KEY,
            POSITION_X_KEY,
            POSITION_Y_KEY,
            SIZE_SCALE_KEY
        )
    }
}
