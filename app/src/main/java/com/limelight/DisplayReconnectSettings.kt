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
    fun canSwitchResolutionFrom(previous: DisplayReconnectSettings, adaptiveBitrate: Boolean): Boolean =
        (width != previous.width || height != previous.height) &&
            fps == previous.fps && screenCombinationMode == previous.screenCombinationMode &&
            width.compareTo(height) == previous.width.compareTo(previous.height) &&
            (!adaptiveBitrate || bitrate == previous.bitrate) &&
            width in 2..16384 && height in 2..16384 && width % 2 == 0 && height % 2 == 0

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
