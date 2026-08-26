package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsModePolicyTest {
    @Test
    fun backgroundModesRoundTripThroughLegacyFlags() {
        BackgroundStreamBehavior.entries.forEach { mode ->
            assertEquals(
                mode,
                BackgroundStreamBehaviorPolicy.fromLegacy(
                    mode.resumeAutomatically,
                    mode.keepConnected,
                    mode.playAudio,
                ),
            )
            assertEquals(mode, BackgroundStreamBehaviorPolicy.fromPreferenceValue(mode.preferenceValue))
        }
    }

    @Test
    fun backgroundLegacyFlagsNormalizeToTheMostCapableMode() {
        assertEquals(
            BackgroundStreamBehavior.KEEP_AUDIO,
            BackgroundStreamBehaviorPolicy.fromLegacy(false, false, true),
        )
        assertEquals(
            BackgroundStreamBehavior.KEEP_CONNECTED,
            BackgroundStreamBehaviorPolicy.fromLegacy(false, true, false),
        )
    }

    @Test
    fun quitModesRoundTripThroughLegacyFlag() {
        QuitBehavior.entries.forEach { mode ->
            val legacy = mode == QuitBehavior.DISCONNECT_ONLY
            assertEquals(mode, QuitBehavior.fromLegacy(legacy))
            assertEquals(mode, QuitBehavior.fromPreferenceValue(mode.preferenceValue))
        }
    }

    @Test
    fun dualSenseModesRoundTripThroughLegacyFlags() {
        DualSenseOutputMode.entries.forEach { mode ->
            assertEquals(
                mode,
                DualSenseOutputMode.fromLegacy(mode.systemBluetooth, mode.wirelessBridge),
            )
            assertEquals(mode, DualSenseOutputMode.fromPreferenceValue(mode.preferenceValue))
        }
    }
}
