package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsModePolicyTest {
    @Test
    fun backgroundModesKeepTheirPersistedValuesAndLegacyFlags() {
        val cases = listOf(
            Triple(BackgroundStreamBehavior.DISCONNECT, "disconnect", listOf(false, false, false)),
            Triple(BackgroundStreamBehavior.RESUME, "resume", listOf(true, false, false)),
            Triple(BackgroundStreamBehavior.KEEP_CONNECTED, "keep_connected", listOf(true, true, false)),
            Triple(BackgroundStreamBehavior.KEEP_AUDIO, "keep_audio", listOf(true, true, true))
        )
        cases.forEach { (mode, stored, flags) ->
            assertEquals(flags, listOf(mode.resumeAutomatically, mode.keepConnected, mode.playAudio))
            assertEquals(mode, BackgroundStreamBehaviorPolicy.fromLegacy(flags[0], flags[1], flags[2]))
            assertEquals(mode, BackgroundStreamBehaviorPolicy.fromPreferenceValue(stored))
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
    fun quitModesKeepTheirPersistedValuesAndLegacyFlag() {
        assertEquals(QuitBehavior.DISCONNECT_ONLY, QuitBehavior.fromLegacy(true))
        assertEquals(QuitBehavior.STOP_HOST_APP, QuitBehavior.fromLegacy(false))
        assertEquals(QuitBehavior.DISCONNECT_ONLY, QuitBehavior.fromPreferenceValue("disconnect_only"))
        assertEquals(QuitBehavior.STOP_HOST_APP, QuitBehavior.fromPreferenceValue("stop_host_app"))
    }

    @Test
    fun dualSenseModesKeepTheirPersistedValuesAndLegacyFlags() {
        val cases = listOf(
            Triple(DualSenseOutputMode.OFF, "off", false to false),
            Triple(DualSenseOutputMode.SYSTEM_BLUETOOTH, "system_bluetooth", true to false),
            Triple(DualSenseOutputMode.WIRELESS_BRIDGE, "wireless_bridge", false to true),
            Triple(DualSenseOutputMode.BOTH, "both", true to true)
        )
        cases.forEach { (mode, stored, flags) ->
            assertEquals(flags, mode.systemBluetooth to mode.wirelessBridge)
            assertEquals(mode, DualSenseOutputMode.fromLegacy(flags.first, flags.second))
            assertEquals(mode, DualSenseOutputMode.fromPreferenceValue(stored))
        }
    }
}
