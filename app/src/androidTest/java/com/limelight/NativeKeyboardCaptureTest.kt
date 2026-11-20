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
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.services.KeyboardAccessibilityService
import com.limelight.utils.Dialog as AppDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class NativeKeyboardCaptureTest {
    @Test
    fun captureFlagIsAvailableAndGrabWaitsForAConnection() {
        assumeTrue(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
        )

        val localServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val intent = Intent(ApplicationProvider.getApplicationContext(), Game::class.java)
                .putExtra(Game.EXTRA_APP_ID, 1)
                .putExtra(Game.EXTRA_APP_NAME, "app")
                .putExtra(Game.EXTRA_HOST, InetAddress.getLoopbackAddress().hostAddress)
                .putExtra(Game.EXTRA_PORT, localServer.localPort)
                .putExtra(Game.EXTRA_HTTPS_PORT, localServer.localPort)

            ActivityScenario.launch<Game>(intent).use { scenario ->
                awaitWindowFocus(scenario, true)
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

                    // Simulate the accepted connection state without a streaming host.
                    val session = Game::class.java.getDeclaredField("streamSession").apply {
                        isAccessible = true
                    }.get(game) as StreamSessionCoordinator
                    StreamSessionCoordinator::class.java.getDeclaredField("state").apply {
                        isAccessible = true
                    }.set(session, StreamSessionState.CONNECTED)
                    assertTrue(game.hasWindowFocus())
                    game.setInputGrabState(true)
                    assertTrue(game.window.attributes.hasKeyboardCapture())
                    assertTrue(KeyboardAccessibilityService.interceptingEnabled)

                    game.setInputGrabState(false)
                    assertFalse(game.window.attributes.hasKeyboardCapture())
                    assertFalse(KeyboardAccessibilityService.interceptingEnabled)
                    game.enableNativeMousePointer(true)
                    assertTrue(game.grabbedInput)
                    assertTrue(game.window.attributes.hasKeyboardCapture())

                    // The shortcut must restore capture when the input grab is off.
                    game.setInputGrabState(false)
                    game.prefConfig.enableCustomKeyMap = true
                    val heldEscape = KeyEvent(
                        0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
                    )
                    assertFalse(game.keyboardInputHandler.handleKeyDown(heldEscape))
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
                    assertFalse(game.keyboardInputHandler.handleKeyUp(KeyEvent.changeAction(heldEscape, KeyEvent.ACTION_UP)))
                    game.prefConfig.enableCustomKeyMap = false

                    AppDialog.displayDialog(game, "Local dialog", "Input stays local", false)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                awaitWindowFocus(scenario, false)
                scenario.onActivity { game ->
                    assertFalse(game.hasWindowFocus())
                    assertFalse(game.window.attributes.hasKeyboardCapture())
                    assertFalse(KeyboardAccessibilityService.interceptingEnabled)
                    assertFalse(game.keyboardInputHandler.handleKeyDown(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
                    assertFalse(game.keyboardInputHandler.handleKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
                    AppDialog.closeDialogs()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                awaitWindowFocus(scenario, true)
                scenario.onActivity { game ->
                    assertTrue(game.hasWindowFocus())
                    assertTrue(game.window.attributes.hasKeyboardCapture())
                    assertTrue(KeyboardAccessibilityService.interceptingEnabled)
                }
            }
        } finally {
            localServer.close()
        }
    }

    private fun awaitWindowFocus(scenario: ActivityScenario<Game>, expected: Boolean) {
        var focused = !expected
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (focused != expected && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { focused = it.hasWindowFocus() }
            if (focused != expected) SystemClock.sleep(100)
        }
        assertEquals("Unexpected stream window focus", expected, focused)
    }
}
