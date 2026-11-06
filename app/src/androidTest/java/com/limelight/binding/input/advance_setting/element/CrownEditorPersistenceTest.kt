package com.limelight.binding.input.advance_setting.element

import android.content.ContentValues
import android.content.Intent
import android.view.KeyEvent
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.Game
import com.limelight.binding.input.advance_setting.ControllerManager
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownEditorPersistenceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Suppress("DEPRECATION")
    @Test fun groupSwitchAndWidthSurviveClosingAndReloadingTheEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val profile = -System.currentTimeMillis()
        var database: SuperConfigDatabaseHelper? = null
        lateinit var manager: ControllerManager
        lateinit var page: SuperPageLayout
        try {
            activity.scenario.onActivity { host ->
                // Attach a real Game context without starting a streaming connection.
                val intent = Intent(host, Game::class.java)
                val game = instrumentation.newActivity(Game::class.java, host, host.window.decorView.windowToken,
                    host.application, intent, host.packageManager.getActivityInfo(intent.component!!, 0),
                    "Crown test", null, null, null) as Game
                game.setTheme(host.resources.getIdentifier("AppTheme", "style", host.packageName))
                fun id(name: String) = host.resources.getIdentifier(name, "id", host.packageName)
                val root = FrameLayout(game).apply {
                    this.id = id("advance_setting_view")
                    addView(FrameLayout(game).apply { this.id = id("layer_2_element") })
                    addView(FrameLayout(game).apply { this.id = id("super_pages_box") })
                }
                host.setContentView(root)
                manager = ControllerManager(root, game)
                database = manager.superConfigDatabaseHelper
                database!!.insertConfig(ContentValues().apply { put("config_id", profile); put("config_name", "控件回归") })
                database!!.insertElement(GroupButton.getInitialInfo().apply { put("config_id", profile); put("element_id", 1L) })
                manager.elementController!!.loadAllElement(profile)
                page = (manager.elementController!!.elements.single() as GroupButton).infoPage
                manager.elementController!!.toggleInfoPage(page)
            }
            instrumentation.waitForIdleSync()
            activity.scenario.onActivity { host ->
                fun id(name: String) = host.resources.getIdentifier(name, "id", host.packageName)
                page.findViewById<CompoundButton>(id("page_group_button_child_visible")).performClick()
                val width = page.findViewById<NumberSeekbar>(id("page_group_button_width"))
                val before = width.value
                val slider = width.findViewById<SeekBar>(id("number_seekbar_seekbar"))
                slider.requestFocus()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    slider.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_DPAD_RIGHT))
                }
                val changedWidth = width.value
                assertTrue("Controller input must change the visible width", changedWidth > before)
                page.pageReturn()
                manager.elementController!!.loadAllElement(profile)
                val reopened = (manager.elementController!!.elements.single() as GroupButton).infoPage
                manager.elementController!!.toggleInfoPage(reopened)
                assertFalse(reopened.findViewById<CompoundButton>(id("page_group_button_child_visible")).isChecked)
                assertEquals(changedWidth, reopened.findViewById<NumberSeekbar>(id("page_group_button_width")).value)
            }
        } finally {
            activity.scenario.onActivity { database?.deleteConfig(profile); database?.close() }
        }
    }
}
