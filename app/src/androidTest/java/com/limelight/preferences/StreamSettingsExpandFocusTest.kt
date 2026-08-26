package com.limelight.preferences

import android.annotation.SuppressLint
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

        activityRule.scenario.onActivity { activity ->
            val newRecyclerView: RecyclerView =
                activity.findViewById(androidx.preference.R.id.recycler_view)
            assertNotSame(oldRecyclerView, newRecyclerView)
            assertTrue(newRecyclerView.isAttachedToWindow)
            activity.currentFocus?.let { focusedView ->
                assertTrue(newRecyclerView.findContainingItemView(focusedView) != null)
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
