package com.limelight.nvstream

/** Session-wide Sunshine /launch and /resume preference, independent of local transports. */
enum class HostGamepadSelection(val preferenceValue: String) {
    AUTOMATIC("automatic"), HOST("host"), XBOX("x360"), DS4("ds4"), DS5("ds5");

    fun resolve(): String? = when (this) {
        // Automatic DS5 hints belong to individual arrival packets, not the session.
        AUTOMATIC -> null
        HOST -> null
        else -> preferenceValue
    }

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.preferenceValue == value } ?: AUTOMATIC
    }
}
