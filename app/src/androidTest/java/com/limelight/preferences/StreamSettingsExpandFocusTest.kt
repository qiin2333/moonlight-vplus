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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamSettingsExpandFocusTest {

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
            assertTrue(expandRow!!.requestFocus())

            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
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

    private companion object {
        private const val ANDROIDX_EXPAND_BUTTON_CLASS = "androidx.preference.ExpandButton"
    }
}
