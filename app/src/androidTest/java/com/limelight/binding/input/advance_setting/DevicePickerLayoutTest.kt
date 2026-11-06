package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.core.view.doOnLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 23)
class DevicePickerLayoutTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    private lateinit var page: SuperPageLayout
    private lateinit var keyboard: KeyboardKeyPickerController
    private lateinit var navigation: DevicePickerNavigationController

    @Test
    fun rotatingTheOpenPickerPreservesKeyShapeAndAccessToEveryDevice() {
        installPicker()
        for ((orientation, name) in listOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait"
        )) {
            activityRule.scenario.onActivity { it.requestedOrientation = orientation }
            waitFor {
                // The localized inflation context has its own configuration;
                // the actual host viewport is the source of truth after rotation.
                val host = page.parent as View
                if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    host.width > host.height
                } else {
                    host.height > host.width
                }
            }
            reveal(R.id.device_nav_keyboard, R.id.keyboard_drawing)
            capture("$name-keyboard")
            activityRule.scenario.onActivity {
                val key = page.findViewById<ViewGroup>(R.id.keyboard_picker_main)
                    .findViewWithTag<View>("k45")
                val density = page.resources.displayMetrics.density
                assertTrue("Standard keys must remain at least 32dp wide", key.width / density >= 32)
                assertTrue("Standard keys must stay close to square: ${key.width}x${key.height}, " +
                    "keyboard=${page.findViewById<View>(R.id.keyboard_drawing).width}x" +
                    "${page.findViewById<View>(R.id.keyboard_drawing).height}",
                    key.width.toFloat() / key.height in 0.8f..1.2f)
                assertTrue("Sheet must remain within the rotated viewport", page.x >= 0)
                assertTrue(page.x + page.width <= (page.parent as View).width)
                val canvasScroll = page.findViewById<HorizontalScrollView>(R.id.device_keyboard_scroll)
                val canvas = page.findViewById<View>(R.id.device_keyboard_pages)
                if (name == "landscape") {
                    assertTrue("Landscape keyboard must fit without clipping its right edge",
                        canvas.width <= canvasScroll.width)
                } else {
                    assertTrue("Portrait keyboard must preserve its usable canvas width",
                        canvas.width > canvasScroll.width)
                }
            }

            if (name == "portrait") {
                swipeKeyboardToEnd()
                activityRule.scenario.onActivity {
                    val lastKey = page.findViewById<ViewGroup>(R.id.keyboard_picker_main)
                        .findViewWithTag<View>("k73")
                    val visible = Rect()
                    assertTrue(lastKey.getLocalVisibleRect(visible))
                    assertEquals("Swipe must reveal the rightmost keys", lastKey.width, visible.width())
                    val tabs = page.findViewById<View>(R.id.keyboard_picker_tabs)
                    assertTrue(tabs.getLocalVisibleRect(visible))
                    assertEquals("Keyboard tabs must stay fixed while panning", tabs.width, visible.width())
                    assertTrue(page.findViewById<View>(R.id.device_nav_keyboard).isSelected)
                    assertEquals("Keyboard pan must not move the outer device strip",
                        page.findViewById<View>(R.id.keyboard_drawing).left,
                        page.findViewById<HorizontalScrollView>(R.id.device_scroll).scrollX)
                }
                capture("$name-keyboard-scrolled")
                activityRule.scenario.onActivity {
                    page.findViewById<HorizontalScrollView>(R.id.device_keyboard_scroll).scrollTo(0, 0)
                }
            }

            activityRule.scenario.onActivity {
                keyboard.showPage(KeyboardKeyPickerController.Page.NAV, requestContentFocus = false)
            }
            capture("$name-function")
            activityRule.scenario.onActivity {
                keyboard.showPage(KeyboardKeyPickerController.Page.NUM, requestContentFocus = false)
            }
            capture("$name-numpad")
            activityRule.scenario.onActivity {
                val numpad = page.findViewById<ViewGroup>(R.id.keyboard_picker_num)
                val key = numpad.findViewWithTag<View>("k151")
                assertTrue("Numpad keys must stay close to square", key.width.toFloat() / key.height in 0.8f..1.2f)
                val visible = Rect()
                assertTrue(numpad.getLocalVisibleRect(visible))
                assertEquals("Numpad must fit without horizontal scrolling", numpad.width, visible.width())
            }
            for ((button, target, suffix) in listOf(
                Triple(R.id.device_nav_mouse, R.id.mouse_drawing, "mouse"),
                Triple(R.id.device_nav_gamepad, R.id.gamepad_drawing, "gamepad")
            )) {
                reveal(button, target)
                activityRule.scenario.onActivity {
                    val targetView = page.findViewById<View>(target)
                    val visible = Rect()
                    assertTrue(targetView.getLocalVisibleRect(visible))
                    assertEquals("Device drawing must be fully visible", targetView.width, visible.width())
                    assertEquals(targetView.height, visible.height())
                }
                capture("$name-$suffix")
            }
            activityRule.scenario.onActivity {
                keyboard.showPage(KeyboardKeyPickerController.Page.MAIN, requestContentFocus = false)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun installPicker() {
        activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            val host = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(16, 21, 28)) }
            val configuration = Configuration().apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
            }
            val localized = ContextThemeWrapper(activity.createConfigurationContext(configuration), 0)
                .apply { theme.setTo(activity.theme) }
            page = LayoutInflater.from(localized).inflate(R.layout.page_device, host, false) as SuperPageLayout
            navigation = DevicePickerNavigationController(page)
            keyboard = KeyboardKeyPickerController(
                root = page.findViewById(R.id.keyboard_drawing),
                onKeySelected = {},
                externalViews = navigation.navigationViews
            )
            activity.setContentView(host)
            val pages = SuperPagesController(host, activity)
            host.doOnLayout {
                host.post {
                    pages.openNewPage(page, fitToViewport = true)
                    navigation.refresh()
                }
            }
        }
        waitFor { page.parent != null && page.width > 0 && page.x >= 0 }
    }

    private fun reveal(buttonId: Int, targetId: Int) {
        activityRule.scenario.onActivity { page.findViewById<View>(buttonId).performClick() }
        waitFor {
            val scroll = page.findViewById<HorizontalScrollView>(R.id.device_scroll)
            val target = page.findViewById<View>(targetId)
            val maxScroll = scroll.getChildAt(0).width - scroll.width + scroll.paddingLeft + scroll.paddingRight
            abs(scroll.scrollX - target.left.coerceAtMost(maxScroll.coerceAtLeast(0))) <= 1
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        if (InstrumentationRegistry.getArguments().getString("captureDevicePicker") != "true") return
        SystemClock.sleep(150) // Let the last rendered frame reach the display before capture.
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "device-picker-qa")
        directory.mkdirs()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    private fun swipeKeyboardToEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bounds = Rect()
        activityRule.scenario.onActivity {
            page.findViewById<View>(R.id.device_keyboard_scroll).getGlobalVisibleRect(bounds)
        }
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..21) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                21 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val x = bounds.right - bounds.width() * (0.05f + 0.9f * step.coerceAtMost(20) / 20f)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                x, bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.sendPointerSync(event)
            event.recycle()
            SystemClock.sleep(16)
        }
        instrumentation.waitForIdleSync()
        waitFor {
            val scroll = page.findViewById<HorizontalScrollView>(R.id.device_keyboard_scroll)
            val canvas = page.findViewById<View>(R.id.device_keyboard_pages)
            scroll.scrollX >= canvas.width - scroll.width - 1
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var passed = false
        while (!passed && SystemClock.uptimeMillis() < deadline) {
            activityRule.scenario.onActivity { passed = condition() }
            if (!passed) SystemClock.sleep(25)
        }
        if (!passed) capture("failure")
        assertTrue("Picker must finish layout/navigation within five seconds: " +
            "parent=${page.parent}, bounds=${page.x},${page.y} ${page.width}x${page.height}", passed)
    }
}
