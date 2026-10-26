package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SettingsActionSheetSelectionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // ActivityScenario shares StreamSettings' desugared Collections facade.
    @Suppress("unused")
    private val desugarCollectionsCompatibility = Collections.synchronizedMap(mutableMapOf<Any, Any>())

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var preferences: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>
    private var scenario: ActivityScenario<StreamSettings>? = null

    @Before
    fun launchSettings() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalPreferences = snapshotSettingsPreferences(preferences)
        preferences.edit()
            .putString(VIDEO_FORMAT_KEY, "auto")
            .putString(SCREEN_POSITION_KEY, "center")
            .putBoolean("checkbox_stretch_video", false)
            .putBoolean("checkbox_enable_perf_overlay", true)
            .putStringSet(DISPLAY_ITEMS_KEY, ORIGINAL_ITEMS)
            .commit()
        scenario = ActivityScenario.launch(StreamSettings::class.java)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun restoreSettings() {
        scenario?.close()
        restoreSettingsPreferences(preferences, originalPreferences)
    }

    @Test
    fun listControllerBrowsingCanCancelAndReturnToItsSettingsRowWithoutSaving() {
        val labels = openList(VIDEO_FORMAT_KEY, "auto", "neverh265")
        navigateDown(labels.distance)

        compose.onNodeWithText(labels.target).assertIsFocused().assertIsNotSelected()
        compose.onNodeWithText(labels.current).assertIsSelected()
        assertEquals("auto", preferences.getString(VIDEO_FORMAT_KEY, null))
        sendKey(KeyEvent.KEYCODE_BUTTON_B)

        assertEquals("auto", preferences.getString(VIDEO_FORMAT_KEY, null))
        assertListValue(VIDEO_FORMAT_KEY, "auto")
        assertFocusedPreference(VIDEO_FORMAT_KEY)
    }

    @Test
    fun listControllerConfirmationWritesTheChosenValueAndReturnsToItsSettingsRow() {
        val labels = openList(VIDEO_FORMAT_KEY, "auto", "neverh265")
        navigateDown(labels.distance)
        compose.onNodeWithText(labels.target).assertIsFocused()

        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertEquals("neverh265", preferences.getString(VIDEO_FORMAT_KEY, null))
        assertListValue(VIDEO_FORMAT_KEY, "neverh265")
        assertFocusedPreference(VIDEO_FORMAT_KEY)
    }

    @Test
    fun ordinaryListInvokesItsListenerForTheCurrentValueAndHonorsAVetoForANewValue() {
        val callbacks = AtomicInteger(0)
        val proposedValues = mutableListOf<String>()
        withFragment { fragment ->
            requireNotNull(fragment.findPreference<ListPreference>(VIDEO_FORMAT_KEY))
                .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    callbacks.incrementAndGet()
                    proposedValues += newValue.toString()
                    false
                }
        }
        openList(VIDEO_FORMAT_KEY, "auto", "neverh265")

        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertEquals("Confirming the current entry must still notify ordinary listeners", 1, callbacks.get())
        assertEquals(listOf("auto"), proposedValues)
        assertFocusedPreference(VIDEO_FORMAT_KEY)

        val labels = openList(VIDEO_FORMAT_KEY, "auto", "neverh265")
        navigateDown(labels.distance)
        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertEquals(2, callbacks.get())
        assertEquals(listOf("auto", "neverh265"), proposedValues)
        assertEquals("auto", preferences.getString(VIDEO_FORMAT_KEY, null))
        assertListValue(VIDEO_FORMAT_KEY, "auto")
        assertFocusedPreference(VIDEO_FORMAT_KEY)
    }

    @Test
    fun iconListKeepsItsRealIconsAndRadioSelectionWhileWritingTheConfirmedValue() {
        withFragment { fragment ->
            val preference = requireNotNull(fragment.findPreference<IconListPreference>(SCREEN_POSITION_KEY))
            val currentIndex = preference.findIndexOfValue("center")
            val targetIndex = preference.findIndexOfValue("center_right")
            val icons = requireNotNull(preference.entryIcons)
            assertEquals(preference.entries.size, icons.size)
            assertTrue("The selected position must retain its icon", icons[currentIndex] != 0)
            assertTrue("The target position must retain its icon", icons[targetIndex] != 0)
        }
        val labels = openList(SCREEN_POSITION_KEY, "center", "center_right")
        compose.onNodeWithText(labels.current)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        navigateDown(labels.distance)
        compose.onNodeWithText(labels.target).assertIsFocused().assertIsNotSelected()
        compose.onNodeWithText(labels.current).assertIsSelected()

        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertEquals("center_right", preferences.getString(SCREEN_POSITION_KEY, null))
        assertListValue(SCREEN_POSITION_KEY, "center_right")
        assertFocusedPreference(SCREEN_POSITION_KEY)
    }

    @Test
    fun multiSelectControllerChangesStayTemporaryAndCancelDiscardsThem() {
        val labels = openDisplayItems()
        compose.onNodeWithText(labels.current).assertIsOn()
        compose.onNodeWithText(labels.target).assertIsOff()
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(labels.target).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        compose.onNodeWithText(labels.target).assertIsOn()
        assertDisplayItems(ORIGINAL_ITEMS)
        sendKey(KeyEvent.KEYCODE_BUTTON_B)

        assertDisplayItems(ORIGINAL_ITEMS)
        assertFocusedPreference(DISPLAY_ITEMS_KEY)
    }

    @Test
    fun multiSelectCommitsTheFinalSetOnlyAfterExplicitConfirmation() {
        val labels = openDisplayItems()
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(labels.target).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText(labels.target).assertIsOn()
        assertDisplayItems(ORIGINAL_ITEMS)
        var confirmLabel = ""
        requireNotNull(scenario).onActivity { confirmLabel = it.getString(android.R.string.ok) }
        compose.onNodeWithText(confirmLabel).assertIsDisplayed().requestFocus()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertDisplayItems(ORIGINAL_ITEMS + "decoder")
        assertFocusedPreference(DISPLAY_ITEMS_KEY)
    }

    @Test
    fun multiSelectDraftSurvivesRotationWithoutSavingUntilTheRestoredSheetIsConfirmed() {
        val labels = openDisplayItems()
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(labels.target).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText(labels.target).assertIsOn()
        assertDisplayItems(ORIGINAL_ITEMS)

        rotateSettings()

        // API 22 resumes the recreated activity before its posted dialog has attached.
        // A missing Compose root is expected during that window, not a failed assertion.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(labels.target)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithText(labels.current).assertIsOn()
        compose.onNodeWithText(labels.target).assertIsOn()
        assertDisplayItems(ORIGINAL_ITEMS)
        var confirmLabel = ""
        requireNotNull(scenario).onActivity { confirmLabel = it.getString(android.R.string.ok) }
        compose.onNodeWithText(confirmLabel).assertIsDisplayed().requestFocus()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertDisplayItems(ORIGINAL_ITEMS + "decoder")
    }

    private fun openList(key: String, currentValue: String, targetValue: String): ChoiceLabels {
        val labels = listLabels(key, currentValue, targetValue)
        focusPreference(key)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(labels.current).assertIsDisplayed().assertIsFocused().assertIsSelected()
        return labels
    }

    private fun openDisplayItems(): ChoiceLabels {
        var labels: ChoiceLabels? = null
        withFragment { fragment ->
            val preference = requireNotNull(fragment.findPreference<MultiSelectListPreference>(DISPLAY_ITEMS_KEY))
            val currentIndex = preference.entryValues.indexOfFirst { it.toString() == "resolution" }
            val targetIndex = preference.entryValues.indexOfFirst { it.toString() == "decoder" }
            assertEquals("The controller scenario expects adjacent display options", currentIndex + 1, targetIndex)
            labels = ChoiceLabels(preference.entries[currentIndex].toString(),
                preference.entries[targetIndex].toString(), targetIndex - currentIndex)
        }
        focusPreference(DISPLAY_ITEMS_KEY)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(requireNotNull(labels).current).assertIsDisplayed().assertIsFocused()
        return requireNotNull(labels)
    }

    private fun listLabels(key: String, currentValue: String, targetValue: String): ChoiceLabels {
        var labels: ChoiceLabels? = null
        withFragment { fragment ->
            val preference = requireNotNull(fragment.findPreference<ListPreference>(key))
            val currentIndex = preference.findIndexOfValue(currentValue)
            val targetIndex = preference.findIndexOfValue(targetValue)
            assertTrue("Both test values must remain available", currentIndex >= 0 && targetIndex > currentIndex)
            labels = ChoiceLabels(preference.entries[currentIndex].toString(),
                preference.entries[targetIndex].toString(), targetIndex - currentIndex)
        }
        return requireNotNull(labels)
    }

    private fun assertListValue(key: String, expected: String) {
        withFragment { fragment ->
            assertEquals(expected, requireNotNull(fragment.findPreference<ListPreference>(key)).value)
        }
    }

    private fun assertDisplayItems(expected: Set<String>) {
        assertEquals(expected, preferences.getStringSet(DISPLAY_ITEMS_KEY, emptySet()))
        withFragment { fragment ->
            assertEquals(expected, requireNotNull(fragment.findPreference<MultiSelectListPreference>(DISPLAY_ITEMS_KEY)).values)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun focusPreference(key: String) {
        var screenKey: String? = null
        withFragment { fragment ->
            val preference = requireNotNull(fragment.findPreference<Preference>(key))
            var parent = preference.parent
            while (parent != null && parent !== fragment.preferenceScreen) {
                if (parent is PreferenceScreen) {
                    screenKey = parent.key
                    break
                }
                parent = parent.parent
            }
            if (fragment.activeSettingsScreenKey != screenKey) fragment.closeSettingsScreen()
            fragment.applySearchFilter(key)
            screenKey?.let { target ->
                if (fragment.activeSettingsScreenKey != target) fragment.openSettingsScreen(target, key)
            }
        }
        compose.waitUntil(10_000) {
            var ready = false
            requireNotNull(scenario).onActivity { activity ->
                val fragment = activity.supportFragmentManager.fragments
                    .filterIsInstance<StreamSettings.SettingsFragment>().single()
                val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                ready = fragment.activeSettingsScreenKey == screenKey && activity.hasWindowFocus() &&
                    list.isAttachedToWindow && list.isLaidOut && !list.isLayoutRequested
            }
            ready
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var position = RecyclerView.NO_POSITION
        requireNotNull(scenario).onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            val adapter = list.adapter as PreferenceGroupAdapter
            position = (0 until adapter.itemCount).first { adapter.getItem(it)?.key == key }
            val preference = requireNotNull(adapter.getItem(position))
            assertTrue("The tested setting must be enabled", preference.isEnabled)
            list.scrollToPosition(position)
        }
        compose.waitUntil(5_000) {
            var laidOut = false
            requireNotNull(scenario).onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                laidOut = list.findViewHolderForAdapterPosition(position)?.itemView?.isLaidOut == true
            }
            laidOut
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        requireNotNull(scenario).onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            val row = requireNotNull(list.findViewHolderForAdapterPosition(position)?.itemView)
            assertTrue(row.requestFocusFromTouch())
        }
    }

    @SuppressLint("RestrictedApi")
    private fun assertFocusedPreference(key: String) {
        compose.waitUntil(5_000) {
            var focusedKey: String? = null
            requireNotNull(scenario).onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                val focusedRow = activity.currentFocus?.let(list::findContainingItemView)
                val position = focusedRow?.let(list::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
                if (position != RecyclerView.NO_POSITION) {
                    focusedKey = (list.adapter as PreferenceGroupAdapter).getItem(position)?.key
                }
            }
            focusedKey == key
        }
    }

    private fun withFragment(action: (StreamSettings.SettingsFragment) -> Unit) {
        requireNotNull(scenario).onActivity { activity ->
            action(activity.supportFragmentManager.fragments.filterIsInstance<StreamSettings.SettingsFragment>().single())
        }
    }

    private fun rotateSettings() {
        var previousActivity: StreamSettings? = null
        var previousRecycler: RecyclerView? = null
        var targetOrientation = Configuration.ORIENTATION_UNDEFINED
        requireNotNull(scenario).onActivity { activity ->
            previousActivity = activity
            previousRecycler = activity.findViewById(androidx.preference.R.id.recycler_view)
            val toLandscape = activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            targetOrientation = if (toLandscape) Configuration.ORIENTATION_LANDSCAPE
                else Configuration.ORIENTATION_PORTRAIT
            activity.requestedOrientation = if (toLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) {
            var rotationCompleted = false
            requireNotNull(scenario).onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                rotationCompleted = activity !== previousActivity &&
                    activity.resources.configuration.orientation == targetOrientation &&
                    previousActivity?.isDestroyed == true &&
                    previousRecycler?.isAttachedToWindow == false &&
                    recycler != null && recycler !== previousRecycler &&
                    recycler.isAttachedToWindow && recycler.isLaidOut &&
                    !recycler.isLayoutRequested
            }
            rotationCompleted
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun navigateDown(count: Int) = repeat(count) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }

    private fun sendKey(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private data class ChoiceLabels(val current: String, val target: String, val distance: Int)

    private companion object {
        private const val VIDEO_FORMAT_KEY = "video_format"
        private const val SCREEN_POSITION_KEY = "list_screen_position"
        private const val DISPLAY_ITEMS_KEY = "perf_overlay_display_items"
        private val ORIGINAL_ITEMS = setOf("resolution")
    }
}
