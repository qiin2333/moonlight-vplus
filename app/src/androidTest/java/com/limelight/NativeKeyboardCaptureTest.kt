package com.limelight

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class NativeKeyboardCaptureTest {
    @Test
    fun captureFlagIsAvailableAndGrabWaitsForAConnection() {
        assumeTrue(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
        )

        val intent = Intent(ApplicationProvider.getApplicationContext(), Game::class.java)
            .putExtra(Game.EXTRA_APP_ID, 1)
            .putExtra(Game.EXTRA_APP_NAME, "app")
            .putExtra(Game.EXTRA_HOST, InetAddress.getLoopbackAddress().hostAddress)
            .putExtra(Game.EXTRA_PORT, 1)

        ActivityScenario.launch<Game>(intent).use { scenario ->
            scenario.onActivity { game ->
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

                // The shortcut restores grab intent; native capture still waits for a connection.
                game.setInputGrabState(false)
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
                assertTrue(game.grabbedInput)
            assertTrue(game.window.attributes.hasKeyboardCapture())
            }
        }
    }
}
