package com.limelight.preferences

/** Maps the in-game display card to the same keys used by the settings page. */
internal object DisplayPreferenceValues {
    fun of(
        nativeResolution: Boolean,
        width: Int,
        height: Int,
        reverseResolution: Boolean = false,
        fps: Int,
        bitrate: Int,
        adaptiveBitrate: Boolean,
        abrMode: String,
        screenCombinationMode: Int
    ): Map<String, Any> {
        return PreferenceConfiguration.DISPLAY_PREFERENCE_KEYS.associateWith { key ->
            when (key) {
                PreferenceConfiguration.RESOLUTION_PREF_STRING ->
                    if (nativeResolution) {
                        PreferenceConfiguration.RES_NATIVE
                    } else {
                        val storedWidth = if (reverseResolution) height else width
                        val storedHeight = if (reverseResolution) width else height
                        "${storedWidth}x${storedHeight}"
                    }
                PreferenceConfiguration.FPS_PREF_STRING -> fps.toString()
                PreferenceConfiguration.BITRATE_PREF_STRING -> bitrate
                PreferenceConfiguration.ADAPTIVE_BITRATE_PREF_STRING -> adaptiveBitrate
                PreferenceConfiguration.ABR_MODE_PREF_STRING -> abrMode
                PreferenceConfiguration.SCREEN_COMBINATION_MODE_PREF_STRING ->
                    screenCombinationMode.toString()
                else -> error("Unexpected display preference: $key")
            }
        }
    }
}
