package com.limelight.preferences

import android.view.KeyEvent
import android.widget.TextView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.Preference
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownSettingsNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val settings by lazy { SettingsNavigationTestDriver(compose) }

    @After fun close() = settings.close()

    @Test fun crownSettingsUseTheSameTextAlignmentAsOtherSettings() {
        settings.launch()
        val crownKeys = listOf("checkbox_show_onscreen_keyboard", "crown_config_management",
            "crown_store_view", "crown_import_package")
        settings.withFragment { fragment ->
            crownKeys.forEach { key ->
                val preference = requireNotNull(fragment.findPreference<Preference>(key))
                assertNull("$key must not have a leading icon", preference.icon)
                assertFalse("$key must not reserve icon space", preference.isIconSpaceReserved)
            }
        }
        fun titleStart(key: String): Int {
            settings.focusPreference(key)
            var x = 0
            settings.withActivity { activity ->
                val title = requireNotNull(activity.currentFocus?.findViewById<TextView>(android.R.id.title))
                val location = IntArray(2)
                title.getLocationOnScreen(location)
                x = location[0]
            }
            return x
        }
        val normalStart = titleStart("checkbox_enable_sops")
        crownKeys.forEach { key -> assertEquals("$key should align with regular settings", normalStart, titleStart(key)) }
    }

    @Test fun crownEntriesOpenDistinctDestinations() {
        settings.launch()
        val keys = settings.adapterKeys()
        assertTrue("Local profile management must remain available", "crown_config_management" in keys)
        assertTrue("The community store must remain available", "crown_store_view" in keys)
        assertTrue("Package import must remain available", "crown_import_package" in keys)
        assertFalse("The misleading duplicate edit entry must be removed", "crown_edit_current" in keys)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for ((key, opensMyCrowns) in listOf("crown_config_management" to true, "crown_store_view" to false)) {
            val monitor = instrumentation.addMonitor(CrownStoreActivity::class.java.name, null, false)
            try {
                settings.focusPreference(key)
                settings.press(KeyEvent.KEYCODE_BUTTON_A)
                val launched = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                assertNotNull("$key must open Crown", launched)
                assertTrue("$key must open the intended tab",
                    requireNotNull(launched).intent.getBooleanExtra(CrownStoreActivity.EXTRA_OPEN_MY_CROWNS, false) == opensMyCrowns)
                instrumentation.runOnMainSync { launched.finish() }
                settings.awaitScreen(null)
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }
}
