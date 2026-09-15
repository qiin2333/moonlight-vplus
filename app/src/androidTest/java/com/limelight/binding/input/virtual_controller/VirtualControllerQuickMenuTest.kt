package com.limelight.binding.input.virtual_controller

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.children
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualControllerQuickMenuTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java).setData(Uri.parse("about:blank")))
    private lateinit var controller: VirtualController
    private lateinit var frame: FrameLayout
    private lateinit var preferences: SharedPreferences
    private lateinit var profiles: SharedPreferences
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()
    private var savedProfiles: Map<String, *> = emptyMap<String, Any>()
    private val keys = listOf("list_osc_layout", "checkbox_only_show_L3R3", "checkbox_half_height_osc_portrait")

    @Before fun setup() {
        activityRule.scenario.onActivity { activity ->
            preferences = PreferenceManager.getDefaultSharedPreferences(activity)
            profiles = activity.getSharedPreferences("OSC", 0)
            savedPreferences = preferences.all.filterKeys { it in keys }
            savedProfiles = profiles.all
            preferences.edit().putString("list_osc_layout", "xbox")
                .putBoolean("checkbox_only_show_L3R3", false).putBoolean("checkbox_half_height_osc_portrait", true).commit()
            profiles.edit().clear().commit()
            frame = FrameLayout(activity)
            controller = VirtualController(null, frame, activity)
            activity.setContentView(frame)
            controller.refreshLayout()
        }
        idle()
    }

    @After fun restore() {
        activityRule.scenario.onActivity {
            controller.cleanup()
            preferences.edit().apply {
                keys.forEach { remove(it) }
                savedPreferences.forEach { (key, value) -> when(value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                } }
            }.commit()
            profiles.edit().clear().apply {
                savedProfiles.forEach { (key, value) -> if (value is String) putString(key, value) }
            }.commit()
        }
    }

    private fun idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    private fun openMenu() {
        activityRule.scenario.onActivity { frame.children.filterIsInstance<ImageButton>().single().performClick() }
        compose.waitForIdle()
    }

    @Test fun rebuildingDuringEditingPreservesUnsavedGeometry() {
        var expected = ""
        activityRule.scenario.onActivity {
            controller.startEditing(VirtualController.ControllerMode.MoveButtons)
            val element = controller.elements.first { it.elementId == 6 }
            val params = element.layoutParams as FrameLayout.LayoutParams
            params.leftMargin -= 17
            params.width += 3
            expected = element.configuration.toString()
            element.requestLayout()
            controller.refreshLayout()
        }
        idle()
        activityRule.scenario.onActivity {
            assertEquals(expected, controller.elements.first { it.elementId == 6 }.configuration.toString())
            assertEquals(VirtualController.ControllerMode.MoveButtons, controller.controllerMode)
        }
    }

    @Test fun menuSwitchesImmediatelyAndPersistsSelection() {
        activityRule.scenario.onActivity {
            val button = frame.children.filterIsInstance<ImageButton>().single()
            val density = it.resources.displayMetrics.density
            assertEquals((48 * density).toInt(), button.width)
            assertTrue(button.width - button.paddingLeft - button.paddingRight <= 25 * density)
            assertEquals(1f, button.alpha, 0f)
            assertNotNull(button.background)
            assertTrue(button.left < frame.width / 2)
            assertEquals(0, button.top)
        }
        openMenu()
        compose.onNodeWithText("DualSense (DS)").performScrollTo().performClick()
        idle()
        activityRule.scenario.onActivity {
            assertEquals(VirtualControllerLayout.DS, controller.layoutStyle)
            assertEquals("ds", preferences.getString("list_osc_layout", null))
            assertEquals(VirtualController.ControllerMode.Active, controller.controllerMode)
        }
        openMenu()
        compose.onNodeWithText("Nintendo Switch (NS)").performScrollTo().performClick()
        idle()
        activityRule.scenario.onActivity { assertEquals(VirtualControllerLayout.NS, controller.layoutStyle) }
    }

    @Test fun switchingFromEditorSavesOldLayoutAndReleasesInput() {
        openMenu()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.osc_action_move)).performScrollTo().performClick()
        idle()
        var savedX = 0
        activityRule.scenario.onActivity {
            assertEquals(VirtualController.ControllerMode.MoveButtons, controller.controllerMode)
            val element = controller.elements.first { it.elementId == 6 }
            val params = element.layoutParams as FrameLayout.LayoutParams
            params.leftMargin -= (2 * controller.profileScale).toInt()
            savedX = params.leftMargin
            element.requestLayout()
            controller.controllerInputContext.leftStickX = 123
        }
        openMenu()
        compose.onNodeWithText("DualSense (DS)").performScrollTo().performClick()
        idle()
        activityRule.scenario.onActivity {
            assertEquals(VirtualController.ControllerMode.Active, controller.controllerMode)
            assertEquals(0, controller.controllerInputContext.leftStickX.toInt())
            assertTrue(profiles.contains("xbox.full.6"))
        }
        openMenu()
        compose.onNodeWithText("Xbox").performScrollTo().performClick()
        idle()
        activityRule.scenario.onActivity {
            val restored = controller.elements.first { it.elementId == 6 }.layoutParams as FrameLayout.LayoutParams
            assertTrue(kotlin.math.abs(savedX - restored.leftMargin) <= controller.profileScale)
        }
    }
}
