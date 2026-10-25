package com.limelight

import com.limelight.preferences.PreferenceConfiguration

/** Keeps committed display choices ahead of the session's original launch overrides. */
internal data class DisplayReconnectSettings(
    val nativeResolution: Boolean,
    val customResolution: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val screenCombinationMode: Int
) {
    fun applyTo(config: PreferenceConfiguration) {
        config.isNativeResolution = nativeResolution
        config.isCustomResolution = customResolution
        config.width = width
        config.height = height
        config.fps = fps
        config.bitrate = bitrate
        config.screenCombinationMode = screenCombinationMode
    }

    companion object {
        fun capture(config: PreferenceConfiguration) = DisplayReconnectSettings(
            nativeResolution = config.isNativeResolution,
            customResolution = config.isCustomResolution,
            width = config.width,
            height = config.height,
            fps = config.fps,
            bitrate = config.bitrate,
            screenCombinationMode = config.screenCombinationMode
        )
    }
}
