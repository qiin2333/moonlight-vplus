package com.limelight.preferences

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.utils.ConfigurationSyncManager
import com.limelight.utils.ConfigurationSyncScheduler
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsResetManagerTest {
    @Test
    fun partialFailureRefreshIsConsumedOnceAndTheResetCanBeRetried() = isolated { context ->
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        defaults.edit().putString("list_resolution", "3840x2160")
            .putString(DeveloperUnlockSettings.PREF_ACCESS_TOKEN, "saved-authorization").commit()
        context.prefs("app_last_settings").edit().putString("host_game", "saved-settings").commit()
        context.failNextCommitForStore = "app_last_settings"
        val application = object : Application() {
            init { attachBaseContext(context) }
            override fun getApplicationContext(): Context = context
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = SettingsResetViewModel(application)
        val store = ViewModelStore().apply { put("reset", model) }
        try {
            instrumentation.runOnMainSync {
                assertFalse(model.consumeFailure())
                model.reset()
            }
            runBlocking { withTimeout(5_000L) { model.state.first { it == SettingsResetViewModel.State.FAILED } } }
            // The default store committed before the later store failed: the host must refresh.
            assertFalse(defaults.contains("list_resolution"))
            assertEquals("saved-settings", context.prefs("app_last_settings").getString("host_game", null))
            instrumentation.runOnMainSync {
                assertTrue(model.consumeFailure())
                assertFalse("A restarted observer must not refresh again", model.consumeFailure())
                assertEquals(SettingsResetViewModel.State.FAILED, model.state.value)
                model.reset()
            }
            runBlocking { withTimeout(5_000L) { model.state.first { it == SettingsResetViewModel.State.COMPLETE } } }
            instrumentation.runOnMainSync { assertFalse(model.consumeFailure()) }
            assertEquals("saved-authorization", defaults.getString(DeveloperUnlockSettings.PREF_ACCESS_TOKEN, null))
            assertTrue(context.prefs("app_last_settings").all.isEmpty())
            assertSyncDisabled(defaults)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test
    fun resetMatchesFreshDeviceDefaultsAndPreservesUserData() = isolated { context ->
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        defaults.edit()
            .putString("list_resolution", "3840x2160").putString("list_fps", "240")
            .putInt("seekbar_bitrate_kbps", 100000).putString("list_resolution_fps", "4K60")
            .putBoolean("checkbox_51_surround", true).putBoolean("checkbox_disable_frame_drop", true)
            .putBoolean("checkbox_vibrate_fallback", true).putBoolean("checkbox_enable_hdr", true)
            .putBoolean("checkbox_enable_mic", true).putFloat("gyro_sensitivity_multiplier", 3f)
            .putBoolean("checkbox_show_QuickKeyCard", true)
            .putBoolean("checkbox_enable_native_mouse_pointer", true)
            .putBoolean("gamepad_motion_sensors_android12_opt_in_migrated", true)
            .putBoolean("gamepad_motion_sensors_android12_opt_in_confirmed", true)
            .putString("string_screen_ds5_touchpad_zone_rect", "0.1,0.1,0.3,0.3")
            .putString("background_source", "api").putString("background_image_type", "api")
            .putLong("current_config_id", 82L)
            .putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, true)
            .putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, true)
            .putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, true)
            .putString(ConfigurationSyncManager.PREF_EXTERNAL_SYNC_TREE_URI, "content://saved-directory")
            .putString("config_sync_device_id", "existing-device")
            .putString("config_sync_last_external_content_hash", "existing-baseline")
            .putString(DeveloperUnlockSettings.PREF_ACCESS_TOKEN, "saved-authorization")
            .putBoolean(DeveloperUnlockSettings.PREF_UNLOCKED, true)
            .putString("pref_framegen_star_access_token", "legacy-authorization")
            .putString(BackgroundSource.KEY_LOCAL_PATH, "/saved/background.png")
            .commit()
        val preservedDefaultKeys = setOf(
            ConfigurationSyncManager.PREF_EXTERNAL_SYNC_TREE_URI, "config_sync_device_id",
            "config_sync_last_external_content_hash", DeveloperUnlockSettings.PREF_ACCESS_TOKEN,
            DeveloperUnlockSettings.PREF_UNLOCKED, "pref_framegen_star_access_token",
            BackgroundSource.KEY_LOCAL_PATH
        )
        val preservedDefaults = defaults.all.filterKeys { it in preservedDefaultKeys }
        val libraryStores = listOf("pair_name_map", "custom_resolutions", "SceneConfigs", "HiddenApps",
            "OSC", "custom_special_keys", "quick_button_config", "config_sync_secrets")
        libraryStores.forEach { context.prefs(it).edit().putString("saved-data", "keep-$it").commit() }
        listOf(TouchPointerPresetPreferences.FILE_NAME, TouchpadInputPreferences.PRESETS_FILE_NAME).forEach {
            context.prefs(it).edit().putString(TouchPointerPresetPreferences.JSON_KEY, "saved-presets")
                .putStringSet(TouchPointerPresetPreferences.DELETED_IDS_KEY, setOf("old-preset"))
                .putString(TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY, "selected").commit()
        }
        val clearedStores = listOf("app_last_settings", "touchpad_input", "performance_overlay",
            "jitter_monitor_overlay", "FloatBallPrefs", "microphone_button_position",
            "microphone_initial_state", "keyboard_settings", "game_menu_prefs", "osc_settings_button")
        clearedStores.forEach { context.prefs(it).edit().putString("old-setting", "changed").commit() }
        context.prefs("AppTheme").edit().putString("theme_mode", "dark")
            .putString("accent_mode", "bg").putInt("derived-color", 123).commit()
        context.prefs("AppView").edit().putString("app_background_mode", "acrylic").commit()
        val pairingFile = File(context.filesDir, "client.pem").apply { writeText("saved-pairing-key") }
        val profileDatabase = context.getDatabasePath("profiles.db").apply { writeText("saved-profiles") }

        val baseline = IsolatedContext(context.baseContext)
        try {
            PreferenceConfiguration.initializeDeviceDefaults(baseline)
            val expected = PreferenceConfiguration.readPreferences(baseline)
            runBlocking { SettingsResetManager.reset(context) }
            assertConfigurationEquals(expected, PreferenceConfiguration.readPreferences(context))
            val baselineDefaults = PreferenceManager.getDefaultSharedPreferences(baseline).all
            listOf("gamepad_motion_sensors_android12_opt_in_migrated",
                "gamepad_motion_sensors_android12_opt_in_confirmed",
                "string_screen_ds5_touchpad_zone_rect").forEach {
                assertEquals("Device default differs for $it", baselineDefaults[it], defaults.all[it])
            }
            assertEquals(preservedDefaults, defaults.all.filterKeys { it in preservedDefaultKeys })
            libraryStores.forEach { assertEquals("keep-$it", context.prefs(it).getString("saved-data", null)) }
            assertEquals("saved-pairing-key", pairingFile.readText())
            assertEquals("saved-profiles", profileDatabase.readText())
            clearedStores.forEach { assertTrue("Store was not reset: $it", context.prefs(it).all.isEmpty()) }
            assertFalse(defaults.contains("current_config_id"))
            assertFalse(defaults.contains("list_resolution_fps"))
            assertFalse(defaults.contains("checkbox_show_QuickKeyCard"))
            assertEquals(BackgroundSource.Auto, BackgroundSource.current(context))
            assertSyncDisabled(defaults)
            listOf(TouchPointerPresetPreferences.FILE_NAME, TouchpadInputPreferences.PRESETS_FILE_NAME).forEach {
                assertEquals("saved-presets", context.prefs(it).getString(TouchPointerPresetPreferences.JSON_KEY, null))
                assertEquals(setOf("old-preset"), context.prefs(it).getStringSet(TouchPointerPresetPreferences.DELETED_IDS_KEY, null))
                assertFalse(context.prefs(it).contains(TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY))
            }
            assertEquals(123, context.prefs("AppTheme").getInt("derived-color", 0))
            assertFalse(context.prefs("AppTheme").contains("theme_mode"))
            runBlocking { SettingsResetManager.reset(context) }
            assertConfigurationEquals(expected, PreferenceConfiguration.readPreferences(context))
            assertSyncDisabled(defaults)
        } finally {
            baseline.close()
        }
    }

    @Test
    fun resetWaitsForAnActiveSyncAndClearsItsImportedValues() = isolated { context ->
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        runBlocking(Dispatchers.IO) {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sync = launch {
                ConfigurationSyncManager(context).withExclusiveSettingsMutation {
                    held.complete(Unit)
                    runBlocking { release.await() }
                    defaults.edit().putString("list_resolution", "3840x2160").commit()
                }
            }
            held.await()
            val reset = async { SettingsResetManager.reset(context) }
            try {
                val busy = runCatching {
                    ConfigurationSyncManager(context).withExclusiveSettingsMutation(lockTimeoutMillis = 0L) {
                        fail("A busy lock must not run the action")
                    }
                }
                assertTrue("Only lock acquisition should time out", busy.exceptionOrNull() is IOException)
                delay(150L)
                assertFalse("Reset must wait for the sync lock", reset.isCompleted)
            } finally {
                release.complete(Unit)
            }
            sync.join()
            reset.await()
        }
        assertEquals(1920, PreferenceConfiguration.readPreferences(context).width)
        assertSyncDisabled(defaults)
    }

    @Test
    fun acquiringTheLockBeforeTheDeadlineDoesNotTimeOutTheAction() = isolated { context ->
        runBlocking(Dispatchers.IO) {
            var applied = false
            ConfigurationSyncManager(context).withExclusiveSettingsMutation(lockTimeoutMillis = 10L) {
                Thread.sleep(40L)
                applied = true
            }
            assertTrue(applied)
        }
    }

    @Test
    fun resetRetainsSyncHistoryAndRecordsDeletionsForTheNextSync() = isolated { context ->
        val last = context.prefs("app_last_settings")
        last.edit().putString("host_game", "remembered-settings").commit()
        val sync = ConfigurationSyncManager(context)
        sync.trackResettablePreferences()
        val state = context.prefs("config_sync_value_state")
        val keyHash = MessageDigest.getInstance("SHA-256").digest("host_game".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val prefix = "appLastSettings:$keyHash"
        val oldHash = state.getString("$prefix.hash", null)
        val oldTimestamp = state.getLong("$prefix.updatedAt", 0L)
        assertNotNull(oldHash)
        state.edit().putString("unrelated-saved-profile-history", "keep").commit()
        runBlocking { SettingsResetManager.reset(context) }
        assertTrue(last.all.isEmpty())
        assertNotEquals(oldHash, state.getString("$prefix.hash", null))
        assertTrue(state.getLong("$prefix.updatedAt", 0L) > oldTimestamp)
        assertTrue(state.getStringSet("appLastSettings.trackedKeys", emptySet())!!.contains("host_game"))
        assertEquals("keep", state.getString("unrelated-saved-profile-history", null))
        val afterReset = state.all
        sync.trackResettablePreferences()
        assertEquals("Exporting again must not replace the deletion timestamp", afterReset, state.all)
    }

    private fun assertSyncDisabled(defaults: SharedPreferences) {
        listOf(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED,
            ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED,
            ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED).forEach {
            assertFalse("Sync switch must be explicitly off: $it", defaults.getBoolean(it, true))
        }
    }

    private fun assertConfigurationEquals(expected: PreferenceConfiguration, actual: PreferenceConfiguration) {
        PreferenceConfiguration::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
            it.isAccessible = true
            assertEquals("Default differs for ${it.name}", it.get(expected), it.get(actual))
        }
    }

    private fun isolated(block: (IsolatedContext) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = IsolatedContext(base)
        try {
            block(context)
        } finally {
            context.close()
            // The real application's observer is process-wide; restore its original scheduling.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ConfigurationSyncScheduler.runNow(base) }
        }
    }

    /** Every preference and file uses a private namespace; no real pairing/profile data is touched. */
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        var failNextCommitForStore: String? = null
        private val namespace = "settings-reset-test-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, namespace).apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$namespace.$name"
            preferenceNames.add(isolatedName)
            val preferences = baseContext.getSharedPreferences(isolatedName, mode)
            if (name != failNextCommitForStore) return preferences
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun clear(): SharedPreferences.Editor {
                            editor.clear()
                            return this
                        }
                        override fun commit(): Boolean {
                            failNextCommitForStore = null
                            return false
                        }
                    }
                }
            }
        }
        fun prefs(name: String): SharedPreferences = getSharedPreferences(name, Context.MODE_PRIVATE)
        fun close() {
            preferenceNames.forEach {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    baseContext.deleteSharedPreferences(it)
                } else {
                    baseContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
            directory.deleteRecursively()
        }
    }
}
