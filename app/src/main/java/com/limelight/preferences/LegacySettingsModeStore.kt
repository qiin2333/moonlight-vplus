package com.limelight.preferences

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Keeps legacy preference keys authoritative while exposing consolidated modes to the UI.
 *
 * Touch and microphone mode strings are retained as derived mirrors because older portable
 * backups already include them. Runtime behavior continues to come from the legacy booleans.
 */
internal class LegacySettingsModeStore(private val preferences: SharedPreferences) {
    fun backgroundMode(): BackgroundStreamBehavior =
        BackgroundStreamBehaviorPolicy.fromLegacy(
            preferences.getBoolean(KEY_RESUME_STREAM, false),
            preferences.getBoolean(KEY_KEEP_STREAM_CONNECTED, false),
            preferences.getBoolean(KEY_BACKGROUND_AUDIO, false),
        )

    fun setBackgroundMode(mode: BackgroundStreamBehavior) {
        preferences.edit {
            putBoolean(KEY_RESUME_STREAM, mode.resumeAutomatically)
            putBoolean(KEY_KEEP_STREAM_CONNECTED, mode.keepConnected)
            putBoolean(KEY_BACKGROUND_AUDIO, mode.playAudio)
        }
    }

    fun quitMode(): QuitBehavior = QuitBehavior.fromLegacy(
        preferences.getBoolean(KEY_DISCONNECT_ONLY_ON_QUIT, false),
    )

    fun setQuitMode(mode: QuitBehavior) {
        preferences.edit {
            putBoolean(KEY_DISCONNECT_ONLY_ON_QUIT, mode == QuitBehavior.DISCONNECT_ONLY)
        }
    }

    fun dualSenseMode(): DualSenseOutputMode = DualSenseOutputMode.fromLegacy(
        preferences.getBoolean(
            PreferenceConfiguration.DUALSENSE_DIRECT_BLUETOOTH_PREF_STRING,
            false,
        ),
        preferences.getBoolean(KEY_DUALSENSE_WIRELESS_BRIDGE, false),
    )

    fun setDualSenseMode(mode: DualSenseOutputMode) {
        preferences.edit {
            putBoolean(
                PreferenceConfiguration.DUALSENSE_DIRECT_BLUETOOTH_PREF_STRING,
                mode.systemBluetooth,
            )
            putBoolean(KEY_DUALSENSE_WIRELESS_BRIDGE, mode.wirelessBridge)
        }
    }

    fun ensureTouchDefaults(hasTouchscreen: Boolean) {
        if (TOUCH_LEGACY_KEYS.any(preferences::contains)) return

        val storedPreset = TouchModePreset.fromPreferenceValue(
            preferences.getString(PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING, null)
        )
        val state = storedPreset?.toState() ?: TouchModePreferencePolicy.resolve(
            hasTouchscreen = hasTouchscreen,
            enhancedTouch = null,
            touchscreenTrackpad = null,
            nativeMousePointer = null,
            screenDs5Touchpad = null,
        )
        setTouchState(TouchModePreferencePolicy.presetFor(state), state)
    }

    fun touchState(hasTouchscreen: Boolean): TouchModePreferenceState =
        TouchModePreferencePolicy.resolve(
            hasTouchscreen = hasTouchscreen,
            enhancedTouch = booleanOrNull(PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING),
            touchscreenTrackpad = booleanOrNull(PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING),
            nativeMousePointer = booleanOrNull(
                PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING
            ),
            screenDs5Touchpad = booleanOrNull(PreferenceConfiguration.SCREEN_DS5_TOUCHPAD_PREF_STRING),
        )

    fun setTouchPreset(preset: TouchModePreset): TouchModePreferenceState {
        val state = preset.toState()
        setTouchState(preset, state)
        return state
    }

    fun reconcileTouchModeMirror(state: TouchModePreferenceState) {
        val mirrorValue = TouchModePreferencePolicy.exactPresetFor(state)?.preferenceValue
            ?: TOUCH_MODE_CUSTOM
        if (preferences.getString(
                PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING,
                null,
            ) != mirrorValue
        ) {
            preferences.edit {
                putString(
                    PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING,
                    mirrorValue,
                )
            }
        }
    }

    fun microphoneMode(): String = MicVolumeProcessingPolicy.resolveMode(
        storedMode = preferences.getString(
            PreferenceConfiguration.MIC_VOLUME_PROCESSING_MODE_PREF_STRING,
            null,
        ),
        processing = booleanOrNull(PreferenceConfiguration.MIC_VOLUME_PROCESSING_PREF_STRING),
        gain = booleanOrNull(PreferenceConfiguration.MIC_GAIN_ENABLED_PREF_STRING),
        balance = booleanOrNull(PreferenceConfiguration.MIC_BALANCE_ENABLED_PREF_STRING),
    )

    fun setMicrophoneMode(modeValue: String): MicVolumeProcessingPolicy.Flags {
        val mode = MicVolumeProcessingPolicy.normalize(modeValue)
        val flags = MicVolumeProcessingPolicy.flagsFor(mode)
        preferences.edit {
            putString(PreferenceConfiguration.MIC_VOLUME_PROCESSING_MODE_PREF_STRING, mode)
            putBoolean(PreferenceConfiguration.MIC_VOLUME_PROCESSING_PREF_STRING, flags.processing)
            putBoolean(PreferenceConfiguration.MIC_GAIN_ENABLED_PREF_STRING, flags.gain)
            putBoolean(PreferenceConfiguration.MIC_BALANCE_ENABLED_PREF_STRING, flags.balance)
        }
        return flags
    }

    fun reconcileMicrophoneModeMirror(mode: String) {
        if (preferences.getString(
                PreferenceConfiguration.MIC_VOLUME_PROCESSING_MODE_PREF_STRING,
                null,
            ) != mode
        ) {
            preferences.edit {
                putString(PreferenceConfiguration.MIC_VOLUME_PROCESSING_MODE_PREF_STRING, mode)
            }
        }
    }

    private fun setTouchState(preset: TouchModePreset, state: TouchModePreferenceState) {
        preferences.edit {
            putString(
                PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING,
                preset.preferenceValue,
            )
            putBoolean(
                PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING,
                state.enhancedTouch,
            )
            putBoolean(
                PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING,
                state.touchscreenTrackpad,
            )
            putBoolean(
                PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING,
                state.nativeMousePointer,
            )
            putBoolean(
                PreferenceConfiguration.SCREEN_DS5_TOUCHPAD_PREF_STRING,
                state.screenDs5Touchpad,
            )
        }
    }

    private fun booleanOrNull(key: String): Boolean? =
        if (preferences.contains(key)) preferences.getBoolean(key, false) else null

    companion object {
        const val KEY_RESUME_STREAM = "checkbox_resume_stream"
        const val KEY_KEEP_STREAM_CONNECTED = "checkbox_extreme_resume"
        const val KEY_BACKGROUND_AUDIO = "checkbox_background_audio"
        const val KEY_DISCONNECT_ONLY_ON_QUIT = "checkbox_swap_quit_and_disconnect"
        const val KEY_DUALSENSE_WIRELESS_BRIDGE = "checkbox_dualsense_wireless_bridge"
        const val TOUCH_MODE_CUSTOM = "custom"

        private val TOUCH_LEGACY_KEYS = setOf(
            PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING,
            PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING,
            PreferenceConfiguration.ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING,
            PreferenceConfiguration.SCREEN_DS5_TOUCHPAD_PREF_STRING,
        )

        val modeKeys = TOUCH_LEGACY_KEYS + setOf(
            KEY_RESUME_STREAM,
            KEY_KEEP_STREAM_CONNECTED,
            KEY_BACKGROUND_AUDIO,
            KEY_DISCONNECT_ONLY_ON_QUIT,
            PreferenceConfiguration.DUALSENSE_DIRECT_BLUETOOTH_PREF_STRING,
            KEY_DUALSENSE_WIRELESS_BRIDGE,
            PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING,
            PreferenceConfiguration.MIC_VOLUME_PROCESSING_MODE_PREF_STRING,
            PreferenceConfiguration.MIC_VOLUME_PROCESSING_PREF_STRING,
            PreferenceConfiguration.MIC_GAIN_ENABLED_PREF_STRING,
            PreferenceConfiguration.MIC_BALANCE_ENABLED_PREF_STRING,
        )

    }
}
