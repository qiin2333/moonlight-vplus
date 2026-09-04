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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.utils.KeyCodeMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class KeyboardKeyPickerControllerTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @Test
    fun navigationPageExposesPrintScreenAndButtonASelectsItOnce() {
        val selectedTag = AtomicReference<String?>()
        val selectionCount = AtomicInteger()

        activityRule.scenario.onActivity { activity ->
            val container = FrameLayout(activity)
            val page = LayoutInflater.from(activity).inflate(
                R.layout.page_device,
                container,
                false
            ) as ViewGroup
            val picker = page.findViewById<ViewGroup>(R.id.keyboard_drawing)
            container.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            activity.setContentView(container)

            val controller = KeyboardKeyPickerController(
                root = picker,
                onKeySelected = { key ->
                    selectedTag.set(key.tag.toString())
                    selectionCount.incrementAndGet()
                }
            )
            controller.showPage(
                KeyboardKeyPickerController.Page.NAV,
                requestContentFocus = true
            )
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val printScreen = activity.findViewById<ViewGroup>(R.id.keyboard_drawing)
                .findViewWithTag<TextView>("k120")
            assertTrue(printScreen.isShown)
            assertTrue(printScreen.requestFocus())
        }

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.waitForIdleSync()
        assertEquals("k120", selectedTag.get())
        assertEquals(1, selectionCount.get())

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            assertEquals("k118", activity.currentFocus?.tag)
        }
    }

    @Test
    fun tabDownMovesFocusIntoVisibleKeyboardPage() {
        activityRule.scenario.onActivity { activity ->
            val page = LayoutInflater.from(activity).inflate(
                R.layout.page_device,
                null,
                false
            ) as ViewGroup
            val picker = page.findViewById<ViewGroup>(R.id.keyboard_drawing)
            activity.setContentView(page)
            KeyboardKeyPickerController(root = picker, onKeySelected = {})
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.keyboard_picker_tab_main).requestFocus())
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val focused = activity.currentFocus
            assertTrue(focused != null)
            assertNotEquals(R.id.keyboard_picker_tab_main, focused?.id)
            assertTrue((focused?.tag as? String)?.startsWith("k") == true)
        }
    }

    @Test
    fun firstTouchSelectsAnUnfocusedKeyAndEachTab() {
        val count = AtomicInteger()
        val selected = AtomicReference<String?>()
        installPicker(370, 220) {
            count.incrementAndGet()
            selected.set(it.tag.toString())
        }
        tap { it.findViewWithTag("k51") }
        assertEquals("k51", selected.get())
        assertEquals(1, count.get())

        tap { it.findViewById(R.id.keyboard_picker_tab_nav) }
        tap { it.findViewWithTag("k120") }
        assertEquals("k120", selected.get())
        assertEquals(2, count.get())

        tap { it.findViewById(R.id.keyboard_picker_tab_num) }
        tap { it.findViewWithTag("k144") }
        assertEquals("k144", selected.get())
        assertEquals(3, count.get())

        tap { it.findViewById(R.id.keyboard_picker_tab_main) }
        tap { it.findViewWithTag("k29") }
        assertEquals("k29", selected.get())
        assertEquals(4, count.get())
    }

    @Test
    fun customKeyDialogActionsRespondToFirstTouch() {
        val actions = listOf(R.id.button_clear_keys, R.id.button_save_key, R.id.button_close_dialog)
        val count = AtomicInteger()
        activityRule.scenario.onActivity { activity ->
            val dialogView = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_add_custom_key, null, false) as ViewGroup
            val buttons = actions.map { id -> dialogView.findViewById<View>(id) }
            buttons.forEach { it.setOnClickListener { count.incrementAndGet() } }
            activity.setContentView(dialogView)
            KeyboardKeyPickerController(
                root = dialogView.findViewById(R.id.keyboard_drawing),
                onKeySelected = {},
                externalViews = buttons
            ).requestInitialFocus()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        actions.forEachIndexed { index, id ->
            tap { it.findViewById(id) }
            assertEquals(index + 1, count.get())
        }
    }

    @Test
    fun cancelledTouchDoesNotSelectKey() {
        val count = AtomicInteger()
        installPicker(370, 220) { count.incrementAndGet() }
        activityRule.scenario.onActivity { activity ->
            val key = activity.findViewById<ViewGroup>(R.id.keyboard_drawing)
                .findViewWithTag<View>("k51")
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                val event = MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0)
                key.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, count.get())
    }

    @Test
    fun numpadFitsNarrowAndShortPickerBounds() {
        for ((width, height) in listOf(370 to 220, 280 to 220, 600 to 180)) {
            val controller = installPicker(width, height) {}
            activityRule.scenario.onActivity {
                controller.showPage(KeyboardKeyPickerController.Page.NUM, requestContentFocus = false)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            activityRule.scenario.onActivity { activity ->
                val picker = activity.findViewById<ViewGroup>(R.id.keyboard_drawing)
                val numpad = picker.findViewById<ViewGroup>(R.id.keyboard_picker_num)
                val codes = mutableSetOf<Int>()
                collectKeyCodes(numpad, codes)
                assertEquals(17, codes.size)
                codes.forEach { code ->
                    val key = numpad.findViewWithTag<TextView>("k$code")
                    val visible = Rect()
                    assertTrue("k$code visible in $width x $height", key.getLocalVisibleRect(visible))
                    assertEquals("k$code width in $width x $height", key.width, visible.width())
                    assertEquals("k$code height in $width x $height", key.height, visible.height())
                    assertTrue(key.width > 0 && key.height > 0)
                }
            }
        }
    }

    @Test
    fun touchThenControllerNavigationAndConfirmStillWork() {
        val count = AtomicInteger()
        val selected = AtomicReference<String?>()
        installPicker(370, 220) {
            count.incrementAndGet()
            selected.set(it.tag.toString())
        }
        tap { it.findViewById(R.id.keyboard_picker_tab_num) }
        tap { it.findViewWithTag("k151") }
        assertEquals("k151", selected.get())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        instrumentation.waitForIdleSync()
        assertEquals("k152", selected.get())
        assertEquals(2, count.get())
    }

    @Test
    fun everyVisiblePickerKeyCanBeSentAndStored() {
        activityRule.scenario.onActivity { activity ->
            val page = LayoutInflater.from(activity).inflate(
                R.layout.page_device,
                null,
                false
            ) as ViewGroup
            val picker = page.findViewById<ViewGroup>(R.id.keyboard_drawing)
            val keyCodes = mutableSetOf<Int>()
            collectKeyCodes(picker, keyCodes)
            val translator = KeyboardTranslator()

            assertTrue(keyCodes.isNotEmpty())
            keyCodes.forEach { keyCode ->
                assertNotEquals(0, translator.translate(keyCode, -1).toInt())
                assertNotNull(KeyCodeMapper.getWindowsKeyCode(keyCode))
                assertNotNull(KeyCodeMapper.getDisplayName(keyCode))
            }
        }
    }

    private fun installPicker(
        widthDp: Int,
        heightDp: Int,
        onKeySelected: (TextView) -> Unit
    ): KeyboardKeyPickerController {
        val controller = AtomicReference<KeyboardKeyPickerController>()
        activityRule.scenario.onActivity { activity ->
            val container = FrameLayout(activity)
            val page = LayoutInflater.from(activity)
                .inflate(R.layout.page_device, container, false) as ViewGroup
            val picker = page.findViewById<ViewGroup>(R.id.keyboard_drawing)
            (picker.parent as ViewGroup).removeView(picker)
            val density = activity.resources.displayMetrics.density
            container.addView(picker, FrameLayout.LayoutParams(
                (widthDp * density).toInt(), (heightDp * density).toInt()
            ))
            activity.setContentView(container)
            controller.set(KeyboardKeyPickerController(picker, onKeySelected))
            controller.get().requestInitialFocus()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return controller.get()
    }

    private fun tap(findView: (ViewGroup) -> View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(true)
        val center = IntArray(2)
        activityRule.scenario.onActivity { activity ->
            val view = findView(activity.findViewById(android.R.id.content))
            assertTrue(view.isShown)
            view.getLocationOnScreen(center)
            center[0] += view.width / 2
            center[1] += view.height / 2
        }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, center[0].toFloat(), center[1].toFloat(), 0
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun collectKeyCodes(view: View, output: MutableSet<Int>) {
        if (view is TextView) {
            (view.tag as? String)
                ?.takeIf { it.matches(KEY_TAG_PATTERN) }
                ?.removePrefix("k")
                ?.toIntOrNull()
                ?.let(output::add)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectKeyCodes(view.getChildAt(index), output)
            }
        }
    }

    companion object {
        private val KEY_TAG_PATTERN = Regex("k\\d+")
    }
}
