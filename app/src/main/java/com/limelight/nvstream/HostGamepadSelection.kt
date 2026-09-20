package com.limelight.nvstream

/** Session-wide Sunshine /launch and /resume preference, independent of local transports. */
enum class HostGamepadSelection(val preferenceValue: String) {
    AUTOMATIC("automatic"), HOST("host"), XBOX("x360"), DS4("ds4"), DS5("ds5");

    fun resolve(screenDs5: Boolean, waveformController: Boolean): String? = when (this) {
        AUTOMATIC -> if (screenDs5 || waveformController) "ds5" else null
        HOST -> null
        else -> preferenceValue
    }

    /** Existing PCM negotiation affects the entire session, so opt in only for a usable candidate. */
    fun requestsAuthoredPcm(waveformController: Boolean, controllerOutputEnabled: Boolean): Boolean =
        waveformController && controllerOutputEnabled && this != XBOX && this != DS4

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.preferenceValue == value } ?: AUTOMATIC
    }
}
