package com.limelight.preferences

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.KeyEvent
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class StreamSettingsExpandFocusTest {

    // The target and instrumentation APKs share the desugared j$ namespace. Keep the
    // synchronizedMap facade in the test APK so it cannot shadow the target with a partial class.
    @Suppress("unused")
    private val desugarCollectionsCompatibility =
        Collections.synchronizedMap(mutableMapOf<Any, Any>())

    @get:Rule
    val activityRule = ActivityScenarioRule(StreamSettings::class.java)

    @Test
    @SuppressLint("RestrictedApi")
    fun activatingAdvancedRowKeepsFocusInPreferenceList() {
        lateinit var recyclerView: RecyclerView
        var expandPosition = RecyclerView.NO_POSITION

        activityRule.scenario.onActivity { activity ->
            recyclerView = activity.findViewById(androidx.preference.R.id.recycler_view)
            val adapter = recyclerView.adapter as PreferenceGroupAdapter
            expandPosition = (0 until adapter.itemCount).first { position ->
                adapter.getItem(position)?.javaClass?.name == ANDROIDX_EXPAND_BUTTON_CLASS
            }
            recyclerView.scrollToPosition(expandPosition)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val expandRow = recyclerView
                .findViewHolderForAdapterPosition(expandPosition)
                ?.itemView
            assertNotNull(expandRow)
            assertTrue(expandRow!!.requestFocusFromTouch())

            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val focusedView = activity.currentFocus
            assertNotNull(focusedView)
            val focusedRow = recyclerView.findContainingItemView(focusedView!!)
            assertNotNull(focusedRow)

            val focusedPosition = recyclerView.getChildAdapterPosition(focusedRow!!)
            assertTrue(focusedPosition >= expandPosition)
            val focusedPreference =
                (recyclerView.adapter as PreferenceGroupAdapter).getItem(focusedPosition)
            assertNotEquals(ANDROIDX_EXPAND_BUTTON_CLASS, focusedPreference?.javaClass?.name)
        }
    }

    @Test
    @SuppressLint("RestrictedApi")
    fun rotationCancelsCallbacksPostedToTheDestroyedPreferenceList() {
        lateinit var oldRecyclerView: RecyclerView
        var expandPosition = RecyclerView.NO_POSITION

        activityRule.scenario.onActivity { activity ->
            oldRecyclerView = activity.findViewById(androidx.preference.R.id.recycler_view)
            expandPosition = findExpandPosition(oldRecyclerView)
            oldRecyclerView.scrollToPosition(expandPosition)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val fragment = settingsFragment(activity)
            val expandRow = requireNotNull(
                oldRecyclerView.findViewHolderForAdapterPosition(expandPosition)?.itemView
            )
            assertTrue(expandRow.requestFocusFromTouch())
            assertTrue(fragment.prepareExpandButtonFocusRestore(expandRow))
            fragment.restoreFocusAfterExpandButton()
        }

        activityRule.scenario.recreate()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        awaitRecreatedListFocus(oldRecyclerView)

        activityRule.scenario.onActivity { activity ->
            val newRecyclerView: RecyclerView =
                activity.findViewById(androidx.preference.R.id.recycler_view)
            assertNotSame(oldRecyclerView, newRecyclerView)
            assertTrue(!oldRecyclerView.isAttachedToWindow)
            assertTrue(newRecyclerView.isAttachedToWindow)
            activity.currentFocus?.let { focusedView ->
                val focusId = if (focusedView.id == android.view.View.NO_ID) "no-id"
                    else runCatching { activity.resources.getResourceName(focusedView.id) }
                        .getOrElse { focusedView.id.toString() }
                assertTrue("Recreated list lost focus to ${focusedView.javaClass.name} ($focusId)",
                    newRecyclerView.findContainingItemView(focusedView) != null)
            }
        }
    }

    @Test
    @SuppressLint("RestrictedApi")
    fun rapidReplacementRequestsKeepOnlyTheLatestFocusRestore() {
        lateinit var recyclerView: RecyclerView
        var expandPosition = RecyclerView.NO_POSITION

        activityRule.scenario.onActivity { activity ->
            recyclerView = activity.findViewById(androidx.preference.R.id.recycler_view)
            expandPosition = findExpandPosition(recyclerView)
            recyclerView.scrollToPosition(expandPosition)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val fragment = settingsFragment(activity)
            val expandRow = requireNotNull(
                recyclerView.findViewHolderForAdapterPosition(expandPosition)?.itemView
            )
            assertTrue(expandRow.requestFocusFromTouch())

            repeat(3) {
                assertTrue(fragment.prepareExpandButtonFocusRestore(expandRow))
                fragment.restoreFocusAfterExpandButton()
            }
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val focusedView = activity.currentFocus
            assertNotNull(focusedView)
            val focusedRow = recyclerView.findContainingItemView(focusedView!!)
            assertNotNull(focusedRow)
            assertTrue(recyclerView.getChildAdapterPosition(focusedRow!!) >= expandPosition)
        }
    }

    private fun settingsFragment(activity: StreamSettings): StreamSettings.SettingsFragment {
        return activity.supportFragmentManager.fragments
            .filterIsInstance<StreamSettings.SettingsFragment>()
            .single()
    }

    /** RESUMED precedes restored window focus on API 22; wait for the new view hierarchy. */
    private fun awaitRecreatedListFocus(oldRecyclerView: RecyclerView) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var ready = false
            activityRule.scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                val focusedView = activity.currentFocus
                ready = !oldRecyclerView.isAttachedToWindow && recycler !== oldRecyclerView &&
                    recycler != null && recycler.isAttachedToWindow && recycler.isLaidOut &&
                    !recycler.isLayoutRequested && activity.hasWindowFocus() &&
                    (focusedView == null || recycler.findContainingItemView(focusedView) != null)
            }
            if (ready) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        // The assertions in the caller report the actual final hierarchy/focus on timeout.
    }

    @SuppressLint("RestrictedApi")
    private fun findExpandPosition(recyclerView: RecyclerView): Int {
        val adapter = recyclerView.adapter as PreferenceGroupAdapter
        return (0 until adapter.itemCount).first { index ->
            adapter.getItem(index)?.javaClass?.name == ANDROIDX_EXPAND_BUTTON_CLASS
        }
    }

    private companion object {
        private const val ANDROIDX_EXPAND_BUTTON_CLASS = "androidx.preference.ExpandButton"
    }
}
