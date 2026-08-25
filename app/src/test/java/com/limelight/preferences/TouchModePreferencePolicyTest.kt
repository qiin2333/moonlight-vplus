package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchModePreferencePolicyTest {
    @Test
    fun `touchscreen devices default to trackpad when no mode was saved`() {
        assertEquals(
            TouchModePreset.TRACKPAD.toState(),
            resolveWithoutSavedMode(hasTouchscreen = true)
        )
    }

    @Test
    fun `non touchscreen devices default to native pointer when no mode was saved`() {
        assertEquals(
            TouchModePreset.NATIVE.toState(),
            resolveWithoutSavedMode(hasTouchscreen = false)
        )
    }

    @Test
    fun `saved classic mode is respected on non touchscreen devices`() {
        assertEquals(
            TouchModePreset.CLASSIC.toState(),
            TouchModePreferencePolicy.resolve(
                hasTouchscreen = false,
                enhancedTouch = false,
                touchscreenTrackpad = false,
                nativeMousePointer = false,
                screenDs5Touchpad = false
            )
        )
    }

    @Test
    fun `a partially saved trackpad choice beats the non touchscreen default`() {
        assertEquals(
            TouchModePreset.TRACKPAD.toState(),
            TouchModePreferencePolicy.resolve(
                hasTouchscreen = false,
                enhancedTouch = null,
                touchscreenTrackpad = true,
                nativeMousePointer = null,
                screenDs5Touchpad = null
            )
        )
    }

    @Test
    fun `preset inference follows the active primary mode`() {
        TouchModePreset.entries.forEach { preset ->
            assertEquals(preset, TouchModePreferencePolicy.presetFor(preset.toState()))
        }
    }

    private fun resolveWithoutSavedMode(hasTouchscreen: Boolean) =
        TouchModePreferencePolicy.resolve(
            hasTouchscreen = hasTouchscreen,
            enhancedTouch = null,
            touchscreenTrackpad = null,
            nativeMousePointer = null,
            screenDs5Touchpad = null
        )
}
