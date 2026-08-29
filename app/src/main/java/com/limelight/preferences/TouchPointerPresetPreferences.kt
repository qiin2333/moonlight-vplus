package com.limelight.preferences

/** Storage contract shared by the game menu and configuration backup. */
internal object TouchPointerPresetPreferences {
    const val FILE_NAME = "touch_pointer_presets"
    const val JSON_KEY = "touch_pointer_sensitivity_presets_json"
    const val ACTIVE_PRESET_ID_KEY = "active_touch_pointer_sensitivity_preset_id"
}
