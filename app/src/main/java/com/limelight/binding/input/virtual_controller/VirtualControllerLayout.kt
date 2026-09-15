package com.limelight.binding.input.virtual_controller

/** Stable preference values. Classic keeps the original OSC profile keys. */
enum class VirtualControllerLayout(val preferenceValue: String) {
    XBOX("xbox"), DS("ds"), NS("ns"), CLASSIC("classic");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.preferenceValue == value } ?: XBOX
    }
}
