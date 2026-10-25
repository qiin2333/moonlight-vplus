package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.utils.ConfigurationSyncManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomResolutionsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var global: SharedPreferences
    private lateinit var custom: SharedPreferences
    private lateinit var valueState: SharedPreferences
    private lateinit var globalSnapshot: Map<String, *>
    private lateinit var customSnapshot: Map<String, *>
    private lateinit var valueStateSnapshot: Map<String, *>

    @Before
    fun prepareIsolatedPreferences() {
        global = PreferenceManager.getDefaultSharedPreferences(context)
        custom = context.getSharedPreferences("custom_resolutions", Context.MODE_PRIVATE)
        valueState = context.getSharedPreferences("config_sync_value_state", Context.MODE_PRIVATE)
        globalSnapshot = snapshotSettingsPreferences(global)
        customSnapshot = snapshotSettingsPreferences(custom)
        valueStateSnapshot = snapshotSettingsPreferences(valueState)
        assertTrue(global.edit()
            .putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, false)
            .remove(LEGACY_LOW_PRESETS_KEY)
            .commit())
        assertTrue(custom.edit().clear().commit())
    }

    @After
    fun restorePreferences() {
        try {
            restoreSettingsPreferences(custom, customSnapshot)
            restoreSettingsPreferences(valueState, valueStateSnapshot)
            assertEquals(customSnapshot, snapshotSettingsPreferences(custom))
            assertEquals(valueStateSnapshot, snapshotSettingsPreferences(valueState))
        } finally {
            // Re-enable the original backup flags last, even when a verification assertion fails.
            restoreSettingsPreferences(global, globalSnapshot)
            assertEquals(globalSnapshot, snapshotSettingsPreferences(global))
        }
    }

    @Test
    fun newInstallStartsWithFourPresetsButSavingAnEmptyListKeepsItEmpty() {
        assertEquals(DEFAULT_RESOLUTIONS, CustomResolutionsStore.load(context))
        CustomResolutionsStore.save(context, emptyList())

        assertTrue(CustomResolutionsStore.load(context).isEmpty())
        assertTrue(DisplayChoiceLists.resolutionStoreValues(context).isEmpty())
        assertEquals(listOf("2340x1080", "Native"), DisplayChoiceLists.resolutionValues(
            DisplayChoiceLists.resolutionStoreValues(context), "2340x1080"))
        // Retaining the active selection in the selector must not save it back to the list.
        assertTrue(CustomResolutionsStore.load(context).isEmpty())
    }

    @Test
    fun backupBeforeOpeningTheListRestoresItsDefaultValuesOverALaterList() {
        val manager = ConfigurationSyncManager(context)
        val backup = JSONObject(manager.exportSyncPackage())
        val section = backup.getJSONObject("sections").getJSONObject("customResolutions")
        backup.put("sections", JSONObject().put("customResolutions", section))
        CustomResolutionsStore.save(context, listOf(Resolution(2340, 1080)))

        manager.importSyncPackage(backup.toString())

        assertEquals(DEFAULT_RESOLUTIONS, CustomResolutionsStore.load(context))
    }

    @Test
    fun anExistingEmptyListWithoutMigrationMetadataDoesNotRegainDefaults() {
        assertTrue(custom.edit().putStringSet(STORED_VALUES_KEY, emptySet()).commit())
        assertTrue(CustomResolutionsStore.load(context).isEmpty())
        assertTrue(DisplayChoiceLists.resolutionStoreValues(context).isEmpty())
    }

    @Test
    fun legacySwitchAddsLowPresetsOnceAndTheirRemovalSurvivesReopening() {
        assertTrue(custom.edit().putStringSet(STORED_VALUES_KEY, setOf("2340x1080"))
            .putInt(MIGRATION_KEY, 1).commit())
        assertTrue(global.edit().putBoolean(LEGACY_LOW_PRESETS_KEY, true).commit())

        assertEquals(listOf("640x360", "854x480", "2340x1080"),
            CustomResolutionsStore.load(context).map(Resolution::toString))
        assertFalse(global.contains(LEGACY_LOW_PRESETS_KEY))

        CustomResolutionsStore.save(context, listOf(Resolution(2340, 1080)))
        assertEquals(listOf(Resolution(2340, 1080)), CustomResolutionsStore.load(context))
        assertEquals(listOf(Resolution(2340, 1080)), CustomResolutionsStore.load(context))
    }

    @Test
    fun importingAnOldBackupMigratesItsSwitchAfterThisDeviceAlreadyMigrated() {
        CustomResolutionsStore.save(context, emptyList())
        val manager = ConfigurationSyncManager(context)
        val backup = JSONObject(manager.exportSyncPackage())
        backup.put("sections", JSONObject()
            .put("defaultPreferences", section(JSONObject().put(LEGACY_LOW_PRESETS_KEY,
                encoded("boolean", true))))
            .put("customResolutions", section(JSONObject()
                .put(STORED_VALUES_KEY, encoded("stringSet", JSONArray().put("2340x1080")))
                .put(MIGRATION_KEY, encoded("int", 1)))))

        manager.importSyncPackage(backup.toString())

        assertEquals(setOf("640x360", "854x480", "2340x1080"),
            custom.getStringSet(STORED_VALUES_KEY, emptySet()))
        assertFalse(global.contains(LEGACY_LOW_PRESETS_KEY))
        assertEquals(listOf("640x360", "854x480", "2340x1080"),
            CustomResolutionsStore.load(context).map(Resolution::toString))
        // Re-importing a backup explicitly replaces the list, including after a later user edit.
        CustomResolutionsStore.save(context, listOf(Resolution(1600, 900)))
        manager.importSyncPackage(backup.toString())
        assertEquals(listOf("640x360", "854x480", "2340x1080"),
            CustomResolutionsStore.load(context).map(Resolution::toString))
        assertFalse(global.contains(LEGACY_LOW_PRESETS_KEY))
        val exported = JSONObject(manager.exportSyncPackage()).getJSONObject("sections")
            .getJSONObject("customResolutions").getJSONObject("values")
            .getJSONObject(STORED_VALUES_KEY).getJSONArray("value")
        assertEquals(setOf("640x360", "854x480", "2340x1080"),
            (0 until exported.length()).map(exported::getString).toSet())
    }

    @Test
    fun backedUpEmptyListRemainsEmptyAfterRestore() {
        CustomResolutionsStore.save(context, emptyList())
        val manager = ConfigurationSyncManager(context)
        val backup = JSONObject(manager.exportSyncPackage())
        val section = backup.getJSONObject("sections").getJSONObject("customResolutions")
        backup.put("sections", JSONObject().put("customResolutions", section))
        CustomResolutionsStore.save(context, DEFAULT_RESOLUTIONS)

        manager.importSyncPackage(backup.toString())

        assertTrue(CustomResolutionsStore.load(context).isEmpty())
        assertTrue(DisplayChoiceLists.resolutionStoreValues(context).isEmpty())
    }

    @Test
    fun aMissingBackupListPreservesLocalValuesWhileAnExplicitEmptyArrayReplacesThem() {
        val saved = listOf(Resolution(2340, 1080))
        CustomResolutionsStore.save(context, saved)
        val manager = ConfigurationSyncManager(context)
        val backup = JSONObject(manager.exportSyncPackage())
        val values = JSONObject()
        backup.put("sections", JSONObject().put("customResolutions", section(values)))

        manager.importSyncPackage(backup.toString())
        assertEquals(saved, CustomResolutionsStore.load(context))

        values.put(STORED_VALUES_KEY, encoded("stringSet", JSONArray()))
        manager.importSyncPackage(backup.toString())
        assertTrue(CustomResolutionsStore.load(context).isEmpty())
    }

    private fun section(values: JSONObject) = JSONObject().put("values", values)
    private fun encoded(type: String, value: Any) = JSONObject().put("type", type).put("value", value)

    private companion object {
        val DEFAULT_RESOLUTIONS = listOf(
            Resolution(1280, 720), Resolution(1920, 1080),
            Resolution(2560, 1440), Resolution(3840, 2160)
        )
        const val STORED_VALUES_KEY = "custom_resolutions"
        const val MIGRATION_KEY = "migration_version"
        const val LEGACY_LOW_PRESETS_KEY = PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING
    }
}
