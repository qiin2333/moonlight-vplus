package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSearchStateTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings by lazy { SettingsNavigationTestDriver(compose) }
    private lateinit var preferences: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>

    @Before
    fun prepareSettings() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalPreferences = snapshotSettingsPreferences(preferences)
        preferences.edit().putBoolean("checkbox_enable_esc_menu", true)
            .putBoolean("checkbox_stretch_video", false).commit()
        settings.launch()
    }

    @After
    fun restoreSettings() {
        settings.close()
        restoreSettingsPreferences(preferences, originalPreferences)
    }

    @Test
    fun recreationInSearchResultPreservesQueryAndReturnFocus() {
        val screenKey = "settings_input"
        val targetKey = "list_esc_menu_key"
        val resultKey = "settings.search.result.$screenKey.$targetKey"
        val query = targetKey
        settings.search(query)
        settings.focusPreference(resultKey)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        settings.awaitScreen(screenKey)
        settings.assertFocusedPreference(targetKey)

        settings.recreate()
        settings.awaitScreen(screenKey)
        settings.press(KeyEvent.KEYCODE_BUTTON_B)
        settings.awaitScreen(null)
        assertEquals(query, settings.query())
        settings.assertFocusedPreference(resultKey)
    }

    @Test
    fun runtimeVisibilityChangesUpdateDeepResultsAndRestoreAfterSearch() {
        val screenKey = "settings_picture_layout"
        val targetKey = "list_screen_position"
        val resultKey = "settings.search.result.$screenKey.$targetKey"
        settings.search(targetKey)
        assertTrue(resultKey in settings.adapterKeys())

        settings.withFragment { fragment ->
            requireNotNull(fragment.findPreference<CheckBoxPreference>("checkbox_stretch_video")).isChecked = true
        }
        compose.waitUntil(5_000) { resultKey !in settings.adapterKeys() }
        settings.withFragment { fragment ->
            assertFalse(requireNotNull(fragment.findPreference<Preference>(targetKey)).isVisible)
            requireNotNull(fragment.findPreference<CheckBoxPreference>("checkbox_stretch_video")).isChecked = false
        }
        compose.waitUntil(5_000) { resultKey in settings.adapterKeys() }
        settings.search("")
        settings.withFragment { fragment ->
            assertTrue(requireNotNull(fragment.findPreference<Preference>(targetKey)).isVisible)
        }
        assertFalse(resultKey in settings.adapterKeys())
    }
}
