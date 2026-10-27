package com.limelight.utils

import android.app.Dialog
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises Android's input dispatcher, including its synthetic joystick-to-DPAD path. */
@RunWith(AndroidJUnit4::class)
class AppActionSheetGamepadInputTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var dialog: Dialog? = null
    private val receivedKeys = CopyOnWriteArrayList<KeyStamp>()
    private val receivedMotion = CopyOnWriteArrayList<MotionStamp>()
    private val singleSelections = mutableListOf<Int>()
    private val multiSelections = mutableListOf<Set<Int>>()
    private val drafts = mutableListOf<Set<Int>>()

    @After
    fun dismissDialog() {
        compose.runOnUiThread { dialog?.dismiss() }
    }

    @Test
    fun gamepadSourceDpadSkipsDisabledChoicesAndAConfirmsWhileBCancels() {
        showSingleChoice()
        compose.onNodeWithText("Current").assertIsFocused()

        injectGamepadKey(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Next").assertIsFocused()
        injectGamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf(2), singleSelections)
        assertFalse(dialog!!.isShowing)

        showSingleChoice()
        compose.onNodeWithText("Current").assertIsFocused()
        injectGamepadKey(KeyEvent.KEYCODE_BUTTON_B)

        assertEquals(listOf(2), singleSelections)
        assertFalse(dialog!!.isShowing)
        Log.i(TAG, "PASS single choice: delivered SOURCE_GAMEPAD DPAD_DOWN/A/B; disabled row skipped, one confirmation, cancel unchanged")
    }

    @Test
    fun joystickAxesNavigateMultiChoiceAndGamepadButtonsConfirmOrDiscardItsDraft() {
        showMultiChoice()
        compose.onNodeWithText("Current").assertIsFocused().assertIsOn()

        injectJoystick(axisY = 1f)
        compose.onNodeWithText("Next").assertIsFocused()
        injectGamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Next").assertIsFocused().assertIsOn()
        assertEquals(emptyList<Set<Int>>(), multiSelections)
        assertEquals(listOf(setOf(0, 2)), drafts)

        injectJoystick(hatY = 1f)
        compose.onNodeWithText("Cancel").assertIsFocused()
        injectGamepadKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Apply").assertIsFocused()
        injectGamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf(setOf(0, 2)), multiSelections)
        assertFalse(dialog!!.isShowing)

        showMultiChoice()
        injectJoystick(axisY = 1f)
        compose.onNodeWithText("Next").assertIsFocused()
        injectGamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        injectJoystick(hatY = -1f)
        compose.onNodeWithText("Current").assertIsFocused()
        injectGamepadKey(KeyEvent.KEYCODE_BUTTON_B)

        assertEquals(listOf(setOf(0, 2)), multiSelections)
        assertFalse(dialog!!.isShowing)
        Log.i(TAG, "PASS multi choice: delivered SOURCE_JOYSTICK AXIS_Y and HAT_Y +/-1; SOURCE_GAMEPAD A confirms once and B discards draft")
    }

    private fun showSingleChoice() {
        compose.runOnUiThread {
            val controllerContext = ContextThemeWrapper(compose.activity, 0).apply {
                applyOverrideConfiguration(Configuration().apply { uiMode = Configuration.UI_MODE_TYPE_TELEVISION })
            }
            dialog = AppActionSheet.show(
                context = controllerContext,
                title = "Controller single choice",
                actions = choices(),
                initialActionId = 0,
                singleChoice = true,
                onAction = { singleSelections += it.id }
            )
            recordDeliveredEvents(requireNotNull(dialog))
        }
        awaitDialogWindowFocus()
    }

    private fun showMultiChoice() {
        compose.runOnUiThread {
            dialog = AppActionSheet.showMultiSelect(
                context = compose.activity,
                title = "Controller multi choice",
                actions = choices(),
                confirmLabel = "Apply",
                cancelLabel = "Cancel",
                onConfirm = { multiSelections += it },
                forceInitialFocus = true,
                onSelectionChanged = { drafts += it }
            )
            recordDeliveredEvents(requireNotNull(dialog))
        }
        awaitDialogWindowFocus()
    }

    private fun awaitDialogWindowFocus() {
        // Input must reach a focused window after its initial Compose focus effect.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnUiThread { dialog?.window?.decorView?.hasWindowFocus() == true }
        }
        compose.waitForIdle()
    }

    private fun choices() = listOf(
        AppActionSheet.Action(0, "Current", checked = true),
        AppActionSheet.Action(1, "Unavailable", enabled = false, checked = false),
        AppActionSheet.Action(2, "Next", checked = false)
    )

    private fun recordDeliveredEvents(dialog: Dialog) {
        val window = requireNotNull(dialog.window)
        val original = requireNotNull(window.callback)
        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                receivedKeys += KeyStamp(event.source, event.keyCode, event.action)
                return original.dispatchKeyEvent(event)
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                receivedMotion += MotionStamp(event.source, event.getAxisValue(MotionEvent.AXIS_Y),
                    event.getAxisValue(MotionEvent.AXIS_HAT_Y))
                return original.dispatchGenericMotionEvent(event)
            }
        }
    }

    private fun injectGamepadKey(code: Int) {
        val before = receivedKeys.size
        val downTime = SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD)
            assertTrue("Platform rejected SOURCE_GAMEPAD key $code/$action", automation.injectInputEvent(event, true))
        }
        compose.waitForIdle()
        val delivered = receivedKeys.drop(before)
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue("SOURCE_GAMEPAD key $code/$action was accepted but not delivered with its source: $delivered",
                delivered.any { it.source == InputDevice.SOURCE_GAMEPAD && it.code == code && it.action == action })
        }
        Log.i(TAG, "Delivered SOURCE_GAMEPAD code=$code down/up")
    }

    private fun injectJoystick(axisY: Float = 0f, hatY: Float = 0f) {
        val before = receivedMotion.size
        injectJoystickMotion(axisY, hatY)
        compose.waitForIdle()
        val delivered = receivedMotion.drop(before)
        assertTrue("Synthetic SOURCE_JOYSTICK was accepted but never reached the dialog: $delivered",
            delivered.any { it.source == InputDevice.SOURCE_JOYSTICK && it.axisY == axisY && it.hatY == hatY })
        injectJoystickMotion(0f, 0f)
        compose.waitForIdle()
        Log.i(TAG, "Delivered SOURCE_JOYSTICK AXIS_Y=$axisY HAT_Y=$hatY and neutral release")
    }

    private fun injectJoystickMotion(axisY: Float, hatY: Float) {
        val time = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_Y, axisY)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
        })
        val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_MOVE, 1, properties, coordinates,
            0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_JOYSTICK, 0)
        try {
            assertTrue("Platform rejected synthetic SOURCE_JOYSTICK AXIS_Y=$axisY HAT_Y=$hatY",
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private data class KeyStamp(val source: Int, val code: Int, val action: Int)
    private data class MotionStamp(val source: Int, val axisY: Float, val hatY: Float)

    private companion object {
        private const val TAG = "ActionSheetGamepad"
    }
}
