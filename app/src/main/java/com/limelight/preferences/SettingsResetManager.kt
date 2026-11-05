package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.limelight.R
import com.limelight.binding.input.virtual_controller.OscSettingsButtonStore
import com.limelight.utils.ConfigurationSyncManager
import com.limelight.utils.ConfigurationSyncScheduler
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** Resets preferences, never databases, pairing material, credentials or saved profile libraries. */
internal object SettingsResetManager {
    private val resetting = AtomicBoolean(false)
    val isResetting: Boolean get() = resetting.get()

    suspend fun reset(context: Context) {
        check(resetting.compareAndSet(false, true)) { "A settings reset is already running" }
        val appContext = context.applicationContext
        try {
            withContext(Dispatchers.IO) {
                val resourceKeys = resourcePreferenceKeys(appContext)
                val sync = ConfigurationSyncManager(appContext)
                sync.withExclusiveSettingsMutation {
                    // Track existing values before removing them so the next sync can export
                    // deletions too, even for preferences that have never been backed up.
                    sync.trackResettablePreferences()
                    resetOverrides(
                        PreferenceManager.getDefaultSharedPreferences(appContext),
                        resourceKeys
                    ) { appContext.getSharedPreferences(it, Context.MODE_PRIVATE) }
                    PreferenceConfiguration.initializeDeviceDefaults(appContext)
                    commit(PreferenceManager.getDefaultSharedPreferences(appContext).edit())
                    sync.trackResettablePreferences()
                }
            }
        } finally {
            // cancel() owns main-thread observers as well as the alarm. The switches are already
            // off before the lock is released, so a queued sync cannot start another import.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                try {
                    ConfigurationSyncScheduler.cancel(appContext)
                } finally {
                    resetting.set(false)
                }
                if (ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
                    ConfigurationSyncScheduler.runNow(appContext)
                }
            }
        }
    }

    internal fun resourcePreferenceKeys(context: Context): Set<String> = buildSet {
        // Read the complete resource, not a screen filtered by search, mode or device capability.
        context.resources.getXml(R.xml.preferences).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val name = parser.name.substringAfterLast('.')
                if (name == "PreferenceCategory" || name == "PreferenceScreen") continue
                if (!parser.getAttributeBooleanValue(ANDROID_NS, "persistent", true) ||
                    !parser.getAttributeBooleanValue(APP_NS, "persistent", true)
                ) continue
                parser.getAttributeValue(ANDROID_NS, "key")?.let(::add)
            }
        }
    }

    internal fun resetOverrides(
        defaults: SharedPreferences,
        resourceKeys: Set<String>,
        preferences: (String) -> SharedPreferences
    ) {
        val editor = defaults.edit()
        (resourceKeys + EXTRA_DEFAULT_KEYS + PreferenceConfiguration.nonXmlResetPreferenceKeys +
            LegacySettingsModeStore.modeKeys)
            .filterNot(::isPreservedDefaultKey)
            .forEach(editor::remove)
        // Keep sync identity, baseline, deletion history, directory grant and password. Disabling
        // all three switches also covers the retired standalone automatic-snapshot settings.
        SYNC_SWITCHES.forEach { editor.putBoolean(it, false) }
        commit(editor)
        STORES_TO_CLEAR.forEach { commit(preferences(it).edit().clear()) }
        STORE_KEYS_TO_REMOVE.forEach { (name, keys) ->
            val storeEditor = preferences(name).edit()
            keys.forEach(storeEditor::remove)
            commit(storeEditor)
        }
    }

    private fun commit(editor: SharedPreferences.Editor) {
        if (!editor.commit()) throw IOException("Unable to save reset settings")
    }

    private fun isPreservedDefaultKey(key: String): Boolean =
        ConfigurationSyncManager.isConfigSyncMetadataPreferenceKey(key) ||
            key.startsWith("pref_developer_features_") || key.startsWith("pref_framegen_star_") ||
            key == BackgroundSource.KEY_LOCAL_PATH || key == BackgroundSource.KEY_DIALOG_SHOWN

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val APP_NS = "http://schemas.android.com/apk/res-auto"

    private val SYNC_SWITCHES = setOf(
        ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED,
        ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED,
        ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED
    )

    // Preferences written outside preferences.xml, plus aliases consumed by legacy migrations.
    // Saved profile data and authorization state must never be added here.
    private val EXTRA_DEFAULT_KEYS = setOf(
        BackgroundSource.KEY_SOURCE,
        BackgroundSource.KEY_API_URL,
        "background_image_type",
        "current_config_id",
        "crown_alignment_snap_enabled"
    )

    private val STORES_TO_CLEAR = setOf(
        "app_last_settings",
        TouchpadInputPreferences.FILE_NAME,
        "performance_overlay",
        "jitter_monitor_overlay",
        "FloatBallPrefs",
        "microphone_button_position",
        "microphone_initial_state",
        "keyboard_settings",
        "game_menu_prefs",
        OscSettingsButtonStore.PREFERENCES_NAME
    )

    private val STORE_KEYS_TO_REMOVE = mapOf(
        "AppTheme" to setOf("theme_mode", "accent_mode"),
        "AppView" to setOf("app_background_mode"),
        TouchPointerPresetPreferences.FILE_NAME to setOf(TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY),
        TouchpadInputPreferences.PRESETS_FILE_NAME to setOf(TouchPointerPresetPreferences.ACTIVE_PRESET_ID_KEY)
    )
}
