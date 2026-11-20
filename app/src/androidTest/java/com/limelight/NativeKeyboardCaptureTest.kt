package com.limelight

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

                game.setInputGrabState(false)
                game.enableNativeMousePointer(true)
                assertTrue(game.grabbedInput)
                assertFalse(game.window.attributes.hasKeyboardCapture())

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
                assertFalse(game.window.attributes.hasKeyboardCapture())
            }
        }
    }
}
