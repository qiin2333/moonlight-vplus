package com.limelight.utils

import com.limelight.binding.input.virtual_controller.OscSettingsButtonStore
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.TouchPointerPresetPreferences
import com.limelight.preferences.TouchpadInputPreferences
import com.limelight.binding.audio.MicrophoneButtonPreferences
import com.limelight.ui.FloatBallPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationSyncSchemaTest {
    @Test
    fun touchpadStoresAreObservedButActivePresetIsLocal() {
        val stores = ConfigurationSyncManager.portableSharedPreferenceNames()
        assertTrue(TouchpadInputPreferences.FILE_NAME in stores)
        assertTrue(TouchpadInputPreferences.PRESETS_FILE_NAME in stores)
        TouchpadInputPreferences.PORTABLE_KEYS.forEach { key ->
            assertTrue(ConfigurationSyncManager.isPortableSharedPreferenceKey(TouchpadInputPreferences.FILE_NAME, key))
            assertFalse(ConfigurationSyncManager.isPortableDefaultPreferenceKey(key))
        }
        assertTrue(ConfigurationSyncManager.isPortableSharedPreferenceKey(
            TouchpadInputPreferences.PRESETS_FILE_NAME, TouchPointerPresetPreferences.JSON_KEY))
        assertFalse(ConfigurationSyncManager.isPortableSharedPreferenceKey(
            TouchpadInputPreferences.PRESETS_FILE_NAME, TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY))
    }

    @Test
    fun consolidatedSettingsKeepNewAndLegacyBackupKeysPortable() {
        listOf(
            "list_mouse_axis_correction",
            "list_hevc_low_latency_mode",
            PreferenceConfiguration.FULL_RANGE_PREF_STRING,
            PreferenceConfiguration.GAME_MENU_OPACITY_PREF_STRING,
            PreferenceConfiguration.NATIVE_MOUSE_MODE_PRESET_PREF_STRING,
            PreferenceConfiguration.TOUCHSCREEN_TRACKPAD_PREF_STRING,
            PreferenceConfiguration.ENABLE_ENHANCED_TOUCH_PREF_STRING,
            PreferenceConfiguration.MIC_VOLUME_PROCESSING_MODE_PREF_STRING,
            PreferenceConfiguration.MIC_VOLUME_PROCESSING_PREF_STRING,
            PreferenceConfiguration.MIC_GAIN_ENABLED_PREF_STRING,
            PreferenceConfiguration.MIC_BALANCE_ENABLED_PREF_STRING,
            PreferenceConfiguration.ENABLE_HOST_CADENCE_PRECISE_SYNC_STRING,
            PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING,
            "checkbox_resume_stream",
            "checkbox_extreme_resume",
            "checkbox_background_audio",
            "checkbox_swap_quit_and_disconnect",
            PreferenceConfiguration.DUALSENSE_DIRECT_BLUETOOTH_PREF_STRING,
            "checkbox_dualsense_wireless_bridge",
            "checkbox_vibrate_osc",
            "checkbox_mouse_emulation",
            "checkbox_shizuku_keyboard_accessibility",
            "checkbox_show_audio_haptics_card",
            "checkbox_show_display_settings_card",
        ).forEach { key ->
            assertTrue("Expected portable key: $key", ConfigurationSyncManager.isPortableDefaultPreferenceKey(key))
        }

        listOf(
            "list_background_stream_behavior",
            "list_quit_behavior",
            "list_dualsense_output_mode",
        ).forEach { key ->
            assertFalse(
                "Synthetic UI key must not enter the backup schema: $key",
                ConfigurationSyncManager.isPortableDefaultPreferenceKey(key),
            )
        }
    }

    @Test
    fun gameRumbleModePreferenceIsPortable() {
        assertTrue(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                PreferenceConfiguration.GAME_RUMBLE_MODE_PREF_STRING
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                PreferenceConfiguration.SCREEN_DS5_TOUCHPAD_PREF_STRING
            )
        )
    }

    @Test
    fun microphoneSettingsKeepPolicyPortableAndLastHostStateLocal() {
        listOf(
            MicrophoneButtonPreferences.KEY_SHOW_BUTTON,
            MicrophoneButtonPreferences.KEY_PRESET_POSITION,
            PreferenceConfiguration.MIC_INITIAL_STATE_PREF_STRING,
        ).forEach { key ->
            assertTrue(ConfigurationSyncManager.isPortableDefaultPreferenceKey(key))
        }
        assertFalse(
            "Per-host microphone last state must stay local",
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                "microphone_initial_state",
                "host_test",
            ),
        )
    }

    @Test
    fun floatBallPresetIsPortable() {
        assertTrue(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                FloatBallPreferences.KEY_PRESET_POSITION
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                "FloatBallPrefs",
                "normalizedX"
            )
        )
    }

    @Test
    fun touchPointerSensitivityPresetsUsePortableDedicatedStorage() {
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                TouchPointerPresetPreferences.JSON_KEY
            )
        )
        assertTrue(
            TouchPointerPresetPreferences.FILE_NAME in
                ConfigurationSyncManager.portableSharedPreferenceNames()
        )
        assertTrue(
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                TouchPointerPresetPreferences.FILE_NAME,
                TouchPointerPresetPreferences.JSON_KEY
            )
        )
        assertTrue(
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                TouchPointerPresetPreferences.FILE_NAME,
                TouchPointerPresetPreferences.DELETED_IDS_KEY
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                TouchPointerPresetPreferences.FILE_NAME,
                TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY
            )
        )
    }

    @Test
    fun historicalSchemaFixturesRemainReadableByProductionNormalizer() {
        for (fixture in listOf("schema-v1.example.json", "schema-v2.example.json")) {
            val normalized = ConfigurationSyncManager.mergeSyncPackagesForTest(
                listOf(readFixture("config-sync/$fixture").toString())
            )
            val sections = JsonParser.parseString(normalized).asJsonObject["sections"].asJsonObject
            val values = sections["defaultPreferences"].asJsonObject["values"].asJsonObject
            assertEquals("1920x1080", values["list_resolution"].asJsonObject["value"].asString)
            assertEquals(50000, values["seekbar_bitrate_kbps"].asJsonObject["value"].asInt)
            assertEquals("int", values["seekbar_bitrate_kbps"].asJsonObject["type"].asString)
            assertEquals(1, sections["crownProfiles"].asJsonArray.size())
            if (fixture == "schema-v2.example.json") {
                val computers = sections["pairing"].asJsonObject["computers"].asJsonArray
                assertEquals(1, computers.size())
                val computer = computers[0].asJsonObject
                assertEquals("host-uuid", computer["uuid"].asString)
                assertEquals("Gaming PC", computer["name"].asString)
                for (field in listOf("localAddress", "activeAddress")) {
                    val address = computer[field].asJsonObject
                    assertEquals(field, "192.168.1.10", address["address"].asString)
                    assertEquals(field, 47989, address["port"].asInt)
                }
            }
        }
    }

    @Test
    fun localSnapshotMetadataIsNotPortableDefaultPreference() {
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_LOCAL_SNAPSHOT_AT
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_IMPORT
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_EXTERNAL_SYNC_TREE_URI
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_EXTERNAL_SYNC_DIRECTORY
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_BACKUP_PASSWORD
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_EXTERNAL_CONTENT_HASH
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_BACKGROUND_SYNC_APPLIED
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_BACKGROUND_SYNC_AT
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_BACKGROUND_SYNC_ERROR
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_BACKGROUND_SYNC_READ_EXTERNAL
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_BACKGROUND_SYNC_SUCCESS
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_BACKGROUND_SYNC_WROTE_EXTERNAL
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_EXTERNAL_SYNC_AT
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LAST_EXTERNAL_SYNC_ID
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_LOCAL_SNAPSHOT_NOW
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableDefaultPreferenceKey(
                ConfigurationSyncManager.PREF_SYNC_STATUS
            )
        )
    }

    @Test
    fun portableSharedPreferenceChangeDetectionCoversSyncedStores() {
        val syncedStores = ConfigurationSyncManager.portableSharedPreferenceNames()
        assertTrue("app_last_settings should be observed", "app_last_settings" in syncedStores)
        assertTrue("custom_resolutions should be observed", "custom_resolutions" in syncedStores)
        assertTrue("SceneConfigs should be observed", "SceneConfigs" in syncedStores)
        assertTrue("HiddenApps should be observed", "HiddenApps" in syncedStores)
        assertTrue("AppView should be observed", "AppView" in syncedStores)
        assertTrue("OSC settings button should be observed", OscSettingsButtonStore.PREFERENCES_NAME in syncedStores)

        assertTrue(ConfigurationSyncManager.isPortableSharedPreferenceKey("app_last_settings", "host-app"))
        assertTrue(ConfigurationSyncManager.isPortableSharedPreferenceKey("custom_resolutions", "custom_resolutions"))
        assertTrue(ConfigurationSyncManager.isPortableSharedPreferenceKey("SceneConfigs", "scene_1"))
        assertTrue(ConfigurationSyncManager.isPortableSharedPreferenceKey("HiddenApps", "host_uuid"))
        assertTrue(ConfigurationSyncManager.isPortableSharedPreferenceKey("AppView", "app_background_mode"))
        assertTrue(
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                OscSettingsButtonStore.PREFERENCES_NAME,
                "size_scale"
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                OscSettingsButtonStore.PREFERENCES_NAME,
                OscSettingsButtonStore.POSITION_X_KEY
            )
        )
        assertFalse(
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                OscSettingsButtonStore.PREFERENCES_NAME,
                OscSettingsButtonStore.POSITION_Y_KEY
            )
        )
        assertTrue(
            ConfigurationSyncManager.isPortableSharedPreferenceKey(
                "OSC",
                "xbox.full.6"
            )
        )
        assertTrue("OSC profiles should be observed", "OSC" in syncedStores)

        assertFalse(ConfigurationSyncManager.isPortableSharedPreferenceKey("AppView", "display_selection"))
        assertFalse(ConfigurationSyncManager.isPortableSharedPreferenceKey("unknown", "app_background_mode"))
        assertFalse(ConfigurationSyncManager.isPortableSharedPreferenceKey("custom_resolutions", null))
    }

    private fun readFixture(path: String): JsonElement {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
            ?: error("Missing fixture: $path")
        return stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it) }
    }
}
