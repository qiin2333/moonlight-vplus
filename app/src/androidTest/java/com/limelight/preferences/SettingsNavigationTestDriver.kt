package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/** Drives the public settings navigation and waits for a window that can accept controller input. */
@SuppressLint("RestrictedApi", "DiscouragedApi")
internal class SettingsNavigationTestDriver(private val compose: ComposeTestRule) {
    private var scenario: ActivityScenario<StreamSettings>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun launch() {
        scenario = ActivityScenario.launch(StreamSettings::class.java)
        awaitScreen(null)
    }

    fun close() { scenario?.close() }

    fun withActivity(action: (StreamSettings) -> Unit) { requireNotNull(scenario).onActivity(action) }

    fun withFragment(action: (StreamSettings.SettingsFragment) -> Unit) {
        withActivity { action(fragment(it)) }
    }

    fun openScreen(key: String, targetKey: String? = null) {
        withFragment { it.openSettingsScreen(key, targetKey) }
        awaitScreen(key)
    }

    fun awaitScreen(key: String?) {
        awaitReady { activity ->
            val detailId = activity.resources.getIdentifier("settings_detail_root", "id", activity.packageName)
            val detailShown = activity.findViewById<View>(detailId)?.isShown == true
            fragment(activity).activeSettingsScreenKey == key && detailShown == (key != null)
        }
    }

    fun awaitReady(condition: (StreamSettings) -> Boolean) {
        compose.waitUntil(10_000) {
            var ready = false
            withActivity { activity ->
                val list = recycler(activity)
                // Focus can be assigned before a partly visible row finishes scrolling into view.
                ready = activity.hasWindowFocus() && list.isAttachedToWindow && list.isLaidOut &&
                    !list.isLayoutRequested && !list.isComputingLayout && !list.hasPendingAdapterUpdates() &&
                    list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    list.adapter is PreferenceGroupAdapter && condition(activity)
            }
            ready
        }
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    fun search(query: String) {
        withActivity { it.findViewById<EditText>(R.id.settings_search_input).setText(query) }
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    fun query(): String {
        var query = ""
        withActivity { query = it.findViewById<EditText>(R.id.settings_search_input).text.toString() }
        return query
    }

    fun adapterItems(): List<Preference> {
        var items = emptyList<Preference>()
        withActivity { activity ->
            val adapter = recycler(activity).adapter as PreferenceGroupAdapter
            items = (0 until adapter.itemCount).mapNotNull(adapter::getItem)
        }
        return items
    }

    fun adapterKeys(): List<String?> = adapterItems().map { it.key }

    fun summary(key: String): String {
        var summary = ""
        withFragment { summary = requireNotNull(it.findPreference<Preference>(key)).summary.toString() }
        return summary
    }

    fun focusPreference(key: String) {
        var position = RecyclerView.NO_POSITION
        withActivity { activity ->
            val list = recycler(activity)
            val adapter = list.adapter as PreferenceGroupAdapter
            position = (0 until adapter.itemCount).first { adapter.getItem(it)?.key == key }
            assertTrue("The tested setting must be enabled: $key", requireNotNull(adapter.getItem(position)).isEnabled)
            // Make the starting viewport explicit: scrollToPosition only ensures attachment,
            // and requesting focus does not guarantee that the whole row becomes visible.
            list.stopScroll()
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
        }
        awaitReady { activity ->
            recycler(activity).findViewHolderForAdapterPosition(position)?.itemView?.isLaidOut == true
        }
        withActivity { activity ->
            val row = requireNotNull(recycler(activity).findViewHolderForAdapterPosition(position)?.itemView)
            assertTrue(row.requestFocusFromTouch())
        }
        assertFocusedPreference(key)
        awaitStableViewport(key)
    }

    fun assertFocusedPreference(key: String) {
        awaitReady { activity ->
            val list = recycler(activity)
            val row = activity.currentFocus?.let(list::findContainingItemView)
            val position = row?.let(list::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
            position != RecyclerView.NO_POSITION &&
                (list.adapter as PreferenceGroupAdapter).getItem(position)?.key == key
        }
    }

    fun viewport(): Viewport {
        awaitStableViewport()
        var result: Viewport? = null
        withActivity { activity ->
            val list = recycler(activity)
            val layout = list.layoutManager as LinearLayoutManager
            val position = layout.findFirstVisibleItemPosition()
            val view = requireNotNull(layout.findViewByPosition(position))
            result = Viewport((list.adapter as PreferenceGroupAdapter).getItem(position)?.key,
                layout.getDecoratedTop(view) - list.paddingTop)
        }
        return requireNotNull(result)
    }

    /** A focus-driven scroll or parent resize may start on the next traversal while scrollState is idle. */
    private fun awaitStableViewport(focusedKey: String? = null) {
        var previous: List<Int>? = null
        var stableFrames = 0
        repeat(60) {
            val frame = CountDownLatch(1)
            withActivity { activity -> recycler(activity).postOnAnimation { frame.countDown() } }
            assertTrue("Settings did not produce a navigation frame", frame.await(5, TimeUnit.SECONDS))
            var geometry: List<Int>? = null
            withActivity { activity ->
                val list = recycler(activity)
                val layout = list.layoutManager as? LinearLayoutManager ?: return@withActivity
                val adapter = list.adapter as? PreferenceGroupAdapter ?: return@withActivity
                if (list.isLayoutRequested || list.isComputingLayout || list.hasPendingAdapterUpdates() ||
                    list.scrollState != RecyclerView.SCROLL_STATE_IDLE) return@withActivity
                val focused = activity.currentFocus?.let(list::findContainingItemView)
                if (focusedKey != null) {
                    val focusedRow = focused ?: return@withActivity
                    val position = list.getChildAdapterPosition(focusedRow)
                    if (position == RecyclerView.NO_POSITION || adapter.getItem(position)?.key != focusedKey) return@withActivity
                    val availableHeight = list.height - list.paddingTop - list.paddingBottom
                    if (focusedRow.height <= availableHeight &&
                        (focusedRow.top < list.paddingTop || focusedRow.bottom > list.height - list.paddingBottom)) return@withActivity
                }
                val first = layout.findFirstVisibleItemPosition()
                val firstView = layout.findViewByPosition(first) ?: return@withActivity
                geometry = listOf(first, layout.getDecoratedTop(firstView), list.width, list.height,
                    list.paddingLeft, list.paddingTop, list.paddingRight, list.paddingBottom,
                    focused?.top ?: -1, focused?.bottom ?: -1)
            }
            stableFrames = if (geometry != null && geometry == previous) stableFrames + 1 else 0
            if (stableFrames >= 2) return
            previous = geometry
        }
        error("Settings viewport did not settle with the focused row visible: $focusedKey")
    }

    fun rotate() {
        var expectedScreen: String? = null
        var targetOrientation = Configuration.ORIENTATION_UNDEFINED
        withActivity { activity ->
            expectedScreen = fragment(activity).activeSettingsScreenKey
            val landscape = activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            targetOrientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        awaitReady { activity ->
            activity.resources.configuration.orientation == targetOrientation &&
                fragment(activity).activeSettingsScreenKey == expectedScreen
        }
    }

    fun recreate() {
        var expectedScreen: String? = null
        withFragment { expectedScreen = it.activeSettingsScreenKey }
        requireNotNull(scenario).recreate()
        awaitScreen(expectedScreen)
    }

    fun press(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    /** Inject through the system so Android 13+ can use OnBackInvokedDispatcher. */
    fun pressSystemBack() {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent ${KeyEvent.KEYCODE_BACK}"),
        ).use { it.readBytes() }
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
    }

    private fun recycler(activity: StreamSettings): RecyclerView =
        activity.findViewById(androidx.preference.R.id.recycler_view)

    private fun fragment(activity: StreamSettings): StreamSettings.SettingsFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<StreamSettings.SettingsFragment>().single()

    data class Viewport(val firstKey: String?, val offset: Int)
}

internal fun snapshotSettingsPreferences(source: SharedPreferences): Map<String, *> =
    source.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

internal fun restoreSettingsPreferences(target: SharedPreferences, values: Map<String, *>) {
    val editor = target.edit().clear()
    for ((key, value) in values) {
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
