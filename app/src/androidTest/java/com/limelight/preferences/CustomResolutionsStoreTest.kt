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
    private val global = PreferenceManager.getDefaultSharedPreferences(context)
    private val custom = context.getSharedPreferences("custom_resolutions", Context.MODE_PRIVATE)
    private val valueState = context.getSharedPreferences("config_sync_value_state", Context.MODE_PRIVATE)
    private lateinit var snapshots: List<Pair<SharedPreferences, Map<String, *>>>

    @Before
    fun prepareIsolatedPreferences() {
        snapshots = listOf(global, custom, valueState).map { it to snapshotSettingsPreferences(it) }
        assertTrue(global.edit()
            .putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, false)
            .remove(LEGACY_LOW_PRESETS_KEY).commit())
        assertTrue(custom.edit().clear().commit())
    }

    @After
    fun restorePreferences() {
        // Restore backup flags last, after list contents and synchronization metadata.
        for ((preferences, saved) in snapshots.asReversed()) {
            restoreSettingsPreferences(preferences, saved)
        }
        for ((preferences, saved) in snapshots) {
            assertEquals(saved, snapshotSettingsPreferences(preferences))
        }
    }

    @Test
    fun firstBackupAndExplicitlyEmptyListBothSurviveRestore() {
        val defaults = listOf(Resolution(1280, 720), Resolution(1920, 1080),
            Resolution(2560, 1440), Resolution(3840, 2160))
        val manager = ConfigurationSyncManager(context)
        for (expected in listOf(defaults, emptyList())) {
            assertTrue(custom.edit().clear().commit())
            if (expected.isEmpty()) {
                // An older saved empty list may have no migration metadata.
                assertTrue(custom.edit().putStringSet(STORED_VALUES_KEY, emptySet()).commit())
            }
            val backup = JSONObject(manager.exportSyncPackage())
            val section = backup.getJSONObject("sections").getJSONObject("customResolutions")
            backup.put("sections", JSONObject().put("customResolutions", section))
            CustomResolutionsStore.save(context, listOf(Resolution(2340, 1080)))
            manager.importSyncPackage(backup.toString())
            assertEquals(expected, CustomResolutionsStore.load(context))
        }
    }

    @Test
    fun legacyLowPresetsMigrateOnceAndOldBackupsStillImportAfterMigration() {
        assertTrue(custom.edit().putStringSet(STORED_VALUES_KEY, setOf("2340x1080"))
            .putInt(MIGRATION_KEY, 1).commit())
        assertTrue(global.edit().putBoolean(LEGACY_LOW_PRESETS_KEY, true).commit())
        val migrated = listOf("640x360", "854x480", "2340x1080")
        assertEquals(migrated, CustomResolutionsStore.load(context).map(Resolution::toString))
        assertFalse(global.contains(LEGACY_LOW_PRESETS_KEY))
        CustomResolutionsStore.save(context, emptyList())
        assertTrue(CustomResolutionsStore.load(context).isEmpty())

        val manager = ConfigurationSyncManager(context)
        val backup = JSONObject(manager.exportSyncPackage())
        backup.put("sections", JSONObject()
            .put("defaultPreferences", section(JSONObject().put(LEGACY_LOW_PRESETS_KEY,
                encoded("boolean", true))))
            .put("customResolutions", section(JSONObject()
                .put(STORED_VALUES_KEY, encoded("stringSet", JSONArray().put("2340x1080")))
                .put(MIGRATION_KEY, encoded("int", 1)))))
        manager.importSyncPackage(backup.toString())
        assertEquals(migrated, CustomResolutionsStore.load(context).map(Resolution::toString))
        assertFalse(global.contains(LEGACY_LOW_PRESETS_KEY))
    }

    @Test
    fun missingBackupListPreservesLocalValuesButAnEmptyArrayReplacesThem() {
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
        const val STORED_VALUES_KEY = "custom_resolutions"
        const val MIGRATION_KEY = "migration_version"
        const val LEGACY_LOW_PRESETS_KEY = PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING
    }
}
