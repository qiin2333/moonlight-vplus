package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.limelight.utils.AppTheme
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDetailNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Suppress("unused")
    private val desugarCollectionsCompatibility = Collections.synchronizedMap(mutableMapOf<Any, Any>())

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings by lazy { SettingsNavigationTestDriver(compose) }
    private lateinit var preferences: SharedPreferences
    private lateinit var themePreferences: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>
    private lateinit var originalThemePreferences: Map<String, *>

    @Before
    fun prepareSettings() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        themePreferences = context.getSharedPreferences("AppTheme", Context.MODE_PRIVATE)
        originalPreferences = snapshotSettingsPreferences(preferences)
        originalThemePreferences = snapshotSettingsPreferences(themePreferences)
        preferences.edit()
            .putBoolean(AUDIO_PASSTHROUGH_KEY, false)
            .putBoolean(AUDIO_EFFECTS_KEY, true)
            .putBoolean(SPATIALIZER_KEY, true)
            .putBoolean("checkbox_enable_esc_menu", true)
            .commit()
        themePreferences.edit().putString("theme_mode", "light").commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppTheme.applyStoredAppTheme(context) }
        settings.launch()
    }

    @After
    fun restoreSettings() {
        settings.close()
        restoreSettingsPreferences(preferences, originalPreferences)
        restoreSettingsPreferences(themePreferences, originalThemePreferences)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppTheme.applyStoredAppTheme(context) }
    }

    @Test
    fun everyEligibleFeatureHasOneMainEntryAndExposesAllItsVisibleSettingsWithoutExpanders() {
        var screens = emptyList<PreferenceScreen>()
        settings.withFragment { fragment ->
            val root = fragment.preferenceScreen
            screens = featureScreens(root)
            assertEquals("Every feature must remain reachable in the complete preference tree",
                SCREEN_KEYS, screens.map { it.key }.toSet())
            allGroups(root).forEach { group ->
                assertEquals("Collapsed settings remain in ${group.key}", Int.MAX_VALUE,
                    group.initialExpandedChildrenCount)
            }
        }
        val mainItems = settings.adapterItems()
        assertNoExpandButton()
        settings.withFragment { fragment ->
            COMMON_MAIN_SETTING_KEYS.mapNotNull { fragment.findPreference<Preference>(it) }
                .filter(::effectivelyVisible)
                .forEach { preference ->
                    assertEquals("Common setting ${preference.key} must be directly available on the main page",
                        1, mainItems.count { it === preference })
                    assertTrue("Common setting ${preference.key} must remain actionable", preference.isSelectable)
                }
        }
        for (screen in screens) {
            val leaves = visibleLeaves(screen)
            val eligible = effectivelyVisible(screen) && leaves.isNotEmpty()
            assertEquals("Incorrect number of main entries for ${screen.key}", if (eligible) 1 else 0,
                mainItems.count { it === screen })
            descendants(screen).forEach { child ->
                assertFalse("${child.key} leaked out of ${screen.key}", mainItems.any { it === child })
            }
            if (!eligible) continue

            settings.openScreen(screen.key)

            settings.withFragment { fragment ->
                assertSame("Opening a feature must keep its original preference instances",
                    screen, fragment.findPreference<PreferenceScreen>(screen.key))
            }
            assertNoExpandButton()
            val actualLeaves = settings.adapterItems().filter { it !is PreferenceGroup }
            assertEquals("Missing or duplicated settings in ${screen.key}", leaves, actualLeaves)
            settings.press(KeyEvent.KEYCODE_BUTTON_B)
            settings.awaitScreen(null)
        }
    }

    @Test
    fun audioPassthroughUpdatesDependenciesOnItsPageAndTheMainAudioRowsWithoutReplacingTheTree() {
        var effects: Preference? = null
        var spatializer: Preference? = null
        settings.withFragment { fragment ->
            effects = requireNotNull(fragment.findPreference<Preference>(AUDIO_EFFECTS_KEY))
            spatializer = requireNotNull(fragment.findPreference<Preference>(SPATIALIZER_KEY))
            assertTrue(requireNotNull(effects).isEnabled)
            assertTrue(requireNotNull(spatializer).isEnabled)
        }
        settings.focusPreference(AUDIO_SCREEN_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        settings.awaitScreen(AUDIO_SCREEN_KEY)
        settings.focusPreference(AUDIO_PASSTHROUGH_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitUntil(5_000) { preferences.getBoolean(AUDIO_PASSTHROUGH_KEY, false) }

        assertAudioDependencies(passthrough = true)
        settings.press(KeyEvent.KEYCODE_BUTTON_B)
        settings.awaitScreen(null)
        settings.assertFocusedPreference(AUDIO_SCREEN_KEY)
        settings.withFragment { fragment ->
            assertSame(effects, fragment.findPreference<Preference>(AUDIO_EFFECTS_KEY))
            assertSame(spatializer, fragment.findPreference<Preference>(SPATIALIZER_KEY))
            assertFalse(requireNotNull(effects).isEnabled)
            assertFalse(requireNotNull(spatializer).isEnabled)
        }
        assertTrue("Disabling the UI must preserve the saved effects preference", preferences.getBoolean(AUDIO_EFFECTS_KEY, false))
        assertTrue("Disabling the UI must preserve the saved spatializer preference", preferences.getBoolean(SPATIALIZER_KEY, false))

        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        settings.awaitScreen(AUDIO_SCREEN_KEY)
        settings.focusPreference(AUDIO_PASSTHROUGH_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitUntil(5_000) { !preferences.getBoolean(AUDIO_PASSTHROUGH_KEY, true) }
        assertAudioDependencies(passthrough = false)
        settings.press(KeyEvent.KEYCODE_BUTTON_B)
        settings.awaitScreen(null)
        settings.withFragment { fragment ->
            assertTrue(requireNotNull(fragment.findPreference<Preference>(AUDIO_EFFECTS_KEY)).isEnabled)
            assertTrue(requireNotNull(fragment.findPreference<Preference>(SPATIALIZER_KEY)).isEnabled)
        }
    }

    @Test
    fun controllerBackRestoresTheUnfilteredMainListScrollAndEntryFocus() {
        settings.search("")
        settings.focusPreference(INPUT_SCREEN_KEY)
        val mainKeys = settings.adapterKeys()
        val mainViewport = settings.viewport()
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        settings.awaitScreen(INPUT_SCREEN_KEY)
        settings.focusPreference(ESC_MENU_KEY)

        settings.press(KeyEvent.KEYCODE_BUTTON_B)

        settings.awaitScreen(null)
        settings.assertFocusedPreference(INPUT_SCREEN_KEY)
        assertEquals("", settings.query())
        assertEquals(mainKeys, settings.adapterKeys())
        assertEquals("Returning must restore the main list's viewport", mainViewport, settings.viewport())
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun systemBackReturnsFromControllerSettingsBeforeAndAfterActivityRecreation() {
        val mainKeys = settings.adapterKeys()
        for (recreate in listOf(false, true)) {
            settings.focusPreference(GAMEPAD_SCREEN_KEY)
            val mainViewport = settings.viewport()
            settings.press(KeyEvent.KEYCODE_BUTTON_A)
            settings.awaitScreen(GAMEPAD_SCREEN_KEY)
            settings.focusPreference("checkbox_multi_controller")
            if (recreate) settings.recreate()

            settings.pressSystemBack()

            settings.awaitScreen(null)
            settings.assertFocusedPreference(GAMEPAD_SCREEN_KEY)
            assertEquals("System Back must return to the main settings list (recreate=$recreate)",
                mainKeys, settings.adapterKeys())
            assertEquals("System Back must preserve the main list's viewport (recreate=$recreate)",
                mainViewport, settings.viewport())
            settings.withActivity { activity ->
                assertFalse("System Back from details must not finish settings (recreate=$recreate)", activity.isFinishing)
            }
        }
    }

    @Test
    fun searchingADeepSettingFocusesItAndEscapeReturnsToTheOriginalQueryWithoutOpeningADialog() {
        var query = ""
        settings.withFragment { fragment ->
            query = requireNotNull(fragment.findPreference<Preference>(ESC_MENU_KEY)).title.toString()
        }
        settings.search(query)
        val searchKeys = settings.adapterKeys()
        assertTrue("The feature entry must match its deep setting", INPUT_SCREEN_KEY in searchKeys)
        assertFalse("Search must keep deep settings within their feature", ESC_MENU_KEY in searchKeys)
        settings.focusPreference(INPUT_SCREEN_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)

        settings.awaitScreen(INPUT_SCREEN_KEY)
        settings.assertFocusedPreference(ESC_MENU_KEY)
        settings.press(KeyEvent.KEYCODE_ESCAPE)

        // If search opened a dialog automatically, Escape would only close that dialog.
        settings.awaitScreen(null)
        settings.assertFocusedPreference(INPUT_SCREEN_KEY)
        assertEquals(query, settings.query())
        assertEquals(searchKeys, settings.adapterKeys())
    }

    @Test
    fun rotationThemeChangeAndRecreationKeepTheActiveFeatureAndItsReturnDestination() {
        settings.focusPreference(GAMEPAD_SCREEN_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        settings.awaitScreen(GAMEPAD_SCREEN_KEY)
        settings.focusPreference("checkbox_multi_controller")

        settings.rotate()
        settings.awaitScreen(GAMEPAD_SCREEN_KEY)
        settings.withActivity { activity ->
            AppTheme.save(activity, AppTheme.THEME_MODE_DARK, AppTheme.getAccentMode(activity))
        }
        settings.awaitReady { activity ->
            (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        settings.awaitScreen(GAMEPAD_SCREEN_KEY)
        settings.recreate()
        settings.awaitScreen(GAMEPAD_SCREEN_KEY)
        assertTrue("Recreated details must still contain their visible settings",
            "checkbox_multi_controller" in settings.adapterKeys())
        settings.withFragment { fragment ->
            assertNotNull("Other feature preferences must remain in the same manager", fragment.findPreference<Preference>(AUDIO_PASSTHROUGH_KEY))
        }

        settings.press(KeyEvent.KEYCODE_BUTTON_B)

        settings.awaitScreen(null)
        settings.assertFocusedPreference(GAMEPAD_SCREEN_KEY)
        assertEquals("", settings.query())
    }

    private fun assertAudioDependencies(passthrough: Boolean) {
        settings.withFragment { fragment ->
            AUDIO_DEPENDENT_KEYS.forEach { key ->
                assertEquals("Incorrect enabled state for $key", passthrough,
                    requireNotNull(fragment.findPreference<Preference>(key)).isEnabled)
            }
            assertEquals(!passthrough, requireNotNull(fragment.findPreference<Preference>(AUDIO_EFFECTS_KEY)).isEnabled)
            assertEquals(!passthrough, requireNotNull(fragment.findPreference<Preference>(SPATIALIZER_KEY)).isEnabled)
        }
    }

    private fun assertNoExpandButton() {
        assertFalse("Feature navigation must not recreate a More expander",
            settings.adapterItems().any { it.javaClass.name == "androidx.preference.ExpandButton" })
    }

    private fun descendants(group: PreferenceGroup): List<Preference> = buildList {
        for (index in 0 until group.preferenceCount) {
            val child = group.getPreference(index)
            add(child)
            if (child is PreferenceGroup) addAll(descendants(child))
        }
    }

    private fun featureScreens(root: PreferenceGroup): List<PreferenceScreen> =
        descendants(root).filterIsInstance<PreferenceScreen>()

    private fun allGroups(root: PreferenceGroup): List<PreferenceGroup> =
        listOf(root) + descendants(root).filterIsInstance<PreferenceGroup>()

    private fun visibleLeaves(group: PreferenceGroup): List<Preference> = buildList {
        for (index in 0 until group.preferenceCount) {
            val child = group.getPreference(index)
            if (!child.isVisible) continue
            if (child is PreferenceGroup) addAll(visibleLeaves(child)) else add(child)
        }
    }

    private fun effectivelyVisible(preference: Preference): Boolean {
        var current: Preference? = preference
        while (current != null) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return true
    }

    private companion object {
        private const val AUDIO_SCREEN_KEY = "settings_audio_passthrough"
        private const val AUDIO_PASSTHROUGH_KEY = "checkbox_enable_audio_passthrough"
        private const val AUDIO_EFFECTS_KEY = "checkbox_enable_audiofx"
        private const val SPATIALIZER_KEY = "checkbox_enable_spatializer"
        private const val INPUT_SCREEN_KEY = "settings_input"
        private const val ESC_MENU_KEY = "list_esc_menu_key"
        private const val GAMEPAD_SCREEN_KEY = "settings_gamepad"
        private val AUDIO_DEPENDENT_KEYS = listOf("list_audio_codec", "list_audio_passthrough_buffer", "checkbox_ac3_iec61937")
        private val SCREEN_KEYS = setOf(
            "settings_frame_generation", "settings_hdr",
            "settings_picture_layout", "settings_performance_overlay", "settings_float_ball",
            "settings_audio_passthrough", "settings_audio_vibration", "settings_gamepad",
            "settings_input",
        )
        private val COMMON_MAIN_SETTING_KEYS = setOf(
            "checkbox_enable_mic", "list_mic_initial_state", "list_mic_menu_action_mode", "checkbox_show_mic_button",
            "seekbar_mic_bitrate_kbps", "list_mic_button_position", "list_mic_icon_color",
            "list_mic_volume_processing_mode", "seekbar_mic_gain_db", "seekbar_mic_balance_target",
            "checkbox_mic_voice_enhancement",
            "controller_diagnostic", "controller_mouse_settings", "seekbar_deadzone", "stick_calibration",
            "list_native_mouse_mode_preset", "checkbox_touch_keyboard_auto_invoke", "checkbox_remote_ime_auto_show",
            "checkbox_enable_sops", "list_screen_combination_mode", "checkbox_host_audio",
            "list_quit_behavior", "list_background_stream_behavior",
            "checkbox_control_only", "checkbox_lock_screen_after_disconnect", "checkbox_clipboard_sync_text",
            "checkbox_clipboard_sync_image", "checkbox_enable_stun",
            "pref_framegen_pick_lossless_dll", "checkbox_framegen_enabled", "checkbox_framegen_adaptive_enabled",
            "list_framegen_quality_preset", "pref_framegen_selftest", "checkbox_enable_hdr",
        )
    }
}
