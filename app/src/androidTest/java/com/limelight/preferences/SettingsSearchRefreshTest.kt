package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.text.NumberFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSearchRefreshTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings by lazy { SettingsNavigationTestDriver(compose) }
    private lateinit var preferences: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>

    @Before fun launch() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalPreferences = snapshotSettingsPreferences(preferences)
        preferences.edit().putBoolean("checkbox_enable_float_ball", true)
            .putInt(DELAY_KEY, 3500).commit()
        settings.launch()
    }

    @After fun close() {
        settings.close()
        restoreSettingsPreferences(preferences, originalPreferences)
    }

    @Test fun changedCurrentValueRefreshesResultsWhileDetailIsOpen() {
        val oldValue = NumberFormat.getNumberInstance().format(3.5)
        val newValue = NumberFormat.getNumberInstance().format(4.5)
        settings.search(oldValue)
        compose.waitUntil(5_000) { DELAY_RESULT in settings.adapterKeys() }
        settings.focusPreference(DELAY_RESULT)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        settings.awaitScreen(FLOAT_SCREEN)
        settings.withFragment { fragment ->
            requireNotNull(fragment.findPreference<SeekBarPreference>(DELAY_KEY)).setProgress(4500)
        }
        settings.press(KeyEvent.KEYCODE_BUTTON_B)
        settings.awaitScreen(null)
        compose.waitUntil(5_000) { DELAY_RESULT !in settings.adapterKeys() }

        settings.search(newValue)
        compose.waitUntil(5_000) { DELAY_RESULT in settings.adapterKeys() }
    }

    @Test fun nestedCategoryTitleListsItsVisibleSettings() {
        var title = ""
        var screenTitle = ""
        settings.withFragment { fragment ->
            title = requireNotNull(fragment.findPreference<Preference>("category_settings_gamepad_players"))
                .title.toString()
            screenTitle = requireNotNull(fragment.findPreference<Preference>("settings_gamepad"))
                .title.toString()
        }
        assertTrue(title.isNotBlank())
        settings.search(title)
        val result = resultKey("settings_gamepad", "checkbox_multi_controller")
        compose.waitUntil(5_000) { result in settings.adapterKeys() }
        assertEquals(1, settings.adapterKeys().count { it == result })
        assertEquals("$screenTitle › $title", settings.adapterItems().single { it.key == result }.summary.toString())
        settings.focusPreference(result)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        settings.awaitScreen("settings_gamepad")
        settings.assertFocusedPreference("checkbox_multi_controller")
    }

    private companion object {
        const val FLOAT_SCREEN = "settings_float_ball"
        const val DELAY_KEY = "seekbar_float_ball_auto_hide_delay"
        val DELAY_RESULT = resultKey(FLOAT_SCREEN, DELAY_KEY)
        fun resultKey(screen: String, target: String) = "settings.search.result.$screen.$target"
    }
}
