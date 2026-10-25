package com.limelight.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.TouchpadInputPreferences
import com.limelight.preferences.TouchpadInputValues
import com.limelight.preferences.TouchPointerPresetPreferences
import com.limelight.gamemenu.TouchPointerSensitivityPresetStore
import com.limelight.preferences.restoreSettingsPreferences
import com.limelight.preferences.snapshotSettingsPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real same-device backup API; no package is written to disk. */
@RunWith(AndroidJUnit4::class)
class ConfigurationBackupRoundTripTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var preferences: SharedPreferences
    private lateinit var valueStatePreferences: SharedPreferences
    private var originalPreferences: Map<String, *>? = null
    private var originalValueState: Map<String, *>? = null
    private var originalTouchpadStores = emptyMap<String, Map<String, *>>()

    @Before
    fun savePreferencesAndDisableBackups() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        valueStatePreferences = context.getSharedPreferences(VALUE_STATE_PREFERENCES, Context.MODE_PRIVATE)
        // Export stamps per-value state and may create the device ID in default preferences.
        // Preserve complete files, including all synchronization metadata and string sets.
        originalPreferences = snapshotSettingsPreferences(preferences)
        originalValueState = snapshotSettingsPreferences(valueStatePreferences)
        originalTouchpadStores = listOf(TouchpadInputPreferences.FILE_NAME,
            TouchpadInputPreferences.PRESETS_FILE_NAME, TouchPointerPresetPreferences.FILE_NAME)
            .associateWith { snapshotSettingsPreferences(context.getSharedPreferences(it, Context.MODE_PRIVATE)) }
        disableBackups()
        assertTrue(preferences.edit()
            .putBoolean(REVERSE_RESOLUTION_KEY, false)
            .putInt(DISPLAY_MIGRATION_KEY, 2)
            .commit())
    }

    @After
    fun restoreEveryTouchedPreference() {
        if (!::preferences.isInitialized || originalPreferences == null) return
        try {
            originalTouchpadStores.forEach { (name, saved) ->
                restoreSettingsPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), saved)
            }
            originalValueState?.let { saved ->
                restoreSettingsPreferences(valueStatePreferences, saved)
                assertTrue("Failed to restore synchronization value state", valueStatePreferences.all == saved)
            }
        } finally {
            // Restore enabled flags last, after the original settings and metadata are together again.
            originalPreferences?.let { saved ->
                restoreSettingsPreferences(preferences, saved)
                assertTrue("Failed to restore default preferences", preferences.all == saved)
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun presetImportsRejectWrongTypesButAcceptValidReplacementsAndDeletion() {
        val manager = ConfigurationSyncManager(context)
        val jsonKey = TouchPointerPresetPreferences.JSON_KEY
        val deletedKey = TouchPointerPresetPreferences.DELETED_IDS_KEY
        val presetJson = """{"version":1,"presets":[{"id":"00000000-0000-0000-0000-000000000025","name":"Preset1","values":{"touch_sense":"250"},"updatedAt":1}]}"""
        for ((section, file) in listOf(
            "touchpadPresets" to TouchpadInputPreferences.PRESETS_FILE_NAME,
            "touchPointerPresets" to TouchPointerPresetPreferences.FILE_NAME
        )) {
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString(jsonKey, presetJson)
                .putStringSet(deletedKey, setOf("original-deleted-id")).commit())
            val exported = JSONObject(manager.exportSyncPackage())
            fun backup(values: JSONObject): String = JSONObject(exported.toString())
                .put("sections", JSONObject().put(section, JSONObject().put("values", values)))
                .toString()
            fun encoded(type: String, value: Any) = JSONObject().put("type", type).put("value", value)

            val rejected = manager.importSyncPackage(backup(JSONObject()
                .put(jsonKey, encoded("int", 7))
                .put(deletedKey, encoded("string", "not-a-set"))))
            assertEquals(0, rejected.defaultPreferencesImported)
            assertEquals(presetJson, prefs.getString(jsonKey, null))
            assertEquals(setOf("original-deleted-id"), prefs.getStringSet(deletedKey, null))
            assertEquals(1, TouchPointerSensitivityPresetStore(context, file).load { "Fallback$it" }.size)

            val replaced = manager.importSyncPackage(backup(JSONObject()
                .put(jsonKey, encoded("string", "{\"version\":1,\"presets\":[]}"))
                .put(deletedKey, encoded("stringSet", org.json.JSONArray().put("replacement-id")))))
            assertEquals(2, replaced.defaultPreferencesImported)
            assertTrue(TouchPointerSensitivityPresetStore(context, file).load { "Fallback$it" }.isEmpty())
            assertEquals(setOf("replacement-id"), prefs.getStringSet(deletedKey, null))

            val removed = manager.importSyncPackage(backup(JSONObject()
                .put(jsonKey, encoded("deleted", JSONObject.NULL))
                .put(deletedKey, encoded("deleted", JSONObject.NULL))))
            assertEquals(2, removed.defaultPreferencesImported)
            assertFalse(prefs.contains(jsonKey))
            assertFalse(prefs.contains(deletedKey))
        }
    }

    @Test
    fun touchpadValuesPresetsAndDeletesRoundTripWithoutRestoringActiveSelection() {
        val manager = ConfigurationSyncManager(context)
        val presetPrefs = context.getSharedPreferences(TouchpadInputPreferences.PRESETS_FILE_NAME, Context.MODE_PRIVATE)
        TouchpadInputPreferences.save(context, TouchpadInputValues(250, 175, 60))
        val presetJson = """{"version":1,"presets":[{"id":"00000000-0000-0000-0000-000000000025","name":"Preset1","values":{"touch_sense":"250"},"updatedAt":1}]}"""
        assertTrue(presetPrefs.edit().putString(TouchPointerPresetPreferences.JSON_KEY, presetJson)
            .putStringSet(TouchPointerPresetPreferences.DELETED_IDS_KEY, setOf("deleted-id"))
            .putString(TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY, "local-only").commit())
        val exported = JSONObject(manager.exportSyncPackage())
        val sections = exported.getJSONObject("sections")
        assertFalse(sections.getJSONObject("touchpadPresets").getJSONObject("values")
            .has(TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY))
        exported.put("sections", JSONObject()
            .put("touchpadInput", sections.getJSONObject("touchpadInput"))
            .put("touchpadPresets", sections.getJSONObject("touchpadPresets")))
        TouchpadInputPreferences.save(context, TouchpadInputValues())
        assertTrue(presetPrefs.edit().clear().commit())
        manager.importSyncPackage(exported.toString())
        assertEquals(TouchpadInputValues(250, 175, 60), TouchpadInputPreferences.read(context))
        assertEquals(presetJson, presetPrefs.getString(TouchPointerPresetPreferences.JSON_KEY, null))
        assertEquals(setOf("deleted-id"), presetPrefs.getStringSet(TouchPointerPresetPreferences.DELETED_IDS_KEY, null))
        assertFalse(presetPrefs.contains(TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY))
        exported.put("sections", JSONObject())
        manager.importSyncPackage(exported.toString())
        assertEquals(TouchpadInputValues(250, 175, 60), TouchpadInputPreferences.read(context))
        assertEquals(presetJson, presetPrefs.getString(TouchPointerPresetPreferences.JSON_KEY, null))
    }

    @Test
    fun displayChoicesCardVisibilityAndDetailSettingSurviveBackupRestore() {
        assertTrue(preferences.edit()
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "3440x1440")
            .putString(PreferenceConfiguration.FPS_PREF_STRING, "75")
            .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 37_000)
            .putBoolean(DISPLAY_CARD_KEY, false)
            .putBoolean(AUDIO_HAPTICS_CARD_KEY, false)
            .putInt(OVERLAY_OPACITY_KEY, 37)
            .commit())
        val backup = exportOnlyDefaultValues(setOf(
            PreferenceConfiguration.RESOLUTION_PREF_STRING,
            PreferenceConfiguration.FPS_PREF_STRING,
            PreferenceConfiguration.BITRATE_PREF_STRING,
            DISPLAY_CARD_KEY,
            AUDIO_HAPTICS_CARD_KEY,
            OVERLAY_OPACITY_KEY,
        ))

        assertTrue(preferences.edit()
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1920x1080")
            .putString(PreferenceConfiguration.FPS_PREF_STRING, "30")
            .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 9_000)
            .putBoolean(DISPLAY_CARD_KEY, true)
            .putBoolean(AUDIO_HAPTICS_CARD_KEY, true)
            .putInt(OVERLAY_OPACITY_KEY, 80)
            .commit())

        val imported = ConfigurationSyncManager(context).importSyncPackage(backup, null)
        assertEquals(6, imported.defaultPreferencesImported)
        assertEquals(6, imported.totalImported)
        assertEquals(0, imported.crownProfilesFailed + imported.pairingItemsFailed)
        assertEquals("3440x1440", preferences.all[PreferenceConfiguration.RESOLUTION_PREF_STRING])
        assertEquals("75", preferences.all[PreferenceConfiguration.FPS_PREF_STRING])
        assertEquals(37_000, preferences.all[PreferenceConfiguration.BITRATE_PREF_STRING])
        assertEquals(false, preferences.all[DISPLAY_CARD_KEY])
        assertEquals(false, preferences.all[AUDIO_HAPTICS_CARD_KEY])
        assertEquals(37, preferences.all[OVERLAY_OPACITY_KEY])

        val configuration = PreferenceConfiguration.readPreferences(context, null)
        assertEquals(3440, configuration.width)
        assertEquals(1440, configuration.height)
        assertEquals(75, configuration.fps)
        assertEquals(37_000, configuration.bitrate)
        assertTrue(configuration.isCustomResolution)
        assertFalse(configuration.isNativeResolution)
        assertFalse(configuration.showDisplaySettingsCard)
        assertFalse(configuration.showAudioHapticsCard)
        // This setting now lives inside the performance overlay detail page.
        assertEquals(37, configuration.perfOverlayBgOpacity)
    }

    @Test
    fun importingLegacyDisplayValuesNormalizesThemEvenAfterMigrationVersionTwo() {
        assertTrue(preferences.edit()
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "360p")
            .putInt(PreferenceConfiguration.FPS_PREF_STRING, 75)
            .putInt(DISPLAY_MIGRATION_KEY, 2)
            .commit())
        val backup = exportOnlyDefaultValues(setOf(
            PreferenceConfiguration.RESOLUTION_PREF_STRING,
            PreferenceConfiguration.FPS_PREF_STRING,
        ))
        assertEquals("360p", preferences.all[PreferenceConfiguration.RESOLUTION_PREF_STRING])
        assertEquals(75, preferences.all[PreferenceConfiguration.FPS_PREF_STRING])
        assertTrue(preferences.edit()
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1920x1080")
            .putString(PreferenceConfiguration.FPS_PREF_STRING, "60")
            .commit())

        val imported = ConfigurationSyncManager(context).importSyncPackage(backup, null)
        assertEquals(2, imported.defaultPreferencesImported)
        assertEquals(2, imported.totalImported)
        // Import must normalize before broadcasting a refresh, without waiting for a reader.
        assertEquals("640x360", preferences.all[PreferenceConfiguration.RESOLUTION_PREF_STRING])
        assertEquals("75", preferences.all[PreferenceConfiguration.FPS_PREF_STRING])
        assertEquals(2, preferences.getInt(DISPLAY_MIGRATION_KEY, 0))

        val configuration = PreferenceConfiguration.readPreferences(context, null)
        assertEquals(640, configuration.width)
        assertEquals(360, configuration.height)
        assertEquals(75, configuration.fps)
        assertFalse(configuration.isCustomResolution)
        assertFalse(configuration.isNativeResolution)
    }

    private fun disableBackups() {
        assertTrue("Failed to disable configuration backups", preferences.edit()
            .putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, false)
            .commit())
        instrumentation.waitForIdleSync()
    }

    private fun exportOnlyDefaultValues(keys: Set<String>): String {
        val exported = JSONObject(ConfigurationSyncManager(context).exportSyncPackage())
        val exportedValues = exported.getJSONObject("sections")
            .getJSONObject("defaultPreferences").getJSONObject("values")
        val selectedValues = JSONObject()
        for (key in keys) {
            assertTrue("Backup omitted setting: $key", exportedValues.has(key))
            selectedValues.put(key, exportedValues.getJSONObject(key))
        }
        // Keep the exporter's same-device metadata, but omit every unrelated section entirely.
        // Empty Crown/pairing sections would still enter their database import paths.
        exported.put("sections", JSONObject().put(
            "defaultPreferences", JSONObject().put("values", selectedValues),
        ))
        return exported.toString()
    }

    private companion object {
        const val VALUE_STATE_PREFERENCES = "config_sync_value_state"
        const val DISPLAY_MIGRATION_KEY = "display_selection_migration_version"
        const val REVERSE_RESOLUTION_KEY = "checkbox_reverse_resolution"
        const val DISPLAY_CARD_KEY = "checkbox_show_display_settings_card"
        const val AUDIO_HAPTICS_CARD_KEY = "checkbox_show_audio_haptics_card"
        const val OVERLAY_OPACITY_KEY = "seekbar_perf_overlay_bg_opacity"
    }
}
