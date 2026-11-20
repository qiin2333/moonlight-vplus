package com.limelight

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.binding.input.ControllerHandler
import com.limelight.services.KeyboardAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardLifecycleRegressionTest {
    @Test
    fun mainWindowFocusLossReleasesKeyboardAndMouseWithoutAccessibility() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            KeyboardAccessibilityService.setIntercepting(false)
            game.prefConfig.mouseNavButtons = false
            val handler = game.keyboardInputHandler
            handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, source = InputDevice.SOURCE_MOUSE))
            assertFalse(forwarded(game).isEmpty())
            assertTrue(field(handler, "rightMouseButtonDown") as Boolean)

            game.onWindowFocusChanged(false)
            assertTrue(forwarded(game).isEmpty())
            assertFalse(field(handler, "rightMouseButtonDown") as Boolean)
            handler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W))
            handler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, source = InputDevice.SOURCE_MOUSE))
            assertTrue(forwarded(game).isEmpty())
            game.onWindowFocusChanged(true)
            handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            assertFalse(forwarded(game).isEmpty())
            handler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W))
            assertTrue(forwarded(game).isEmpty())
        }
    }

    @Test
    fun pauseReleasesForwardedKeysEvenWhenInterceptionIsAlreadyDisabled() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            KeyboardAccessibilityService.setIntercepting(false)
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            assertFalse(forwarded(game).isEmpty())
        }
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity { assertTrue(forwarded(it).isEmpty()) }
    }

    @Test
    fun unmatchedEscapeAndUngrabbedKeypadDoNotSendCustomMappings() = withGame { scenario ->
        var keypad = 0
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            val handler = game.keyboardInputHandler
            assertTrue(handler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)))
            assertTrue(forwarded(game).isEmpty())
            game.setInputGrabState(false)
            keypad = controllerDeviceId()
            handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, keypad))
        }
        SystemClock.sleep(250)
        scenario.onActivity { game ->
            assertTrue(forwarded(game).isEmpty())
            game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, keypad))
            assertTrue(forwarded(game).isEmpty())
        }
    }

    @Test
    fun grabbedEscapeTapAndHoldStillReleaseNormally() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertFalse(forwarded(game).isEmpty())
        }
        SystemClock.sleep(100)
        scenario.onActivity { game ->
            assertTrue(forwarded(game).isEmpty())
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        }
        SystemClock.sleep(250)
        scenario.onActivity { game ->
            assertFalse(forwarded(game).isEmpty())
            game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
            assertTrue(forwarded(game).isEmpty())
        }
    }

    @Test
    fun connectionStopCancelsPendingGrabShortcutWhileUngrabbed() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.setInputGrabState(false)
            shortcut(game, KeyEvent.KEYCODE_Z)
        }
        SystemClock.sleep(350)
        scenario.onActivity { game ->
            assertTrue("The shortcut should regrab input while connected", game.grabbedInput)
            game.setInputGrabState(false)
            shortcut(game, KeyEvent.KEYCODE_Z)
            Game::class.java.getDeclaredMethod("stopConnection", StreamStopReason::class.java).apply {
                isAccessible = true
            }.invoke(game, StreamStopReason.CONNECTION_TERMINATED)
        }
        SystemClock.sleep(350)
        scenario.onActivity { assertFalse(it.grabbedInput) }
    }

    private fun withGame(block: (ActivityScenario<Game>) -> Unit) {
        try {
            KeyboardTestScenario.launch().use(block)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                KeyboardAccessibilityService.setIntercepting(false)
            }
        }
    }

    private fun connect(game: Game) {
        game.onWindowFocusChanged(true)
        val session = field(game, "streamSession") as StreamSessionCoordinator
        StreamSessionCoordinator::class.java.getDeclaredField("state").apply { isAccessible = true }
            .set(session, StreamSessionState.CONNECTING)
        assertTrue(Game::class.java.getDeclaredMethod("onConnectionEstablished").apply {
            isAccessible = true
        }.invoke(game) as Boolean)
        game.prefConfig.enableCustomKeyMap = false
        game.prefConfig.enableEscMenu = false
        game.setInputGrabState(true)
    }

    private fun forwarded(game: Game) = field(game.keyboardInputHandler, "forwardedKeys") as Map<*, *>
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(target)

    private fun controllerDeviceId(): Int {
        if (ControllerHandler.isGameControllerDevice(InputDevice.getDevice(0))) return 0
        return InputDevice.getDeviceIds().first { ControllerHandler.isGameControllerDevice(InputDevice.getDevice(it)) }
    }

    private fun key(action: Int, code: Int, deviceId: Int = KeyCharacterMap.VIRTUAL_KEYBOARD,
                    source: Int = InputDevice.SOURCE_KEYBOARD) = KeyEvent(
        1, SystemClock.uptimeMillis(), action, code, 0, 0, deviceId, 0, 0, source
    )

    private fun shortcut(game: Game, code: Int) {
        val keys = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, code)
        keys.forEach { game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, it)) }
        keys.reversed().forEach { game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, it)) }
    }
}
