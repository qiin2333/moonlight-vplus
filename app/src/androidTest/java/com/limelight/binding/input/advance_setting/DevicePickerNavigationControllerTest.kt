package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.descendants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevicePickerNavigationControllerTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    private lateinit var page: ViewGroup
    private lateinit var navigation: DevicePickerNavigationController
    private var selectedTag: String? = null
    private var selectionCount = 0
    private var dismissCount = 0
    private val destinations = listOf(
        R.id.device_nav_actions to R.id.device_actions,
        R.id.device_nav_keyboard to R.id.keyboard_drawing,
        R.id.device_nav_mouse to R.id.mouse_drawing,
        R.id.device_nav_gamepad to R.id.gamepad_drawing
    )

    @Test
    fun categoryButtonsShowExactlyOneContentAndMatchingHighlight() {
        installPicker()
        for (buttonId in listOf(R.id.device_nav_keyboard, R.id.device_nav_mouse,
            R.id.device_nav_gamepad, R.id.device_nav_actions, R.id.device_nav_keyboard)) {
            activityRule.scenario.onActivity { page.findViewById<View>(buttonId).performClick() }
            idle()
            assertActive(buttonId)
        }
    }

    @Test
    fun availabilitySurvivesSwitchesAndCanBeUpdatedWhenReopened() {
        installPicker()
        activityRule.scenario.onActivity { navigation.setAvailableDevices(false, true, true) }
        idle()
        assertActive(R.id.device_nav_mouse)
        activityRule.scenario.onActivity {
            assertEquals(View.GONE, page.findViewById<View>(R.id.device_nav_keyboard).visibility)
            page.findViewById<View>(R.id.device_nav_gamepad).performClick()
        }
        idle()
        assertActive(R.id.device_nav_gamepad)
        activityRule.scenario.onActivity { page.findViewById<View>(R.id.device_nav_mouse).performClick() }
        idle()
        assertActive(R.id.device_nav_mouse)
        activityRule.scenario.onActivity { navigation.setAvailableDevices(false, false, false) }
        idle()
        assertActive(R.id.device_nav_actions)
        activityRule.scenario.onActivity { navigation.setAvailableDevices(true, true, true) }
        idle()
        assertActive(R.id.device_nav_keyboard)
        activityRule.scenario.onActivity {
            destinations.forEach { (button, _) ->
                assertEquals(View.VISIBLE, page.findViewById<View>(button).visibility)
            }
        }
    }

    @Test
    fun firstTouchAfterCategorySwitchSelectsMouseKeyOnce() {
        installPicker()
        tap { page.findViewById(R.id.device_nav_mouse) }
        assertActive(R.id.device_nav_mouse)
        tap { page.findViewWithTag("m1") }
        assertEquals("m1", selectedTag)
        assertEquals(1, selectionCount)
    }

    @Test
    fun controllerCanEnterNewCategoriesSelectKeysAndDismiss() {
        installPicker()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (button in listOf(R.id.device_nav_mouse, R.id.device_nav_gamepad)) {
            activityRule.scenario.onActivity { assertTrue(page.findViewById<View>(button).requestFocus()) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
            idle()
            assertActive(button)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            idle()
            var focusedTag: String? = null
            activityRule.scenario.onActivity { activity ->
                val contentId = destinations.first { it.first == button }.second
                val content = page.findViewById<ViewGroup>(contentId)
                assertTrue("Focus must enter the selected category", content.descendants.any { it === activity.currentFocus })
                focusedTag = activity.currentFocus?.tag as? String
                assertTrue(focusedTag != null)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
            idle()
            assertEquals(focusedTag, selectedTag)
        }
        assertEquals(2, selectionCount)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B)
        idle()
        assertEquals(1, dismissCount)
    }

    private fun installPicker() {
        activityRule.scenario.onActivity { activity ->
            val container = FrameLayout(activity)
            page = LayoutInflater.from(activity).inflate(R.layout.page_device, container, false) as ViewGroup
            navigation = DevicePickerNavigationController(page)
            val keyboardRoot = page.findViewById<ViewGroup>(R.id.keyboard_drawing)
            val externalKeys = listOf(R.id.device_actions, R.id.mouse_drawing, R.id.gamepad_drawing)
                .flatMap { page.findViewById<ViewGroup>(it).descendants.filterIsInstance<TextView>().toList() }
                .filter { it.tag != null }
            externalKeys.forEach { key -> key.setOnClickListener { select(key) } }
            val keyboard = KeyboardKeyPickerController(
                keyboardRoot, ::select,
                externalViews = externalKeys + navigation.navigationViews + page.findViewById<View>(R.id.device_cancel),
                onDismiss = { dismissCount++ }
            )
            navigation.onCategoryChanged = { keyboard.refreshNavigation() }
            val density = activity.resources.displayMetrics.density
            container.addView(page, FrameLayout.LayoutParams(
                (320 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            ))
            activity.setContentView(container)
            navigation.setAvailableDevices(true, true, true)
        }
        idle()
    }

    private fun select(key: TextView) {
        selectedTag = key.tag.toString()
        selectionCount++
    }

    private fun assertActive(buttonId: Int) {
        activityRule.scenario.onActivity {
            destinations.forEach { (button, content) ->
                assertEquals("Category highlight must match content", button == buttonId,
                    page.findViewById<View>(button).isSelected)
                assertEquals(if (button == buttonId) View.VISIBLE else View.GONE,
                    page.findViewById<View>(content).visibility)
            }
        }
    }

    private fun tap(findView: () -> View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(true)
        val bounds = Rect()
        activityRule.scenario.onActivity { assertTrue(findView().getGlobalVisibleRect(bounds)) }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        idle()
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}
