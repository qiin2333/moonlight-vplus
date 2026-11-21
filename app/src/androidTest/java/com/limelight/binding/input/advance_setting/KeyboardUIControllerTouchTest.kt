package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
    fun fullKeyboardTabsRemainReadableAndTouchableInLightAndDarkThemes() {
        for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            lateinit var themed: Context
            activityRule.scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
                val themeContext = ContextThemeWrapper(activity.createConfigurationContext(configuration), 0)
                    .apply { theme.setTo(activity.theme) }
                themed = object : ContextWrapper(themeContext) {
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                        super.getSharedPreferences("keyboard-visibility-test-$name", mode)
                }
                themed.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
                val parent = FrameLayout(themed)
                KeyboardUIController(parent, NoOpKeyboardListener, themed).show()
                activity.setContentView(parent)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            activityRule.scenario.onActivity { activity ->
                val panel = ColorUtils.compositeColors(
                    ContextCompat.getColor(themed, R.color.crown_panel_background),
                    if (nightMode == Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
                )
                val tabs = listOf(R.id.btn_key_page_main, R.id.btn_key_page_nav,
                    R.id.btn_key_page_num, R.id.btn_key_page_mini)
                    .map { activity.findViewById<TextView>(it) }
                fun assertTabVisible(tab: TextView) {
                    val visible = Rect()
                    assertTrue(tab.getGlobalVisibleRect(visible))
                    assertEquals(tab.width, visible.width())
                    assertEquals(tab.height, visible.height())
                    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(panel)
                    val drawable = tab.background
                    val oldBounds = Rect(drawable.bounds)
                    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                    drawable.draw(canvas)
                    drawable.bounds = oldBounds
                    val background = bitmap.getPixel(16, 16)
                    bitmap.recycle()
                    assertTrue("Tab text must contrast with its background",
                        ColorUtils.calculateContrast(tab.currentTextColor, background) >= 4.5)
                }
                tabs.forEach(::assertTabVisible)
                assertTrue(tabs[0].isSelected)
                tabs[1].performClick()
                assertTrue(tabs[1].isSelected)
                assertFalse(tabs[0].isSelected)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layout_nav).visibility)
                tabs.forEach(::assertTabVisible)
                tabs[0].performClick()
                assertTrue(tabs[0].isSelected)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layout_main).visibility)
            }
        }
    }

    @Test
    fun numpadRemainsVisibleAndEmitsPairedTouchEvents() {
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
            val numpad = activity.findViewById<View>(R.id.layout_num)
            for (code in (KeyEvent.KEYCODE_NUM_LOCK..KeyEvent.KEYCODE_NUMPAD_DOT) + KeyEvent.KEYCODE_ENTER) {
                val key = numpad.findViewWithTag<View>("k$code")
                val rect = Rect()
                assertTrue(key.getLocalVisibleRect(rect))
                assertEquals(key.width, rect.width())
                assertEquals(key.height, rect.height())
            }
            val key = numpad.findViewWithTag<View>("k144")
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0)
                key.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        assertEquals(listOf(true to KeyEvent.KEYCODE_NUMPAD_0.toShort(), false to KeyEvent.KEYCODE_NUMPAD_0.toShort()), events)
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

    private object NoOpKeyboardListener : KeyboardUIController.OnKeyboardEventListener {
        override fun sendKeyEvent(down: Boolean, keyCode: Short) = Unit

        override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
        override fun onKeyboardVisibilityChanged(visible: Boolean) = Unit
    }
}
