package com.limelight

import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.preference.PreferenceManager
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeKeyboardCaptureTest {
    @Test
    fun switchingDesktopMouseModeReconfiguresCaptureWithoutChangingPreset() {
        KeyboardTestScenario.launch().use { scenario ->
            scenario.onActivity { game ->
                assumeTrue(game.prefConfig.supportsDesktopMouseMode)
                val prefs = PreferenceManager.getDefaultSharedPreferences(game)
                val key = PreferenceConfiguration.ABSOLUTE_MOUSE_MODE_PREF_STRING
                val original = prefs.all[key] as? Boolean
                try {
                    game.setTouchMode(false)
                    game.setInputGrabState(true)
                    game.enableNativeMousePointer(true)
                    for (desktop in listOf(true, false, true)) {
                        game.setExternalMouseDesktopMode(desktop)
                        assertEquals(!desktop, game.inputCaptureProvider.isCapturingEnabled())
                        assertEquals(desktop, game.cursorVisible)
                        assertEquals(desktop, PreferenceConfiguration.readPreferences(game).absoluteMouseMode)
                        assertTrue(game.prefConfig.enableNativeMousePointer)
                        assertFalse(game.prefConfig.touchscreenTrackpad)
                    }
                } finally {
                    val editor = prefs.edit()
                    if (original == null) editor.remove(key) else editor.putBoolean(key, original)
                    editor.apply()
                }
            }
        }
    }

    @Test
    fun captureFlagIsAvailableAndGrabWaitsForAConnection() {
        assumeTrue(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
        )

        KeyboardTestScenario.launch().use { scenario ->
            scenario.onActivity { game ->
                game.onWindowFocusChanged(true)
                assertEquals(
                    PackageManager.PERMISSION_GRANTED,
                    game.checkSelfPermission("android.permission.CAPTURE_KEYBOARD")
                )
                game.setInputGrabState(true)
                assertTrue(game.grabbedInput)
                assertFalse(game.connected)
                assertFalse(game.window.attributes.hasKeyboardCapture())

                game.setMetaKeyCaptureState(true)
                assertTrue(game.window.attributes.hasKeyboardCapture())

                game.setInputGrabState(false)
                assertFalse(game.grabbedInput)
                assertFalse(game.window.attributes.hasKeyboardCapture())

                val captureState = InputCaptureProvider::class.java.getDeclaredField("isCapturing").apply {
                    isAccessible = true
                }
                game.prefConfig.absoluteMouseMode = true
                game.enableNativeMousePointer(true)
                sendCursorShortcut(game)
                assertTrue(game.grabbedInput)
                assertTrue(captureState.getBoolean(game.inputCaptureProvider))
                assertFalse(game.window.attributes.hasKeyboardCapture())

                game.setInputGrabState(false)
                game.setInputGrabState(true)
                assertFalse(game.window.attributes.hasKeyboardCapture())

            // Drive the accepted connection transition without a streaming host.
            val session = Game::class.java.getDeclaredField("streamSession").apply {
                isAccessible = true
            }.get(game) as StreamSessionCoordinator
            StreamSessionCoordinator::class.java.getDeclaredField("state").apply {
                isAccessible = true
            }.set(session, StreamSessionState.CONNECTING)
            val connected = Game::class.java.getDeclaredMethod("onConnectionEstablished").apply {
                isAccessible = true
            }.invoke(game) as Boolean
            assertTrue(connected)
            assertTrue(game.window.attributes.hasKeyboardCapture())

                game.setInputGrabState(false)
                game.enableNativeMousePointer(true)
            assertFalse(game.grabbedInput)
                assertFalse(game.window.attributes.hasKeyboardCapture())

            // Mouse releases must not be skipped after the input grab is released.
            game.prefConfig.mouseNavButtons = false
            game.setInputGrabState(true)
            val mouseBackDown = KeyEvent(
                0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_MOUSE
            )
            assertTrue(game.keyboardInputHandler.handleKeyDown(mouseBackDown))
            game.setInputGrabState(false)
            assertTrue(game.keyboardInputHandler.handleKeyUp(KeyEvent.changeAction(mouseBackDown, KeyEvent.ACTION_UP)))

            game.setInputGrabState(true)
            game.touchInputHandler.detectMouseMiddle = true
            TouchInputHandler::class.java.getDeclaredField("lastMouseHoverTime").apply {
                isAccessible = true
            }.setLong(game.touchInputHandler, SystemClock.uptimeMillis())
            val middleBackDown = KeyEvent(
                0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            assertTrue(game.keyboardInputHandler.handleKeyDown(middleBackDown))
            assertTrue(game.touchInputHandler.detectMouseMiddleDown)
            game.setInputGrabState(false)
            assertTrue(game.keyboardInputHandler.handleKeyUp(KeyEvent.changeAction(middleBackDown, KeyEvent.ACTION_UP)))
            assertFalse(game.touchInputHandler.detectMouseMiddleDown)
            val virtualBack = KeyEvent(
                0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_MOUSE
            )
            assertFalse(game.keyboardInputHandler.handleKeyUp(virtualBack))

                // The shortcut restores both pointer and keyboard capture in native mouse mode.
                game.setInputGrabState(false)
                assertTrue(game.prefConfig.enableNativeMousePointer)
                assertFalse(captureState.getBoolean(game.inputCaptureProvider))
                sendCursorShortcut(game)
                assertTrue(game.grabbedInput)
            assertTrue(game.window.attributes.hasKeyboardCapture())
            assertTrue("Pointer capture was not restored", captureState.getBoolean(game.inputCaptureProvider))
            }
        }
    }

    private fun sendCursorShortcut(game: Game) {
        listOf(
            KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_C,
            KeyEvent.ACTION_UP to KeyEvent.KEYCODE_C,
            KeyEvent.ACTION_UP to KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.ACTION_UP to KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.ACTION_UP to KeyEvent.KEYCODE_CTRL_LEFT,
        ).forEach { (action, keyCode) ->
            val event = KeyEvent(
                0, 0, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            if (action == KeyEvent.ACTION_DOWN) {
                game.keyboardInputHandler.handleKeyDown(event)
            } else {
                game.keyboardInputHandler.handleKeyUp(event)
            }
        }
    }
}
