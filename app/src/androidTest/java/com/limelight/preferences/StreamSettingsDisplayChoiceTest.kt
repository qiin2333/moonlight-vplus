package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import com.limelight.utils.ConfigurationSyncManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class StreamSettingsDisplayChoiceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // Keep the same desugared Collections facade as other StreamSettings tests.
    @Suppress("unused")
    private val desugarCollectionsCompatibility = Collections.synchronizedMap(mutableMapOf<Any, Any>())

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var preferences: SharedPreferences
    private lateinit var customPreferences: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>
    private lateinit var originalCustomPreferences: Map<String, *>
    private var scenario: ActivityScenario<StreamSettings>? = null

    @Before
    fun prepareSettings() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        customPreferences = context.getSharedPreferences("custom_resolutions", Context.MODE_PRIVATE)
        originalPreferences = snapshotSettingsPreferences(preferences)
        originalCustomPreferences = snapshotSettingsPreferences(customPreferences)
        preferences.edit()
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, ORIGINAL_RESOLUTION)
            .putString(PreferenceConfiguration.FPS_PREF_STRING, FRAME_RATE)
            .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, ORIGINAL_BITRATE)
            .putBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, true)
            .putBoolean(PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING, false)
            .putBoolean(ConfigurationSyncManager.PREF_AUTO_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_EXTERNAL_SNAPSHOT_ENABLED, false)
            .putBoolean(ConfigurationSyncManager.PREF_BACKGROUND_SYNC_ENABLED, false)
            .commit()
        CustomResolutionsStore.save(context, DEFAULT_RESOLUTIONS)
    }

    @After
    fun restoreSettings() {
        scenario?.close()
        restoreSettingsPreferences(customPreferences, originalCustomPreferences)
        // Re-enable any original backup flags only after the original list is together again.
        restoreSettingsPreferences(preferences, originalPreferences)
    }

    private fun launchSettings(autoAdjust: Boolean = true) {
        preferences.edit().putBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, autoAdjust).commit()
        scenario = ActivityScenario.launch(StreamSettings::class.java)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun oneDpadStepMovesFromResolutionRowToFrameRateRow() {
        launchSettings()
        focusResolutionRow()

        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)

        assertFocusedPreference(PreferenceConfiguration.FPS_PREF_STRING)
    }

    @Test
    @Suppress("DEPRECATION")
    fun controllerCanChooseFrameRatesWithTheLegacyUnlockFlagDisabled() {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val refreshRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display.supportedModes.map { it.refreshRate } + display.refreshRate
        } else {
            listOf(display.refreshRate)
        }
        val maxRefreshRate = refreshRates.filter { it.isFinite() && it > 0f }.maxOrNull() ?: 0f
        val savedFrameRate = (ceil(maxRefreshRate.toDouble()).toInt().coerceAtLeast(240) + 30).toString()
        preferences.edit()
            .putBoolean(PreferenceConfiguration.UNLOCK_FPS_STRING, false)
            .putString(PreferenceConfiguration.FPS_PREF_STRING, savedFrameRate)
            .commit()
        launchSettings(autoAdjust = false)

        var savedLabel = ""
        var targetLabel = ""
        var previousLabel = ""
        var notice = ""
        var targetSteps = 0
        requireNotNull(scenario).onActivity { activity ->
            val fragment = fragment(activity)
            assertNull(fragment.findPreference<Preference>(PreferenceConfiguration.UNLOCK_FPS_STRING))
            val preference = requireNotNull(fragment.findPreference<DisplayChoicePreference>(
                PreferenceConfiguration.FPS_PREF_STRING))
            val values = preference.entryValues.map(CharSequence::toString)
            assertEquals(values.toSet(), preference.enabledValues)
            assertEquals(savedFrameRate, preference.value)
            assertTrue("Frame-rate choices must not have persistent descriptions",
                values.all { preference.descriptionFor(it) == null })
            notice = activity.getString(R.string.display_frame_rate_above_refresh_rate)
            savedLabel = labelFor(preference, savedFrameRate)
            if (maxRefreshRate > 0f) {
                assertTrue("A frame rate above the display limit must have a compact marker",
                    savedLabel.endsWith(activity.getString(R.string.display_frame_rate_above_refresh_rate_marker)))
            }
            targetLabel = labelFor(preference, "30")
            val savedIndex = values.indexOf(savedFrameRate)
            previousLabel = labelFor(preference, values[savedIndex - 1])
            targetSteps = savedIndex - values.indexOf("30")
            assertTrue(targetSteps > 0)
        }

        focusResolutionRow()
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedPreference(PreferenceConfiguration.FPS_PREF_STRING)
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText(savedLabel).assertIsDisplayed().assertIsFocused().assertIsSelected()
        compose.onAllNodesWithText(notice).assertCountEquals(0)
        sendKey(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText(previousLabel).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        assertFocusedPreference(PreferenceConfiguration.FPS_PREF_STRING)
        assertEquals(savedFrameRate, preferences.getString(PreferenceConfiguration.FPS_PREF_STRING, null))

        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText(savedLabel).assertIsDisplayed().assertIsFocused().assertIsSelected()
        repeat(targetSteps) { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        compose.onNodeWithText(targetLabel).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertEquals("30", preferences.getString(PreferenceConfiguration.FPS_PREF_STRING, null))
        assertEquals(ORIGINAL_BITRATE, preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
        assertFalse(preferences.getBoolean(PreferenceConfiguration.UNLOCK_FPS_STRING, true))
        assertFocusedPreference(PreferenceConfiguration.FPS_PREF_STRING)

        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        repeat(targetSteps) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(savedLabel).assertIsFocused()
        compose.onAllNodesWithText(notice).assertCountEquals(0)
        if (maxRefreshRate > 0f) {
            // Observe the system toast after confirmation; it lives outside the Compose sheet.
            val event = InstrumentationRegistry.getInstrumentation().uiAutomation.executeAndWaitForEvent(
                { sendKey(KeyEvent.KEYCODE_BUTTON_A) },
                { event -> event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    event.text.any { it.toString() == notice } },
                5_000
            )
            event.recycle()
        } else {
            sendKey(KeyEvent.KEYCODE_BUTTON_A)
        }
        assertEquals(savedFrameRate, preferences.getString(PreferenceConfiguration.FPS_PREF_STRING, null))
        assertEquals(ORIGINAL_BITRATE, preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
        assertFocusedPreference(PreferenceConfiguration.FPS_PREF_STRING)
    }

    @Test
    fun controllerOpensAtCurrentChoiceAndCancelRestoresTheOriginalRowWithoutSaving() {
        launchSettings()
        val currentLabel = openResolutionSheet()

        compose.onNodeWithText(currentLabel).assertIsDisplayed().assertIsFocused().assertIsSelected()
        sendKey(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText(labelFor(NEW_RESOLUTION)).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_B)

        assertFocusedPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING)
        assertEquals(ORIGINAL_RESOLUTION, preferences.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null))
        assertEquals(ORIGINAL_BITRATE, preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
    }

    @Test
    fun changingResolutionRecomputesRecommendedBitrateWhenAutomaticAdjustmentIsEnabled() {
        launchSettings(autoAdjust = true)
        select720pWithController()

        assertEquals(NEW_RESOLUTION, preferences.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null))
        assertEquals(PreferenceConfiguration.getDefaultBitrate(NEW_RESOLUTION, FRAME_RATE),
            preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
        assertFocusedPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING)
    }

    @Test
    fun changingResolutionPreservesManualBitrateWhenAutomaticAdjustmentIsDisabled() {
        launchSettings(autoAdjust = false)
        select720pWithController()

        assertEquals(NEW_RESOLUTION, preferences.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null))
        assertEquals(ORIGINAL_BITRATE, preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
    }

    @Test
    fun confirmingCurrentResolutionDoesNotRecomputeBitrate() {
        launchSettings(autoAdjust = true)
        openResolutionSheet()

        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertEquals(ORIGINAL_RESOLUTION, preferences.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null))
        assertEquals(ORIGINAL_BITRATE, preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
        assertFocusedPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING)
    }

    @Test
    fun customManagementReturnsToItsActionAndThenToTheOriginalSettingsRow() {
        launchSettings()
        val originalResolutions = CustomResolutionsStore.load(context)
        val managerLabel = openCustomManagementWithController()

        sendKey(KeyEvent.KEYCODE_BUTTON_B)

        compose.onNodeWithText(managerLabel).assertIsDisplayed().assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        assertFocusedPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING)
        assertEquals(originalResolutions, CustomResolutionsStore.load(context))
        assertEquals(ORIGINAL_RESOLUTION, preferences.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null))
        assertEquals(ORIGINAL_BITRATE, preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
    }

    @Test
    fun removingTheCurrentResolutionSavesOnceAndReopeningDoesNotRestoreIt() {
        val current = requireNotNull(ResolutionValidator.parseResolution(ORIGINAL_RESOLUTION))
        CustomResolutionsStore.save(context, listOf(current))
        launchSettings()
        openCustomManagementWithController()
        val deleteLabel = context.getString(R.string.custom_resolution_delete_contentdesc,
            current.width, current.height)
        compose.onNodeWithContentDescription(deleteLabel).performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.custom_list_save)).performTouchInput { click() }

        compose.onNodeWithText(context.getString(R.string.display_resolution_current_not_saved))
            .assertIsDisplayed()

        // Confirming the retained current choice must not add it back or reset a manual bitrate.
        compose.onNodeWithText(labelFor(ORIGINAL_RESOLUTION)).performTouchInput { click() }

        // This is a new editor session; no second Save is needed to complete the deletion.
        openCustomManagementWithController()
        compose.onNodeWithText(context.getString(R.string.custom_resolution_empty_title)).assertIsDisplayed()
        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(CustomResolutionsStore.load(context).isEmpty())
        assertEquals(ORIGINAL_RESOLUTION, preferences.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null))
        assertEquals(ORIGINAL_BITRATE, preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
    }

    @Test
    fun choosingASavedPresetRetiresThePreviousUnsavedCurrentChoice() {
        CustomResolutionsStore.save(context, listOf(requireNotNull(
            ResolutionValidator.parseResolution(NEW_RESOLUTION))))
        launchSettings()
        select720pWithController()

        requireNotNull(scenario).onActivity { activity ->
            val preference = requireNotNull(fragment(activity).findPreference<DisplayChoicePreference>(
                PreferenceConfiguration.RESOLUTION_PREF_STRING))
            assertEquals(listOf(NEW_RESOLUTION, PreferenceConfiguration.RES_NATIVE),
                preference.entryValues.map(CharSequence::toString))
        }
        assertEquals(listOf(NEW_RESOLUTION), CustomResolutionsStore.load(context).map(Resolution::toString))
    }

    @Test
    fun recreatingSettingsWhileCustomEditorIsOpenDoesNotReopenTheOldChoiceSheet() {
        launchSettings()
        val managerLabel = openCustomManagementWithController()

        requireNotNull(scenario).recreate()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        compose.onAllNodesWithText(managerLabel).assertCountEquals(0)
        compose.onAllNodesWithText(editorAddLabel()).assertCountEquals(0)
        // The new view still opens a fresh selector; no callback from the destroyed editor wins.
        openResolutionSheet()
        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        assertFocusedPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING)
    }

    @Test
    fun controllerCancelAfterTouchOpeningRestoresTheOriginalSettingsRow() {
        launchSettings()
        openResolutionSheetWithTouch()

        sendKey(KeyEvent.KEYCODE_BUTTON_B)

        assertFocusedPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING)
        assertEquals(ORIGINAL_RESOLUTION, preferences.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null))
        assertEquals(ORIGINAL_BITRATE, preferences.getInt(PreferenceConfiguration.BITRATE_PREF_STRING, -1))
    }

    @Test
    fun controllerCanManageCustomResolutionsAfterTouchOpeningAndReturnToTheSettingsRow() {
        launchSettings()
        val managerLabel = openCustomManagementWithController(touchFirst = true)

        sendKey(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText(managerLabel).assertIsDisplayed().assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_B)

        assertFocusedPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING)
        assertEquals(ORIGINAL_RESOLUTION, preferences.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null))
    }

    @Test
    fun physicalKeyboardKeepsCustomResolutionInputFocusedUntilControllerNavigation() {
        launchSettings()
        openResolutionSheetWithTouch()
        var managerLabel = ""
        requireNotNull(scenario).onActivity { activity ->
            managerLabel = requireNotNull(fragment(activity).findPreference<DisplayChoicePreference>(
                PreferenceConfiguration.RESOLUTION_PREF_STRING)?.actionLabel)
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(managerLabel))
        compose.onNodeWithText(managerLabel).performTouchInput { click() }
        compose.onNodeWithText(editorAddLabel()).assertIsDisplayed()

        val fields = compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        fields[0].performTouchInput { click() }
        fields[0].assertIsFocused()
        sendHardwareDigits("1920")
        fields[0].assertIsFocused().assertTextEquals("1920")

        // Touching the second field starts another touch-to-keyboard transition.
        fields[1].performTouchInput { click() }
        fields[1].assertIsFocused()
        sendHardwareDigits("1080")
        fields[1].assertIsFocused().assertTextEquals("1080")

        // A real controller source must still acquire the editor's navigation anchor.
        sendSourceKey(KeyEvent.KEYCODE_DPAD_DOWN, InputDevice.SOURCE_GAMEPAD)
        compose.onNodeWithText(editorAddLabel()).assertIsFocused()
    }

    @Test
    @SuppressLint("RestrictedApi")
    fun resolutionChoicesAndCustomManagementRemainSearchable() {
        launchSettings()
        requireNotNull(scenario).onActivity { activity ->
            val fragment = fragment(activity)
            val preference = requireNotNull(fragment.findPreference<DisplayChoicePreference>(
                PreferenceConfiguration.RESOLUTION_PREF_STRING))
            val queries = listOf(labelFor(preference, ORIGINAL_RESOLUTION),
                requireNotNull(preference.descriptionFor(ORIGINAL_RESOLUTION)),
                requireNotNull(preference.actionLabel))
            for (query in queries) {
                fragment.applySearchFilter(query)
                assertTrue("Resolution must match $query", preference.isVisible)
            }
            fragment.applySearchFilter("")
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun select720pWithController() {
        openResolutionSheet()
        sendKey(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText(labelFor(NEW_RESOLUTION)).assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
    }

    private fun openCustomManagementWithController(touchFirst: Boolean = false): String {
        if (touchFirst) openResolutionSheetWithTouch() else openResolutionSheet()
        var managerLabel = ""
        var choiceCount = 0
        requireNotNull(scenario).onActivity { activity ->
            val preference = requireNotNull(fragment(activity).findPreference<DisplayChoicePreference>(
                PreferenceConfiguration.RESOLUTION_PREF_STRING))
            managerLabel = requireNotNull(preference.actionLabel)
            choiceCount = preference.entryValues.size
        }
        // Continue to the end so this also exercises navigation over disabled presets.
        repeat(choiceCount) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(managerLabel).assertIsDisplayed().assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText(editorAddLabel())
            .assertIsDisplayed().assertIsFocused()
        return managerLabel
    }

    private fun editorAddLabel(): String {
        var label = ""
        requireNotNull(scenario).onActivity { label = it.getString(R.string.custom_resolution_add_short) }
        return label
    }

    private fun openResolutionSheet(): String {
        focusResolutionRow()
        val label = labelFor(ORIGINAL_RESOLUTION)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(label).assertIsDisplayed().assertIsFocused().assertIsSelected()
        return label
    }

    @SuppressLint("RestrictedApi")
    private fun openResolutionSheetWithTouch() {
        focusResolutionRow()
        var x = 0f
        var y = 0f
        requireNotNull(scenario).onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            val adapter = list.adapter as PreferenceGroupAdapter
            val position = (0 until adapter.itemCount).first { index ->
                adapter.getItem(index)?.key == PreferenceConfiguration.RESOLUTION_PREF_STRING
            }
            val row = requireNotNull(list.findViewHolderForAdapterPosition(position)?.itemView)
            val location = IntArray(2)
            row.getLocationOnScreen(location)
            x = location[0] + row.width / 2f
            y = location[1] + row.height / 2f
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 1, MotionEvent.ACTION_UP, x, y, 0)
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        compose.waitForIdle()
        compose.onNodeWithText(labelFor(ORIGINAL_RESOLUTION)).assertIsDisplayed().assertIsSelected()
    }

    private fun labelFor(value: String): String {
        var label = ""
        requireNotNull(scenario).onActivity { activity ->
            label = labelFor(requireNotNull(fragment(activity).findPreference<DisplayChoicePreference>(
                PreferenceConfiguration.RESOLUTION_PREF_STRING)), value)
        }
        return label
    }

    private fun labelFor(preference: DisplayChoicePreference, value: String): String {
        val index = preference.entryValues.indexOfFirst { it.toString() == value }
        assertTrue("Missing display choice $value", index >= 0)
        return preference.entries[index].toString()
    }

    @SuppressLint("RestrictedApi")
    private fun focusResolutionRow() {
        // Resolution and frame rate are direct main-page choices; feature navigation must not
        // introduce an intermediate page or an expander before either controller row.
        compose.waitUntil(10_000) {
            var ready = false
            requireNotNull(scenario).onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                ready = fragment(activity).activeSettingsScreenKey == null && activity.hasWindowFocus() &&
                    list.isAttachedToWindow && list.isLaidOut && !list.isLayoutRequested
            }
            ready
        }
        var position = RecyclerView.NO_POSITION
        requireNotNull(scenario).onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            val adapter = list.adapter as PreferenceGroupAdapter
            position = (0 until adapter.itemCount).first { index ->
                adapter.getItem(index)?.key == PreferenceConfiguration.RESOLUTION_PREF_STRING
            }
            assertTrue(adapter.getItem(position) is DisplayChoicePreference)
            assertEquals("The main page must expose one resolution choice", 1,
                (0 until adapter.itemCount).count {
                    adapter.getItem(it)?.key == PreferenceConfiguration.RESOLUTION_PREF_STRING
                })
            assertEquals("The main page must expose one frame-rate choice", 1,
                (0 until adapter.itemCount).count {
                    adapter.getItem(it)?.key == PreferenceConfiguration.FPS_PREF_STRING
                })
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

    private fun sendKey(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun sendHardwareDigits(text: String) {
        text.forEach { digit ->
            sendSourceKey(KeyEvent.KEYCODE_0 + (digit - '0'), InputDevice.SOURCE_KEYBOARD)
        }
    }

    private fun sendSourceKey(code: Int, source: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            instrumentation.sendKeySync(KeyEvent(
                downTime, SystemClock.uptimeMillis(), action, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source
            ))
        }
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    private fun fragment(activity: StreamSettings): StreamSettings.SettingsFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<StreamSettings.SettingsFragment>().single()

    private companion object {
        private val DEFAULT_RESOLUTIONS = listOf(
            Resolution(1280, 720), Resolution(1920, 1080),
            Resolution(2560, 1440), Resolution(3840, 2160)
        )
        private const val ORIGINAL_RESOLUTION = "1920x1080"
        private const val NEW_RESOLUTION = "1280x720"
        private const val FRAME_RATE = "60"
        private const val ORIGINAL_BITRATE = 57_000
    }
}
