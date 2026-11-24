package com.limelight.preferences

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class SettingsSearchInsetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val settings by lazy { SettingsNavigationTestDriver(compose) }

    @After fun tearDown() = settings.close()

    @Test fun portraitSearchDoesNotCoverCategoryAcrossDetailAndRecreation() {
        settings.launch()
        var landscape = false
        settings.withActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        if (landscape) settings.rotate()
        var originalTopPadding = 0
        settings.withActivity { activity ->
            originalTopPadding = activity.findViewById<View>(R.id.preference_container).paddingTop
            val toggle = activity.findViewById<View>(R.id.settings_search_toggle)
            toggle.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        val screen = "settings_input"
        val target = "list_esc_menu_key"
        settings.search(target)
        assertCategoryBelowSearchBar()

        settings.tapPreference("settings.search.result.$screen.$target")
        settings.awaitScreen(screen)
        settings.pressSystemBack()
        settings.awaitScreen(null)
        assertCategoryBelowSearchBar()

        settings.recreate()
        assertCategoryBelowSearchBar()
        settings.withActivity { it.findViewById<View>(R.id.settings_search_close).performClick() }
        settings.awaitReady { activity ->
            activity.findViewById<View>(R.id.settings_search_bar).visibility == View.GONE &&
                activity.findViewById<View>(R.id.preference_container).paddingTop == originalTopPadding
        }
        assertEquals("", settings.query())
    }

    private fun assertCategoryBelowSearchBar() {
        settings.withActivity { activity ->
            val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
        }
        settings.awaitReady { activity ->
            val bar = activity.findViewById<View>(R.id.settings_search_bar)
            val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            val adapter = list.adapter as PreferenceGroupAdapter
            val first = list.findViewHolderForAdapterPosition(0)?.itemView ?: return@awaitReady false
            val barLocation = IntArray(2)
            val rowLocation = IntArray(2)
            bar.getLocationOnScreen(barLocation)
            first.getLocationOnScreen(rowLocation)
            bar.isShown && adapter.getItem(0) is PreferenceCategory &&
                rowLocation[1] >= barLocation[1] + bar.height
        }
    }
}
