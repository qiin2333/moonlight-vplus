package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardUIControllerTouchTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @Test
    fun numPageRemainsVisibleAndEmitsPairedTouchEvents() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) {
                    events.add(down to keyCode)
                }

                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
                override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
            }
            KeyboardUIController(parent, listener, activity).show()
            activity.setContentView(parent)
            parent.findViewById<View>(R.id.btn_key_page_num).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val numPage = activity.findViewById<View>(R.id.layout_num)
            val key = numPage.findViewWithTag<View>("k66")
            val rect = Rect()
            assertTrue(key.getLocalVisibleRect(rect))
            assertTrue(key.width - rect.width() <= 1)
            assertTrue(key.height - rect.height() <= 1)
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0)
                key.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        assertEquals(
            listOf(
                true to KeyEvent.KEYCODE_ENTER.toShort(),
                false to KeyEvent.KEYCODE_ENTER.toShort()
            ),
            events
        )
    }

    @Test
    fun accessibilityClickSendsPairedKeyEvents() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) {
                    events.add(down to keyCode)
                }

                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
                override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
            }
            KeyboardUIController(parent, listener, activity).show()
            activity.setContentView(parent)
            parent.findViewById<View>(R.id.btn_key_page_main).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.layout_main).findViewWithTag<View>("k45")?.performClick()
        }
        assertEquals(
            listOf(true to KeyEvent.KEYCODE_Q.toShort(), false to KeyEvent.KEYCODE_Q.toShort()),
            events
        )
    }

    @Test
    fun shiftedSymbolSendsShiftChord() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) {
                    events.add(down to keyCode)
                }

                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
                override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
            }
            KeyboardUIController(parent, listener, activity).show()
            activity.setContentView(parent)
            parent.findViewById<View>(R.id.btn_key_page_num).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val numPage = activity.findViewById<View>(R.id.layout_num)
            val key = numPage.findViewWithTag<View>("k1008")
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0)
                key.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        assertEquals(
            listOf(
                true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
                true to KeyEvent.KEYCODE_1.toShort(),
                false to KeyEvent.KEYCODE_1.toShort(),
                false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort()
            ),
            events
        )
    }

    @Test
    fun lockedShiftIsNotReleasedByShiftedSymbol() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) {
                    events.add(down to keyCode)
                }

                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
                override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
            }
            KeyboardUIController(parent, listener, activity).show()
            activity.setContentView(parent)
            parent.findViewById<View>(R.id.btn_key_page_main).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val shift = activity.findViewById<View>(R.id.layout_main).findViewWithTag<View>("k59")
            shift.performClick()
            shift.performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.btn_key_page_num).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.layout_num).findViewWithTag<View>("k1008")?.performClick()
        }
        assertTrue(events.any { it.first && it.second == KeyEvent.KEYCODE_SHIFT_LEFT.toShort() })
        assertFalse(events.any { !it.first && it.second == KeyEvent.KEYCODE_SHIFT_LEFT.toShort() })
    }

    @Test
    fun miniModeUsesCompactDockAndRestores() {
        activityRule.scenario.onActivity { activity ->
            activity.getSharedPreferences("keyboard_settings", 0).edit()
                .putBoolean("keyboard_mini", false)
                .commit()
            val density = activity.resources.displayMetrics.density
            val root = FrameLayout(activity)
            val parent = FrameLayout(activity)
            parent.layoutParams = FrameLayout.LayoutParams((360 * density).toInt(), FrameLayout.LayoutParams.MATCH_PARENT)
            root.addView(parent)
            KeyboardUIController(parent, NoOpKeyboardListener, activity).show()
            activity.setContentView(root)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var normalWidth = 0
        var miniWidth = 0
        activityRule.scenario.onActivity { activity ->
            val content = activity.findViewById<View>(R.id.keyboard_content)
            normalWidth = content.layoutParams.width
            activity.findViewById<View>(R.id.btn_keyboard_mini).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val content = activity.findViewById<View>(R.id.keyboard_content)
            miniWidth = content.layoutParams.width
            activity.findViewById<View>(R.id.btn_keyboard_mini).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val content = activity.findViewById<View>(R.id.keyboard_content)
            val density = activity.resources.displayMetrics.density
            assertEquals((348 * density).toInt(), normalWidth)
            assertEquals((336 * density).toInt(), miniWidth)
            assertTrue(miniWidth < normalWidth)
            assertEquals(normalWidth, content.layoutParams.width)
        }
    }

    @Test
    fun activeModifierShowsChipAndChipClearsIt() {
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            KeyboardUIController(parent, NoOpKeyboardListener, activity).show()
            activity.setContentView(parent)
            parent.findViewById<View>(R.id.btn_key_page_main).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val key = activity.findViewById<View>(R.id.layout_main).findViewWithTag<View>("k59")
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0)
                key.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val chips = activity.findViewById<ViewGroup>(R.id.keyboard_modifier_chips)
            assertEquals(View.VISIBLE, chips.visibility)
            assertTrue(chips.childCount > 0)
            chips.getChildAt(0).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val chips = activity.findViewById<View>(R.id.keyboard_modifier_chips)
            assertEquals(View.GONE, chips.visibility)
        }
    }

    @Test
    fun keyboardBodyConsumesBlankTouchesButFullscreenCarrierDoesNot() {
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            KeyboardUIController(parent, NoOpKeyboardListener, activity).show()

            val keyboardLayer = parent.findViewById<View>(R.id.layer_6_keyboard)
            val keyboardContent = parent.findViewById<View>(R.id.keyboard_content)

            assertFalse(parent.isClickable)
            assertFalse(keyboardLayer.isClickable)
            assertFalse(keyboardContent.isClickable)

            val eventTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                eventTime,
                eventTime,
                MotionEvent.ACTION_DOWN,
                8f,
                8f,
                0
            )
            val up = MotionEvent.obtain(
                eventTime,
                eventTime + 16,
                MotionEvent.ACTION_UP,
                8f,
                8f,
                0
            )
            try {
                assertTrue(keyboardContent.dispatchTouchEvent(down))
                assertTrue(keyboardContent.dispatchTouchEvent(up))
            } finally {
                down.recycle()
                up.recycle()
            }
        }
    }

    @Test
    fun keyboardVisibilityChangesAreReportedToListener() {
        val visibilityEvents = mutableListOf<Boolean>()
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) = Unit

                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit

                override fun onKeyboardVisibilityChanged(visible: Boolean) {
                    visibilityEvents.add(visible)
                }
            }
            val controller = KeyboardUIController(parent, listener, activity)
            controller.show()
            controller.hide()
        }
        assertEquals(listOf(true, false), visibilityEvents)
    }

    @Test
    fun virtualKeyboardSuppressionHidesAndRestoresCrownElementLayer() {
        activityRule.scenario.onActivity { activity ->
            // activity_game.xml uses a <merge> root, so construct only the hierarchy
            // required by ControllerManager instead of inflating the full game screen.
            val parent = FrameLayout(activity).apply {
                id = R.id.advance_setting_view
                addView(FrameLayout(activity).apply {
                    id = R.id.layer_2_element
                    visibility = View.VISIBLE
                })
                addView(FrameLayout(activity).apply {
                    id = R.id.super_pages_box
                })
            }
            val crownElementLayer = parent.findViewById<View>(R.id.layer_2_element)
            val manager = ControllerManager(parent, activity)

            manager.setVirtualElementsSuppressedByKeyboard(true)
            assertEquals(View.GONE, crownElementLayer.visibility)

            manager.setVirtualElementsSuppressedByKeyboard(false)
            assertEquals(View.VISIBLE, crownElementLayer.visibility)

            crownElementLayer.visibility = View.INVISIBLE
            manager.setVirtualElementsSuppressedByKeyboard(true)
            manager.setVirtualElementsSuppressedByKeyboard(false)
            assertEquals(View.INVISIBLE, crownElementLayer.visibility)
        }
    }

    @Test
    fun mainPageKeyEmitsPairedTouchEvents() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) {
                    events.add(down to keyCode)
                }

                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
                override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
            }
            KeyboardUIController(parent, listener, activity).show()
            activity.setContentView(parent)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.btn_key_page_main).performClick()
            val key = activity.findViewById<View>(R.id.layout_main).findViewWithTag<View>("k45")
            requireNotNull(key) { "main Q key is missing" }
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0)
                key.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        assertEquals(
            listOf(true to KeyEvent.KEYCODE_Q.toShort(), false to KeyEvent.KEYCODE_Q.toShort()),
            events
        )
    }

    private object NoOpKeyboardListener : KeyboardUIController.OnKeyboardEventListener {
        override fun sendKeyEvent(down: Boolean, keyCode: Short) = Unit

        override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
        override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
    }
}
