package com.limelight.preferences

internal data class TouchModePreferenceState(
    val enhancedTouch: Boolean,
    val touchscreenTrackpad: Boolean,
    val nativeMousePointer: Boolean,
    val screenDs5Touchpad: Boolean
)

internal enum class TouchModePreset(val preferenceValue: String) {
    ENHANCED("enhanced"),
    CLASSIC("classic"),
    TRACKPAD("trackpad"),
    NATIVE("native");

    fun toState(): TouchModePreferenceState = when (this) {
        ENHANCED -> TouchModePreferenceState(
            enhancedTouch = true,
            touchscreenTrackpad = false,
            nativeMousePointer = false,
            screenDs5Touchpad = false
        )
        CLASSIC -> TouchModePreferenceState(
            enhancedTouch = false,
            touchscreenTrackpad = false,
            nativeMousePointer = false,
            screenDs5Touchpad = false
        )
        TRACKPAD -> TouchModePreferenceState(
            enhancedTouch = false,
            touchscreenTrackpad = true,
            nativeMousePointer = false,
            screenDs5Touchpad = false
        )
        NATIVE -> TouchModePreferenceState(
            enhancedTouch = false,
            touchscreenTrackpad = false,
            nativeMousePointer = true,
            screenDs5Touchpad = false
        )
    }
}

internal object TouchModePreferencePolicy {
    /** Device capabilities choose a default only; any persisted mode wins. */
    fun resolve(
        hasTouchscreen: Boolean,
        enhancedTouch: Boolean?,
        touchscreenTrackpad: Boolean?,
        nativeMousePointer: Boolean?,
        screenDs5Touchpad: Boolean?
    ): TouchModePreferenceState {
        val hasSavedSelection = enhancedTouch != null ||
            touchscreenTrackpad != null ||
            nativeMousePointer != null ||
            screenDs5Touchpad != null

        if (!hasSavedSelection) {
            return if (hasTouchscreen) {
                TouchModePreset.TRACKPAD.toState()
            } else {
                TouchModePreset.NATIVE.toState()
            }
        }

        return TouchModePreferenceState(
            enhancedTouch = enhancedTouch ?: false,
            touchscreenTrackpad = touchscreenTrackpad ?: false,
            nativeMousePointer = nativeMousePointer ?: false,
            screenDs5Touchpad = screenDs5Touchpad ?: false
        )
    }

    fun presetFor(state: TouchModePreferenceState): TouchModePreset = when {
        state.nativeMousePointer -> TouchModePreset.NATIVE
        state.touchscreenTrackpad -> TouchModePreset.TRACKPAD
        state.enhancedTouch -> TouchModePreset.ENHANCED
        else -> TouchModePreset.CLASSIC
    }

    /** Returns null when restored legacy flags do not describe one exact preset. */
    fun exactPresetFor(state: TouchModePreferenceState): TouchModePreset? =
        TouchModePreset.entries.firstOrNull { it.toState() == state }
}
