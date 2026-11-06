package com.limelight.preferences

import android.content.Context
import androidx.core.content.edit

object TouchpadInputPreferences {
    const val FILE_NAME = "touchpad_input"
    const val PRESETS_FILE_NAME = "touchpad_presets"
    const val SENSITIVITY = "touch_sense"
    const val SCROLL_SPEED = "touchpad_scroll_speed_percent"
    const val WHEEL_SPEED = "mouse_wheel_speed"
    val PORTABLE_KEYS = setOf(SENSITIVITY, SCROLL_SPEED, WHEEL_SPEED)

    fun read(context: Context): TouchpadInputValues {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return TouchpadInputValues(
            prefs.getInt(SENSITIVITY, 100).coerceIn(1, 1000),
            prefs.getInt(SCROLL_SPEED, 100).coerceIn(1, 500),
            prefs.getInt(WHEEL_SPEED, 20).coerceIn(1, 100)
        )
    }

    fun save(context: Context, values: TouchpadInputValues) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit {
            putInt(SENSITIVITY, values.sensitivity)
            putInt(SCROLL_SPEED, values.scrollSpeed)
            putInt(WHEEL_SPEED, values.wheelSpeed)
        }
    }
}

data class TouchpadInputValues(
    val sensitivity: Int = 100,
    val scrollSpeed: Int = 100,
    val wheelSpeed: Int = 20
)
