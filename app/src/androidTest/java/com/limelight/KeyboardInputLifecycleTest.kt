package com.limelight

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.services.KeyboardAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on both legacy Android and 36.1; no real host is needed for lifecycle transitions. */
@RunWith(AndroidJUnit4::class)
class KeyboardInputLifecycleTest {
    @Test
    fun unpairedEscapeAndDisabledCustomMappingNeverSendKeys() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            val handler = game.keyboardInputHandler
            assertTrue(handler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)))
            assertTrue(forwarded(game).isEmpty())
            game.setInputGrabState(false)
            handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
            assertTrue(forwarded(game).isEmpty())
        }
    }

    @Test
    fun focusLossReleasesKeysAndMouseEvenWithoutAccessibility() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            KeyboardAccessibilityService.setIntercepting(false)
            game.prefConfig.mouseNavButtons = false
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_MOUSE))
            assertFalse(forwarded(game).isEmpty())
            assertTrue(field(game.keyboardInputHandler, "rightMouseButtonDown") as Boolean)
            game.onWindowFocusChanged(false)
        }
        idle()
        scenario.onActivity { game ->
            assertTrue(forwarded(game).isEmpty())
            assertFalse(field(game.keyboardInputHandler, "rightMouseButtonDown") as Boolean)
            assertTrue(game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W)))
            assertTrue(game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_MOUSE)))
        }
    }

    @Test
    fun transfersBetweenStreamWindowsPreserveHeldKeysInEitherCallbackOrder() = withGame { scenario ->
        lateinit var callback: ExternalDisplayManager.ExternalDisplayCallback
        scenario.onActivity { game ->
            connect(game)
            callback = Game::class.java.getDeclaredMethod("createExternalDisplayCallback").apply {
                isAccessible = true
            }.invoke(game) as ExternalDisplayManager.ExternalDisplayCallback
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            game.onWindowFocusChanged(false)
            callback.onStreamWindowFocusChanged(true)
        }
        idle()
        scenario.onActivity { game ->
            assertFalse(forwarded(game).isEmpty())
            game.onWindowFocusChanged(true)
            callback.onStreamWindowFocusChanged(false)
        }
        idle()
        scenario.onActivity { game ->
            assertFalse(forwarded(game).isEmpty())
            game.onWindowFocusChanged(false)
        }
        idle()
        scenario.onActivity { assertTrue(forwarded(it).isEmpty()) }
    }

    @Test
    fun losingFocusCancelsPendingEscapeAndGrabShortcut() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            shortcut(game, KeyEvent.KEYCODE_Z)
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            game.onWindowFocusChanged(false)
        }
        idle()
        SystemClock.sleep(350)
        scenario.onActivity { game ->
            assertTrue("A stale shortcut changed input grab", game.grabbedInput)
            assertTrue("A delayed Escape was sent after focus loss", forwarded(game).isEmpty())
        }
    }

    @Test
    fun localDialogReleasesHeldKeysAndStopsNewForwarding() = withGame { scenario ->
        try {
            scenario.onActivity { game ->
                connect(game)
                game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
                assertFalse(forwarded(game).isEmpty())
                com.limelight.utils.Dialog.displayDialog(game, "Input test", "Local keyboard owner", false)
                assertFalse(game.canForwardKeyboardInput())
                assertTrue(forwarded(game).isEmpty())
                assertFalse(game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
                assertTrue(forwarded(game).isEmpty())
                com.limelight.utils.Dialog.closeDialogs()
            }
            idle()
            scenario.onActivity { game ->
                game.onWindowFocusChanged(true)
                assertTrue(game.canForwardKeyboardInput())
                assertTrue(game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W)))
                game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
                assertFalse(forwarded(game).isEmpty())
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { com.limelight.utils.Dialog.closeDialogs() }
        }
    }

    @Test
    fun stoppingConnectionReleasesKeysAndCancelsPendingShortcut() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            shortcut(game, KeyEvent.KEYCODE_Z)
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            Game::class.java.getDeclaredMethod("stopConnection", StreamStopReason::class.java).apply {
                isAccessible = true
            }.invoke(game, StreamStopReason.CONNECTION_TERMINATED)
            assertFalse(game.connected)
            assertTrue(forwarded(game).isEmpty())
        }
        SystemClock.sleep(350)
        scenario.onActivity { assertTrue(it.grabbedInput) }
    }

    @Test
    fun nativeMouseShortcutStillRestoresPointerOnLegacyAndModernAndroid() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.enableNativeMousePointer(true)
            game.setInputGrabState(false)
            assertTrue("Shortcut should remain available while ungrabbed", game.canUseKeyboardShortcuts())
            shortcut(game, KeyEvent.KEYCODE_C)
            assertTrue("Shortcut did not restore grab: modifiers=${field(game.keyboardInputHandler, "modifierFlags")}, special=${field(game.keyboardInputHandler, "specialKeyCode")}", game.grabbedInput)
            val providerField = com.limelight.binding.input.capture.InputCaptureProvider::class.java
                .getDeclaredField("isCapturing").apply { isAccessible = true }
            assertTrue(providerField.getBoolean(game.inputCaptureProvider))
        }
    }

    private fun withGame(block: (ActivityScenario<Game>) -> Unit) {
        val oldInterception = KeyboardAccessibilityService.interceptingEnabled
        try {
            KeyboardTestScenario.launch().use(block)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                KeyboardAccessibilityService.setIntercepting(oldInterception)
            }
        }
    }

    private fun connect(game: Game) {
        game.onWindowFocusChanged(true)
        val session = field(game, "streamSession") as StreamSessionCoordinator
        StreamSessionCoordinator::class.java.getDeclaredField("state").apply {
            isAccessible = true
        }.set(session, StreamSessionState.CONNECTING)
        assertTrue(Game::class.java.getDeclaredMethod("onConnectionEstablished").apply {
            isAccessible = true
        }.invoke(game) as Boolean)
        game.prefConfig.enableCustomKeyMap = false
        game.prefConfig.enableEscMenu = false
        game.setInputGrabState(true)
        assertTrue("Keyboard input should be active for the simulated connection", game.canForwardKeyboardInput())
    }

    private fun forwarded(game: Game) = field(game.keyboardInputHandler, "forwardedKeys") as Map<*, *>
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(target)
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun key(action: Int, code: Int, source: Int = InputDevice.SOURCE_KEYBOARD) = KeyEvent(
        1, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source
    )
    private fun shortcut(game: Game, code: Int) {
        val keys = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, code)
        keys.forEach { game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, it)) }
        keys.reversed().forEach { game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, it)) }
    }
}
