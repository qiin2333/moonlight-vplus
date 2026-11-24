package com.limelight.preferences

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class SettingsSearchRevealTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val settings by lazy { SettingsNavigationTestDriver(compose) }
    private val preferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
    }
    private lateinit var originalPreferences: Map<String, *>

    @Before fun setUp() {
        originalPreferences = snapshotSettingsPreferences(preferences)
        settings.launch()
        settings.withFragment { fragment ->
            requireNotNull(fragment.findPreference<CheckBoxPreference>("checkbox_enable_perf_overlay")).isChecked = false
        }
    }

    @After fun tearDown() {
        settings.close()
        restoreSettingsPreferences(preferences, originalPreferences)
    }

    @Test fun partiallyVisibleDisabledSearchTargetIsFullyRevealed() {
        val screenKey = "settings_performance_overlay"
        val targetKey = "list_perf_overlay_style"
        settings.openScreen(screenKey)
        var viewportHeight = 0
        settings.withFragment { fragment ->
            val list = fragment.listView
            val adapter = list.adapter as PreferenceGroupAdapter
            val position = adapter.getPreferenceAdapterPosition(targetKey)
            val row = requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView
            assertFalse(requireNotNull(fragment.findPreference<androidx.preference.Preference>(targetKey)).isEnabled)
            // On a fresh detail page this height cuts the target in half, while still
            // leaving enough room to display the entire target after scrolling.
            viewportHeight = row.top + row.height / 2
            assertTrue(viewportHeight >= row.height)
            fragment.closeSettingsScreen()
        }
        settings.awaitScreen(null)
        settings.search(targetKey)
        settings.withFragment { fragment ->
            fragment.listView.layoutParams = fragment.listView.layoutParams.apply { height = viewportHeight }
        }
        settings.tapPreference("settings.search.result.$screenKey.$targetKey")
        settings.awaitScreen(screenKey)
        settings.awaitReady { activity ->
            val list = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            val adapter = list.adapter as PreferenceGroupAdapter
            val position = adapter.getPreferenceAdapterPosition(targetKey)
            val row = list.findViewHolderForAdapterPosition(position)?.itemView
            row != null && row.top >= list.paddingTop && row.bottom <= list.height - list.paddingBottom
        }
    }
}
