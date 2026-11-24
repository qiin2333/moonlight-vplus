package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
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

internal fun findShiftedOne(root: View): View {
    fun find(view: View): KeyboardKeyView? {
        if (view is KeyboardKeyView && view.keySpec?.let {
                it.code == KeyEvent.KEYCODE_1 && it.shift && it.label == "!"
            } == true) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            find(view.getChildAt(i))?.let { return it }
        }
        return null
    }
    return checkNotNull(find(root)) { "Shifted 1 key is missing" }
}

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
            val key = findShiftedOne(numPage)
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
        var symbolStart = 0
        activityRule.scenario.onActivity { activity ->
            symbolStart = events.size
            val symbol = findShiftedOne(activity.findViewById(R.id.layout_num))
            symbol.performClick()
        }
        val symbolEvents = events.drop(symbolStart)
        assertTrue(events.any { it.first && it.second == KeyEvent.KEYCODE_SHIFT_LEFT.toShort() })
        assertTrue(symbolEvents.any { it.first && it.second == KeyEvent.KEYCODE_1.toShort() })
        assertFalse(symbolEvents.any { !it.first && it.second == KeyEvent.KEYCODE_SHIFT_LEFT.toShort() })
    }

    @Test
    fun syntheticShiftReleasesWhenOnlyRightShiftIsActive() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        var controller: KeyboardUIController? = null
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) {
                    events.add(down to keyCode)
                }

                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
                override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
            }
            val c = KeyboardUIController(parent, listener, isolatedKeyboardContext(activity))
            c.show()
            controller = c
            activity.setContentView(parent)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val c = checkNotNull(controller)
            val symbol = findShiftedOne(activity.findViewById(R.id.layout_num))
            c.onKeyPress(symbol, KeyEvent.KEYCODE_1)
            c.onKeyPress(symbol, KeyEvent.KEYCODE_SHIFT_RIGHT)
            assertEquals(listOf(
                true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
                true to KeyEvent.KEYCODE_1.toShort(),
                true to KeyEvent.KEYCODE_SHIFT_RIGHT.toShort(),
                false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort()
            ), events)
            c.onKeyRelease(symbol, KeyEvent.KEYCODE_1)
            assertEquals(listOf(
                true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
                true to KeyEvent.KEYCODE_1.toShort(),
                true to KeyEvent.KEYCODE_SHIFT_RIGHT.toShort(),
                false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
                false to KeyEvent.KEYCODE_1.toShort()
            ), events)
        }
    }

    @Test
    fun shiftedKeyKeepsShiftWhenExplicitModifierIsCleared() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        lateinit var controller: KeyboardUIController
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) { events.add(down to keyCode) }
                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
            }
            controller = KeyboardUIController(parent, listener, isolatedKeyboardContext(activity))
            controller.show()
            activity.setContentView(parent)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val symbol = findShiftedOne(activity.findViewById(R.id.layout_num))
            val left = KeyEvent.KEYCODE_SHIFT_LEFT
            controller.onKeyPress(symbol, left)
            controller.onKeyRelease(symbol, left)
            controller.onKeyPress(symbol, KeyEvent.KEYCODE_1)
            val chips = activity.findViewById<ViewGroup>(R.id.keyboard_modifier_chips)
            assertTrue(chips.getChildAt(0).performClick())
            assertEquals(View.GONE, chips.visibility)
            assertEquals(listOf(
                true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
                true to KeyEvent.KEYCODE_1.toShort()
            ), events)
            controller.onKeyRelease(symbol, KeyEvent.KEYCODE_1)
        }
        assertEquals(listOf(
            true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
            true to KeyEvent.KEYCODE_1.toShort(),
            false to KeyEvent.KEYCODE_1.toShort(),
            false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort()
        ), events)
    }

    @Test
    fun heldShiftedKeyTakesOverWhenRightShiftIsReleased() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        lateinit var controller: KeyboardUIController
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) { events.add(down to keyCode) }
                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
            }
            controller = KeyboardUIController(parent, listener, isolatedKeyboardContext(activity))
            controller.show()
            activity.setContentView(parent)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val symbol = findShiftedOne(activity.findViewById(R.id.layout_num))
            val right = KeyEvent.KEYCODE_SHIFT_RIGHT
            controller.onKeyPress(symbol, right)
            controller.onKeyPress(symbol, KeyEvent.KEYCODE_1)
            controller.onModifierHoldRelease(symbol, right)
            controller.onKeyRelease(symbol, KeyEvent.KEYCODE_1)
        }
        assertEquals(listOf(
            true to KeyEvent.KEYCODE_SHIFT_RIGHT.toShort(),
            true to KeyEvent.KEYCODE_1.toShort(),
            true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
            false to KeyEvent.KEYCODE_SHIFT_RIGHT.toShort(),
            false to KeyEvent.KEYCODE_1.toShort(),
            false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort()
        ), events)
    }

    @Test
    fun sharedKeyCodeStaysPressedUntilLastViewReleasesOrCancels() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { activity ->
            val controller = KeyboardUIController(FrameLayout(activity), object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) { events.add(down to keyCode) }
                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
            }, isolatedKeyboardContext(activity))
            fun key(spec: KeySpec): KeyboardKeyView = KeyboardKeyView(activity).apply {
                bind(spec, 40f) { }
                tag = "k${spec.code}"
                val detector = KeyboardGestureDetector(controller)
                setOnTouchListener { view, event -> detector.onTouchEvent(view, event) }
            }
            fun touch(view: View, action: Int) {
                val time = SystemClock.uptimeMillis()
                MotionEvent.obtain(time, time, action, 1f, 1f, 0).also {
                    view.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            val minus = KeyEvent.KEYCODE_MINUS
            val plain = key(KeySpec(code = minus, label = "-"))
            val shifted = key(KeySpec(code = minus, label = "_", shift = true))
            touch(plain, MotionEvent.ACTION_DOWN)
            touch(shifted, MotionEvent.ACTION_DOWN)
            touch(plain, MotionEvent.ACTION_CANCEL)
            assertEquals(listOf(
                true to minus.toShort(), true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort()
            ), events)
            touch(shifted, MotionEvent.ACTION_UP)
            assertEquals(listOf(
                true to minus.toShort(), true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
                false to minus.toShort(), false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort()
            ), events)

            events.clear()
            touch(plain, MotionEvent.ACTION_DOWN)
            touch(shifted, MotionEvent.ACTION_DOWN)
            touch(shifted, MotionEvent.ACTION_UP)
            assertEquals(listOf(
                true to minus.toShort(), true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
                false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort()
            ), events)
            touch(plain, MotionEvent.ACTION_UP)
            assertEquals(listOf(
                true to minus.toShort(), true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(),
                false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(), false to minus.toShort()
            ), events)

            events.clear()
            val left = KeyEvent.KEYCODE_DPAD_LEFT
            val first = key(KeySpec(code = left, label = "←"))
            val second = key(KeySpec(code = left, label = "←"))
            touch(first, MotionEvent.ACTION_DOWN)
            touch(second, MotionEvent.ACTION_DOWN)
            touch(first, MotionEvent.ACTION_CANCEL)
            assertEquals(listOf(true to left.toShort()), events)
            touch(second, MotionEvent.ACTION_UP)
            assertEquals(listOf(true to left.toShort(), false to left.toShort()), events)

            events.clear()
            controller.onKeyPress(first, left)
            controller.onKeyPress(first, left)
            controller.onKeyRelease(second, left)
            assertEquals(listOf(true to left.toShort()), events)
            controller.onKeyRelease(first, left)
            assertEquals(listOf(true to left.toShort(), false to left.toShort()), events)
            controller.onKeyRelease(first, left)
            assertEquals(listOf(true to left.toShort(), false to left.toShort()), events)

            events.clear()
            val shift = KeyEvent.KEYCODE_SHIFT_LEFT
            controller.onKeyPress(first, shift)
            controller.onKeyRelease(first, shift)
            controller.onKeyRelease(second, left)
            assertEquals(listOf(true to shift.toShort()), events)
            controller.onDoubleTap(first, shift)
            assertEquals(listOf(true to shift.toShort(), false to shift.toShort()), events)
        }
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
    fun narrowDockKeepsHideVisible() {
        activityRule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val root = FrameLayout(activity)
            val parent = FrameLayout(activity)
            parent.layoutParams = FrameLayout.LayoutParams((240 * density).toInt(), FrameLayout.LayoutParams.MATCH_PARENT)
            root.addView(parent)
            KeyboardUIController(parent, NoOpKeyboardListener, activity).show()
            activity.setContentView(root)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val hide = activity.findViewById<View>(R.id.btn_keyboard_hide)
            val rect = Rect()
            assertTrue(hide.getGlobalVisibleRect(rect))
            assertTrue(rect.width() > 0)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_keyboard_mini).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_keyboard_resize).visibility)
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

    private fun isolatedKeyboardContext(context: Context): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("shift-ownership-test-$name", mode)
    }.also { it.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit() }

    private object NoOpKeyboardListener : KeyboardUIController.OnKeyboardEventListener {
        override fun sendKeyEvent(down: Boolean, keyCode: Short) = Unit

        override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
        override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
    }
}
