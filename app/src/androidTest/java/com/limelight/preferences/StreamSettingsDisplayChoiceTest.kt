package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

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
        originalPreferences = preferences.all.toMap()
        originalCustomPreferences = customPreferences.all.toMap()
        preferences.edit()
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, ORIGINAL_RESOLUTION)
            .putString(PreferenceConfiguration.FPS_PREF_STRING, FRAME_RATE)
            .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, ORIGINAL_BITRATE)
            .putBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, true)
            .putBoolean(PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING, false)
            .commit()
        CustomResolutionsStore.save(context, CustomResolutionsStore.DEFAULT_RESOLUTIONS)
    }

    @After
    fun restoreSettings() {
        scenario?.close()
        restore(preferences, originalPreferences)
        restore(customPreferences, originalCustomPreferences)
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
        assertTrue("Missing resolution $value", index >= 0)
        return preference.entries[index].toString()
    }

    @SuppressLint("RestrictedApi")
    private fun focusResolutionRow() {
        var position = RecyclerView.NO_POSITION
        requireNotNull(scenario).onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            val adapter = list.adapter as PreferenceGroupAdapter
            position = (0 until adapter.itemCount).first { index ->
                adapter.getItem(index)?.key == PreferenceConfiguration.RESOLUTION_PREF_STRING
            }
            assertTrue(adapter.getItem(position) is DisplayChoicePreference)
            list.scrollToPosition(position)
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

    private fun fragment(activity: StreamSettings): StreamSettings.SettingsFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<StreamSettings.SettingsFragment>().single()

    private fun restore(prefs: SharedPreferences, snapshot: Map<String, *>) {
        val editor = prefs.edit().clear()
        for ((key, value) in snapshot) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    private companion object {
        private const val ORIGINAL_RESOLUTION = "1920x1080"
        private const val NEW_RESOLUTION = "1280x720"
        private const val FRAME_RATE = "60"
        private const val ORIGINAL_BITRATE = 57_000
    }
}
