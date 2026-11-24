package com.limelight.preferences

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSearchResumeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings by lazy { SettingsNavigationTestDriver(compose) }
    private lateinit var preferences: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>
    private var configId = -1L

    @Before fun setUp() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalPreferences = snapshotSettingsPreferences(preferences)
        SuperConfigDatabaseHelper(context).use { db ->
            configId = System.currentTimeMillis()
            while (configId in db.queryAllConfigIds()) configId++
            db.insertConfig(ContentValues().apply {
                put("config_id", configId)
                put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "SearchOldCrownName")
            })
        }
        preferences.edit().putLong("current_config_id", configId).commit()
        settings.launch()
    }

    @After fun tearDown() {
        settings.close()
        restoreSettingsPreferences(preferences, originalPreferences)
        if (configId >= 0) SuperConfigDatabaseHelper(context).use { it.deleteConfig(configId) }
    }

    @Test fun returningAfterCrownRenameRecalculatesSearchMatches() {
        val preferenceKey = "checkbox_show_onscreen_keyboard"
        settings.search("SearchOldCrownName")
        compose.waitUntil(5_000) { preferenceKey in settings.adapterKeys() }

        settings.moveToState(Lifecycle.State.CREATED)
        SuperConfigDatabaseHelper(context).use { db ->
            db.updateConfig(configId, ContentValues().apply {
                put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "SearchNewCrownName")
            })
        }
        settings.moveToState(Lifecycle.State.RESUMED)
        settings.awaitScreen(null)
        compose.waitUntil(5_000) { preferenceKey !in settings.adapterKeys() }

        settings.search("SearchNewCrownName")
        compose.waitUntil(5_000) { preferenceKey in settings.adapterKeys() }
    }
}
