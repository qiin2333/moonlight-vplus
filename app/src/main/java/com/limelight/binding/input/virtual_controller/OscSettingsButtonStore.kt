package com.limelight.binding.input.virtual_controller

import android.content.Context
import androidx.core.content.edit
import com.limelight.ui.FloatingButtonNormalizedPosition

/** Device-local placement, independent of controller profiles and configuration sync. */
internal class OscSettingsButtonStore(context: Context) {
    private val preferences = context.getSharedPreferences("osc_settings_button", Context.MODE_PRIVATE)

    var dragEnabled: Boolean
        get() = preferences.getBoolean("drag_enabled", false)
        set(value) { preferences.edit { putBoolean("drag_enabled", value) } }

    fun position(): FloatingButtonNormalizedPosition? {
        val values = preferences.all
        val x = values["x"] as? Float ?: return null
        val y = values["y"] as? Float ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        return FloatingButtonNormalizedPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    fun save(position: FloatingButtonNormalizedPosition) {
        preferences.edit {
            putFloat("x", position.x)
            putFloat("y", position.y)
        }
    }

    fun resetPosition() {
        preferences.edit { remove("x"); remove("y") }
    }
}
