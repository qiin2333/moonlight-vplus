package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
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
            picker.findViewById<View>(R.id.keyboard_picker_tab_main).requestFocus()
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
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
