package com.limelight.binding.input.virtual_controller

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.SystemClock
import androidx.test.filters.SdkSuppress
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.*
import androidx.core.view.children
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import com.limelight.ui.FloatingButtonPlacement
import com.limelight.ui.FloatingButtonViewport
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
    private lateinit var buttonPreferences: SharedPreferences
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()
    private var savedProfiles: Map<String, *> = emptyMap<String, Any>()
    private var savedButtonPreferences: Map<String, *> = emptyMap<String, Any>()
    private val keys = listOf("list_osc_layout", "checkbox_only_show_L3R3", "checkbox_half_height_osc_portrait",
        "seekbar_osc_opacity")

    @Before fun setup() {
        activityRule.scenario.onActivity { activity ->
            preferences = PreferenceManager.getDefaultSharedPreferences(activity)
            profiles = activity.getSharedPreferences("OSC", 0)
            buttonPreferences = activity.getSharedPreferences("osc_settings_button", 0)
            savedButtonPreferences = buttonPreferences.all
            buttonPreferences.edit().clear().commit()
            savedPreferences = preferences.all.filterKeys { it in keys }
            savedProfiles = profiles.all
            preferences.edit().putString("list_osc_layout", "xbox")
                .putBoolean("checkbox_only_show_L3R3", false).putBoolean("checkbox_half_height_osc_portrait", true)
                .putInt("seekbar_osc_opacity", 40).commit()
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
                    is Int -> putInt(key, value)
                } }
            }.commit()
            profiles.edit().clear().apply {
                savedProfiles.forEach { (key, value) -> if (value is String) putString(key, value) }
            }.commit()
            buttonPreferences.edit().clear().apply {
                savedButtonPreferences.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                } }
            }.commit()
        }
    }

    private fun idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    private fun openMenu() {
        activityRule.scenario.onActivity { frame.children.filterIsInstance<ImageButton>().single().performClick() }
        onView(isRoot()).inRoot(isDialog()).check(matches(isDisplayed()))
        compose.waitForIdle()
    }

    private fun key(key: Int, action: Int? = null, sourceId: Int = -7) {
        activityRule.scenario.onActivity {
            val now = SystemClock.uptimeMillis()
            for (half in action?.let { listOf(it) } ?: listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                controller.dispatchMenuKey(KeyEvent(now, now, half, key, 0, 0, sourceId, 0))
            }
        }
        idle()
    }

    private fun touch(action: Int, x: Float, y: Float) {
        activityRule.scenario.onActivity {
            val event = MotionEvent.obtain(1, SystemClock.uptimeMillis(), action, x, y, 0)
            try { frame.children.filterIsInstance<ImageButton>().single().dispatchTouchEvent(event) }
            finally { event.recycle() }
        }
        idle()
    }

    private fun enableDragging() {
        openMenu()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.osc_allow_drag_settings_button)
        compose.onNodeWithText(label).performScrollTo().performClick()
        activityRule.scenario.onActivity { assertTrue(buttonPreferences.getBoolean("drag_enabled", false)) }
        key(KeyEvent.KEYCODE_BACK)
    }

    @Test fun dragSavesAndRestoresWithoutOpeningMenu() {
        activityRule.scenario.onActivity { assertFalse(buttonPreferences.getBoolean("drag_enabled", false)) }
        enableDragging()
        touch(MotionEvent.ACTION_DOWN, 2f, 2f)
        touch(MotionEvent.ACTION_MOVE, 100000f, 100000f)
        touch(MotionEvent.ACTION_UP, 100000f, 100000f)
        activityRule.scenario.onActivity {
            val button = frame.children.filterIsInstance<ImageButton>().single()
            assertTrue(button.x + button.width <= frame.width)
            assertTrue(button.y + button.height <= frame.height)
            assertEquals(1f, buttonPreferences.getFloat("x", -1f), 0f)
            assertEquals(1f, buttonPreferences.getFloat("y", -1f), 0f)
            assertFalse(controller.dispatchMenuAxes(-7, 0f, 0f, 0f))
            controller.cleanup()
            controller.removeElements()
            controller = VirtualController(null, frame, it)
            controller.refreshLayout()
        }
        idle()
        activityRule.scenario.onActivity {
            val button = frame.children.filterIsInstance<ImageButton>().single()
            assertTrue(button.x > frame.width / 2)
            assertTrue(button.y > frame.height / 2)
        }
        // A tap still opens the menu, including after re-entering a stream.
        touch(MotionEvent.ACTION_DOWN, 2f, 2f)
        touch(MotionEvent.ACTION_UP, 2f, 2f)
        compose.onNodeWithText("Xbox").assertExists()
    }

    @Test fun disablingDragPreservesPositionAndResetPreservesToggle() {
        enableDragging()
        touch(MotionEvent.ACTION_DOWN, 2f, 2f)
        touch(MotionEvent.ACTION_MOVE, 200f, 200f)
        touch(MotionEvent.ACTION_UP, 200f, 200f)
        var savedX = 0f
        var savedY = 0f
        activityRule.scenario.onActivity {
            savedX = buttonPreferences.getFloat("x", -1f)
            savedY = buttonPreferences.getFloat("y", -1f)
        }
        openMenu()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.osc_allow_drag_settings_button)).performScrollTo().performClick()
        activityRule.scenario.onActivity {
            assertFalse(buttonPreferences.getBoolean("drag_enabled", true))
            assertEquals(savedX, buttonPreferences.getFloat("x", -1f), 0f)
            assertEquals(savedY, buttonPreferences.getFloat("y", -1f), 0f)
        }
        compose.onNodeWithText(context.getString(R.string.osc_reset_settings_button_position)).performScrollTo().performClick()
        idle()
        activityRule.scenario.onActivity {
            val button = frame.children.filterIsInstance<ImageButton>().single()
            val margin = minOf((8 * it.resources.displayMetrics.density).toInt(), button.width / 4)
            assertEquals(margin.toFloat(), button.x, 0f)
            assertEquals(margin.toFloat(), button.y, 0f)
            assertFalse(buttonPreferences.contains("x"))
            assertFalse(buttonPreferences.contains("y"))
            assertFalse(buttonPreferences.getBoolean("drag_enabled", true))
        }
    }

    @Test fun cancelledDragAndLayoutRefreshCannotSaveOrClick() {
        enableDragging()
        touch(MotionEvent.ACTION_DOWN, 2f, 2f)
        touch(MotionEvent.ACTION_MOVE, 150f, 150f)
        touch(MotionEvent.ACTION_CANCEL, 150f, 150f)
        touch(MotionEvent.ACTION_UP, 150f, 150f)
        activityRule.scenario.onActivity {
            assertFalse(buttonPreferences.contains("x"))
            assertFalse(controller.dispatchMenuAxes(-7, 0f, 0f, 0f))
        }
        touch(MotionEvent.ACTION_DOWN, 2f, 2f)
        touch(MotionEvent.ACTION_MOVE, 150f, 150f)
        activityRule.scenario.onActivity { controller.refreshLayout() }
        idle()
        touch(MotionEvent.ACTION_UP, 150f, 150f)
        activityRule.scenario.onActivity {
            assertFalse(buttonPreferences.contains("x"))
            assertFalse(controller.dispatchMenuAxes(-7, 0f, 0f, 0f))
        }
    }

    @Test fun nextDragCannotInheritCancelledMovement() {
        enableDragging()
        activityRule.scenario.onActivity { activity ->
            val button = frame.children.filterIsInstance<ImageButton>().single()
            val originalX = button.x
            val originalY = button.y
            fun dispatch(action: Int, coordinate: Float) {
                val event = MotionEvent.obtain(1, SystemClock.uptimeMillis(), action, coordinate, coordinate, 0)
                try { button.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            // Consecutive gestures can be delivered before the posted placement callback runs.
            dispatch(MotionEvent.ACTION_DOWN, 2f)
            dispatch(MotionEvent.ACTION_MOVE, 150f)
            dispatch(MotionEvent.ACTION_CANCEL, 150f)
            dispatch(MotionEvent.ACTION_DOWN, 2f)
            dispatch(MotionEvent.ACTION_MOVE, 50f)
            dispatch(MotionEvent.ACTION_UP, 50f)
            val margin = minOf((8 * activity.resources.displayMetrics.density).toInt(), button.width / 4)
            val viewport = FloatingButtonViewport(frame.width, frame.height, button.width, button.height, margin)
            val expected = FloatingButtonPlacement.normalize(
                FloatingButtonPlacement.clampCustom(originalX + 48f, originalY + 48f, viewport), viewport)
            assertEquals(expected, OscSettingsButtonStore(activity).position())
        }
    }

    @Test fun savedPositionScalesWithViewportWithoutChangingStoredFractions() {
        enableDragging()
        touch(MotionEvent.ACTION_DOWN, 2f, 2f)
        touch(MotionEvent.ACTION_MOVE, 250f, 400f)
        touch(MotionEvent.ACTION_UP, 250f, 400f)
        val saved = OscSettingsButtonStore(InstrumentationRegistry.getInstrumentation().targetContext).position()!!
        for ((width, height) in listOf(1280 to 720, 720 to 1280)) {
            activityRule.scenario.onActivity {
                frame.layoutParams = FrameLayout.LayoutParams(width, height)
                controller.refreshLayout()
            }
            idle()
            activityRule.scenario.onActivity {
                val button = frame.children.filterIsInstance<ImageButton>().single()
                val margin = minOf((8 * it.resources.displayMetrics.density).toInt(), button.width / 4)
                val expected = FloatingButtonPlacement.resolve(FloatingButtonPlacement.POSITION_CUSTOM,
                    saved, FloatingButtonViewport(frame.width, frame.height, button.width, button.height, margin))
                assertEquals(expected.x.toFloat(), button.x, 0f)
                assertEquals(expected.y.toFloat(), button.y, 0f)
                assertEquals(saved, OscSettingsButtonStore(it).position())
            }
        }
    }

    @Test fun hidingDuringDragDropsTheOldGesture() {
        enableDragging()
        touch(MotionEvent.ACTION_DOWN, 2f, 2f)
        touch(MotionEvent.ACTION_MOVE, 150f, 150f)
        activityRule.scenario.onActivity { controller.hide(); controller.show() }
        touch(MotionEvent.ACTION_UP, 150f, 150f)
        activityRule.scenario.onActivity {
            assertFalse(buttonPreferences.contains("x"))
            assertFalse(controller.dispatchMenuAxes(-7, 0f, 0f, 0f))
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun cancelledPointerUpCannotSaveOrOpenMenu() {
        enableDragging()
        touch(MotionEvent.ACTION_DOWN, 2f, 2f)
        touch(MotionEvent.ACTION_MOVE, 150f, 150f)
        activityRule.scenario.onActivity {
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = 150f; y = 150f })
            val event = MotionEvent.obtain(1, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                1, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.FLAG_CANCELED)
            try { frame.children.filterIsInstance<ImageButton>().single().dispatchTouchEvent(event) }
            finally { event.recycle() }
            assertFalse(buttonPreferences.contains("x"))
            assertFalse(controller.dispatchMenuAxes(-7, 0f, 0f, 0f))
        }
    }

    @Test fun rightStickScrollDoesNotMoveFocus() {
        openMenu()
        activityRule.scenario.onActivity { controller.dispatchMenuAxes(-7, 0f, 0f, 1f) }
        Thread.sleep(600)
        idle()
        compose.onNodeWithText("Xbox").assertIsFocused()
        activityRule.scenario.onActivity { controller.dispatchMenuAxes(-7, 0f, 0f, 0f) }
    }

    @Test fun controllerToggleRetainsFocusAndOrphanUpDoesNothing() {
        openMenu()
        compose.onNodeWithText("Xbox").assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)
        compose.onNodeWithText("Xbox").assertExists()
        repeat(9) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.osc_allow_drag_settings_button)
        compose.onNodeWithText(label).assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText(label).assertIsFocused()
        activityRule.scenario.onActivity { assertTrue(buttonPreferences.getBoolean("drag_enabled", false)) }
        key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)
        activityRule.scenario.onActivity { assertTrue(buttonPreferences.getBoolean("drag_enabled", false)) }
        key(KeyEvent.KEYCODE_BUTTON_B)
        activityRule.scenario.onActivity { assertFalse(controller.dispatchMenuAxes(-7, 0f, 0f, 0f)) }
    }

    @Test fun usbAxesNavigateAndDisconnectStopsRepeating() {
        openMenu()
        activityRule.scenario.onActivity {
            assertTrue(controller.dispatchMenuAxes(-7, 0f, 1f, 0f))
            controller.releaseMenuSource(-7)
        }
        idle()
        compose.onNodeWithText("DualSense (DS)").assertIsFocused()
        Thread.sleep(450)
        idle()
        compose.onNodeWithText("DualSense (DS)").assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_A)
        activityRule.scenario.onActivity { assertEquals(VirtualControllerLayout.DS, controller.layoutStyle) }
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
            val size = minOf((48 * density).toInt(), (frame.height * 0.06f).toInt().coerceAtLeast(1))
                .coerceAtMost(minOf(frame.width, frame.height))
            val margin = minOf((8 * density).toInt(), size / 4)
            assertEquals(size, button.width)
            assertEquals(size / 8, button.paddingLeft)
            assertEquals(size / 8, button.paddingRight)
            assertEquals(0.4f, button.alpha, 0f)
            assertNotNull(button.background)
            assertEquals(margin, button.left)
            assertEquals(margin, button.top)
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

    @Test fun configureButtonStaysInCornerAcrossSizesAndLayouts() {
        for ((width, height) in listOf(1280 to 720, 3200 to 1440, 720 to 1280, 32 to 24)) {
            activityRule.scenario.onActivity {
                frame.layoutParams = FrameLayout.LayoutParams(width, height)
                controller.refreshLayout()
            }
            idle()
            for (preset in VirtualControllerLayout.entries) {
                activityRule.scenario.onActivity { controller.switchLayout(preset) }
                idle()
                activityRule.scenario.onActivity {
                    val button = frame.children.filterIsInstance<ImageButton>().single()
                    val density = it.resources.displayMetrics.density
                    val size = minOf((48 * density).toInt(), (frame.height * 0.06f).toInt().coerceAtLeast(1))
                        .coerceAtMost(minOf(frame.width, frame.height))
                    val margin = minOf((8 * density).toInt(), size / 4)
                    assertEquals(size, button.width)
                    assertEquals(margin.coerceAtMost(frame.width - size), button.left)
                    assertEquals(margin.coerceAtMost(frame.height - size), button.top)
                    assertTrue(button.right <= frame.width)
                    assertTrue(button.bottom <= frame.height)
                    val buttonBounds = Rect(button.left, button.top, button.right, button.bottom)
                    controller.elements.forEach { element ->
                        assertFalse("Configure button must not block element ${element.elementId} in $preset",
                            Rect.intersects(buttonBounds, Rect(element.left, element.top, element.right, element.bottom)))
                    }
                }
            }
        }
    }

    @Test fun configureButtonFollowsOpacityAndVisibility() {
        activityRule.scenario.onActivity {
            val button = frame.children.filterIsInstance<ImageButton>().single()
            val size = button.width
            for (opacity in listOf(0, 20, 40, 100)) {
                controller.setOpacity(opacity)
                assertEquals(opacity / 100f, button.alpha, 0f)
                assertEquals(size, button.width)
                assertTrue(button.isClickable)
            }
            controller.hide()
            assertEquals(View.INVISIBLE, button.visibility)
            controller.show()
            assertEquals(View.VISIBLE, button.visibility)
        }
        openMenu()
        compose.onNodeWithText("DualSense (DS)").performScrollTo().performClick()
        idle()
        activityRule.scenario.onActivity {
            assertEquals(0.4f, frame.children.filterIsInstance<ImageButton>().single().alpha, 0f)
        }
    }

    @Test fun configureButtonRemainsOnPhysicalLeftInRtl() {
        activityRule.scenario.onActivity {
            frame.layoutDirection = View.LAYOUT_DIRECTION_RTL
            controller.refreshLayout()
        }
        idle()
        activityRule.scenario.onActivity {
            val button = frame.children.filterIsInstance<ImageButton>().single()
            val margin = minOf((8 * it.resources.displayMetrics.density).toInt(), button.width / 4)
            assertEquals(margin, button.left)
            assertEquals(margin, button.top)
        }
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
