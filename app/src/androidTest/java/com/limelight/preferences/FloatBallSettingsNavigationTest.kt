package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.widget.SeekBar
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.fragment.app.DialogFragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FloatBallSettingsNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Suppress("unused")
    private val desugarCollectionsCompatibility = Collections.synchronizedMap(mutableMapOf<Any, Any>())

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings by lazy { SettingsNavigationTestDriver(compose) }
    private lateinit var preferences: SharedPreferences
    private lateinit var positionPreferences: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>
    private lateinit var originalPositionPreferences: Map<String, *>

    @Before
    fun prepareSettings() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        positionPreferences = context.getSharedPreferences("FloatBallPrefs", Context.MODE_PRIVATE)
        originalPreferences = snapshotSettingsPreferences(preferences)
        originalPositionPreferences = snapshotSettingsPreferences(positionPreferences)
        preferences.edit()
            .putBoolean(ENABLE_KEY, true)
            .putString(POSITION_KEY, "center_right")
            .putInt(AUTO_HIDE_KEY, 2000)
            .putString(SINGLE_CLICK_KEY, "open_keyboard")
            .putString(DOUBLE_CLICK_KEY, "open_menu")
            .putString(LONG_CLICK_KEY, "toggle_visibility")
            .commit()
        positionPreferences.edit().clear().commit()
        settings.launch()
    }

    @After
    fun restoreSettings() {
        settings.close()
        restoreSettingsPreferences(preferences, originalPreferences)
        restoreSettingsPreferences(positionPreferences, originalPositionPreferences)
    }

    @Test
    fun mainEntryOpensTheSamePreferenceTreeAndControllerReturnRefreshesSummaryAndFocus() {
        var originalScreen: PreferenceScreen? = null
        settings.withFragment { fragment ->
            originalScreen = requireNotNull(fragment.findPreference<PreferenceScreen>(SCREEN_KEY))
            CHILD_KEYS.forEach { key ->
                assertNotNull("Missing nested setting: $key", originalScreen?.findPreference<Preference>(key))
            }
        }
        settings.search(SCREEN_KEY)
        assertMainEntryContainsNoChildren()
        settings.focusPreference(SCREEN_KEY)
        val previousSummary = settings.summary(SCREEN_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)

        settings.awaitScreen(SCREEN_KEY)
        settings.withFragment { fragment ->
            assertSame("Navigation must retain the original PreferenceManager tree",
                originalScreen, fragment.findPreference<PreferenceScreen>(SCREEN_KEY))
        }
        settings.focusPreference(ENABLE_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitUntil(5_000) { !preferences.getBoolean(ENABLE_KEY, true) }
        settings.withFragment { fragment ->
            CHILD_KEYS.filter { it != ENABLE_KEY }.forEach { key ->
                assertFalse("Disabled floating ball must disable $key",
                    requireNotNull(fragment.findPreference<Preference>(key)).isEnabled)
            }
        }
        settings.press(KeyEvent.KEYCODE_BUTTON_B)

        settings.awaitScreen(null)
        settings.assertFocusedPreference(SCREEN_KEY)
        assertEquals(SCREEN_KEY, settings.query())
        compose.waitUntil(5_000) { settings.summary(SCREEN_KEY) != previousSummary }
        assertNotEquals(previousSummary, settings.summary(SCREEN_KEY))
        assertMainEntryContainsNoChildren()
    }

    @Test
    fun searchingNestedTitleOrActionOptionFocusesItsRowWithoutOpeningItsSheet() {
        val queries = mutableListOf<String>()
        settings.withFragment { fragment ->
            val preference = requireNotNull(fragment.findPreference<ListPreference>(SINGLE_CLICK_KEY))
            queries += preference.title.toString()
            val optionIndex = preference.findIndexOfValue("show_perf_overlay")
            assertTrue("The search fixture must be a visible action option", optionIndex >= 0)
            queries += preference.entries[optionIndex].toString()
        }
        for (query in queries) {
            settings.search(query)
            assertMainEntryContainsNoChildren()
            settings.focusPreference(SCREEN_KEY)
            settings.press(KeyEvent.KEYCODE_BUTTON_A)

            settings.awaitScreen(SCREEN_KEY)
            settings.assertFocusedPreference(SINGLE_CLICK_KEY)
            // One B must close the detail itself, proving no choice sheet opened automatically.
            settings.press(KeyEvent.KEYCODE_BUTTON_B)
            settings.awaitScreen(null)
            assertEquals(query, settings.query())
            settings.assertFocusedPreference(SCREEN_KEY)
        }
    }

    @Test
    fun cancellingPositionPreservesDragOverrideAndPresetConfirmationUpdatesTheMainSummary() {
        positionPreferences.edit()
            .putInt("positionVersion", 2)
            .putFloat("normalizedX", 0.2f)
            .putFloat("normalizedY", 0.7f)
            .putString("edge", "left")
            .putBoolean("isHalfShow", true)
            .commit()
        val dragOverride = snapshotSettingsPreferences(positionPreferences)
        settings.openScreen(SCREEN_KEY, POSITION_KEY)
        settings.assertFocusedPreference(POSITION_KEY)
        val labels = openList(POSITION_KEY, "center_right", "bottom_left")
        repeat(labels.distance) { settings.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(labels.target).assertIsFocused().assertIsNotSelected()
        compose.onNodeWithText(labels.current).assertIsSelected()
        settings.press(KeyEvent.KEYCODE_BUTTON_B)

        assertEquals("center_right", preferences.getString(POSITION_KEY, null))
        assertEquals(dragOverride, snapshotSettingsPreferences(positionPreferences))
        settings.assertFocusedPreference(POSITION_KEY)
        openList(POSITION_KEY, "center_right", "bottom_left")
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("center_right", preferences.getString(POSITION_KEY, null))
        assertTrue("Confirming the current preset must discard the custom drag position", positionPreferences.all.isEmpty())
        settings.assertFocusedPreference(POSITION_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_B)
        settings.awaitScreen(null)
        val previousSummary = settings.summary(SCREEN_KEY)

        settings.openScreen(SCREEN_KEY, POSITION_KEY)
        openList(POSITION_KEY, "center_right", "bottom_left")
        repeat(labels.distance) { settings.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("bottom_left", preferences.getString(POSITION_KEY, null))
        settings.press(KeyEvent.KEYCODE_BUTTON_B)
        settings.awaitScreen(null)
        compose.waitUntil(5_000) { settings.summary(SCREEN_KEY) != previousSummary }
        assertTrue("The entry must describe the newly selected position", settings.summary(SCREEN_KEY).contains(labels.target))
    }

    @Test
    fun confirmedActionAndCurrentChoiceSurviveRotationInsideTheSameSettingsActivity() {
        settings.openScreen(SCREEN_KEY, SINGLE_CLICK_KEY)
        settings.assertFocusedPreference(SINGLE_CLICK_KEY)
        val labels = openList(SINGLE_CLICK_KEY, "open_keyboard", "open_menu")
        repeat(labels.distance) { settings.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(labels.target).assertIsFocused()
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("open_menu", preferences.getString(SINGLE_CLICK_KEY, null))
        settings.assertFocusedPreference(SINGLE_CLICK_KEY)

        settings.rotate()

        settings.awaitScreen(SCREEN_KEY)
        assertEquals("open_menu", preferences.getString(SINGLE_CLICK_KEY, null))
        settings.withFragment { fragment ->
            assertEquals("open_menu", requireNotNull(fragment.findPreference<ListPreference>(SINGLE_CLICK_KEY)).value)
        }
        settings.focusPreference(SINGLE_CLICK_KEY)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        awaitFocusedChoice(labels.target)
        compose.onNodeWithText(labels.target).assertIsDisplayed().assertIsFocused().assertIsSelected()
        settings.press(KeyEvent.KEYCODE_BUTTON_B)
        settings.assertFocusedPreference(SINGLE_CLICK_KEY)
    }

    @Test
    @SuppressLint("DiscouragedApi")
    fun cancellingAutoHideDelayDialogReturnsToItsDetailRowWithoutSaving() {
        settings.openScreen(SCREEN_KEY, AUTO_HIDE_KEY)
        for (cancelKey in listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE)) {
            settings.focusPreference(AUTO_HIDE_KEY)
            settings.press(KeyEvent.KEYCODE_BUTTON_A)
            compose.waitUntil(5_000) {
                var focused = false
                settings.withActivity { activity ->
                    val dialog = (activity.supportFragmentManager
                        .findFragmentByTag("SeekBarPreference") as? DialogFragment)?.dialog
                    val seekbarId = activity.resources.getIdentifier("pref_seekbar", "id", activity.packageName)
                    focused = dialog?.isShowing == true &&
                        dialog.findViewById<SeekBar>(seekbarId)?.hasFocus() == true
                }
                focused
            }
            settings.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            settings.withActivity { activity ->
                val dialog = requireNotNull((activity.supportFragmentManager
                    .findFragmentByTag("SeekBarPreference") as? DialogFragment)?.dialog)
                val seekbarId = activity.resources.getIdentifier("pref_seekbar", "id", activity.packageName)
                assertEquals("Controller input must change the temporary delay", 2500,
                    requireNotNull(dialog.findViewById<SeekBar>(seekbarId)).progress)
            }
            assertEquals("Browsing the delay must not save it", 2000, preferences.getInt(AUTO_HIDE_KEY, -1))

            settings.press(cancelKey)

            settings.awaitScreen(SCREEN_KEY)
            settings.assertFocusedPreference(AUTO_HIDE_KEY)
            assertEquals("Cancelling must retain the original delay", 2000, preferences.getInt(AUTO_HIDE_KEY, -1))
        }

        settings.press(KeyEvent.KEYCODE_BUTTON_B)

        settings.awaitScreen(null)
        settings.assertFocusedPreference(SCREEN_KEY)
    }

    private fun assertMainEntryContainsNoChildren() {
        val keys = settings.adapterKeys()
        assertEquals("The main page must expose one floating-ball entry", 1, keys.count { it == SCREEN_KEY })
        CHILD_KEYS.forEach { key -> assertFalse("Nested setting leaked onto the main page: $key", key in keys) }
    }

    private fun openList(key: String, currentValue: String, targetValue: String): ChoiceLabels {
        var labels: ChoiceLabels? = null
        settings.withFragment { fragment ->
            val preference = requireNotNull(fragment.findPreference<ListPreference>(key))
            val currentIndex = preference.findIndexOfValue(currentValue)
            val targetIndex = preference.findIndexOfValue(targetValue)
            assertTrue("Both controller choices must remain available", currentIndex >= 0 && targetIndex > currentIndex)
            labels = ChoiceLabels(preference.entries[currentIndex].toString(),
                preference.entries[targetIndex].toString(), targetIndex - currentIndex)
        }
        settings.focusPreference(key)
        settings.press(KeyEvent.KEYCODE_BUTTON_A)
        awaitFocusedChoice(requireNotNull(labels).current)
        compose.onNodeWithText(requireNotNull(labels).current).assertIsDisplayed().assertIsFocused().assertIsSelected()
        return requireNotNull(labels)
    }

    private fun awaitFocusedChoice(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes(atLeastOneRootRequired = false).any {
                it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused]
            }
        }
    }

    private data class ChoiceLabels(val current: String, val target: String, val distance: Int)

    private companion object {
        private const val SCREEN_KEY = "settings_float_ball"
        private const val ENABLE_KEY = "checkbox_enable_float_ball"
        private const val POSITION_KEY = "list_float_ball_position"
        private const val AUTO_HIDE_KEY = "seekbar_float_ball_auto_hide_delay"
        private const val SINGLE_CLICK_KEY = "list_float_ball_single_click_action"
        private const val DOUBLE_CLICK_KEY = "list_float_ball_double_click_action"
        private const val LONG_CLICK_KEY = "list_float_ball_long_click_action"
        private val CHILD_KEYS = listOf(ENABLE_KEY, POSITION_KEY, AUTO_HIDE_KEY,
            SINGLE_CLICK_KEY, DOUBLE_CLICK_KEY, LONG_CLICK_KEY)
    }
}
