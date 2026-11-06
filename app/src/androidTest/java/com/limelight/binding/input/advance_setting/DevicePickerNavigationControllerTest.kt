package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    @Test
    fun mouseAndGamepadAreReachableFromPersistentNavigation() {
        installPicker()
        for ((buttonId, targetId) in listOf(
            R.id.device_nav_mouse to R.id.mouse_drawing,
            R.id.device_nav_gamepad to R.id.gamepad_drawing,
            R.id.device_nav_actions to R.id.device_actions
        )) {
            activityRule.scenario.onActivity { activity ->
                val button = activity.findViewById<View>(buttonId)
                assertTrue(button.getGlobalVisibleRect(android.graphics.Rect()))
                button.performClick()
            }
            waitForDestination(targetId)
        }
    }

    @Test
    fun hiddenLayoutsHaveNoShortcutAndDoNotOffsetOtherDestinations() {
        installPicker(keyboardVisible = false)
        activityRule.scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.device_nav_keyboard).visibility)
            activity.findViewById<View>(R.id.device_nav_mouse).performClick()
        }
        waitForDestination(R.id.mouse_drawing)
    }

    private fun installPicker(keyboardVisible: Boolean = true) {
        activityRule.scenario.onActivity { activity ->
            val container = FrameLayout(activity)
            val page = LayoutInflater.from(activity).inflate(R.layout.page_device, container, false)
            page.findViewById<View>(R.id.keyboard_drawing).visibility =
                if (keyboardVisible) View.VISIBLE else View.GONE
            DevicePickerNavigationController(page).refresh()
            val density = activity.resources.displayMetrics.density
            container.addView(page, FrameLayout.LayoutParams(
                (320 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            ))
            activity.setContentView(container)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForDestination(targetId: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        var visible = false
        while (!visible && System.currentTimeMillis() < deadline) {
            activityRule.scenario.onActivity { activity ->
                val scroll = activity.findViewById<HorizontalScrollView>(R.id.device_scroll)
                val target = activity.findViewById<View>(targetId)
                // Each complete target fits this viewport. The last destination
                // may clamp to the end of the strip rather than align at its start.
                visible = target.left >= scroll.scrollX &&
                    target.right <= scroll.scrollX + scroll.width - scroll.paddingLeft - scroll.paddingRight
            }
            if (!visible) Thread.sleep(25)
        }
        assertTrue("Destination $targetId must be visible without swiping", visible)
    }
}
