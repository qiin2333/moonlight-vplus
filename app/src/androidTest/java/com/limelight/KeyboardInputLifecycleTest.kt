package com.limelight

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.services.KeyboardAccessibilityService
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.InputDeviceContext
import com.limelight.binding.input.StartGestureReducer
import com.limelight.nvstream.input.ControllerPacket
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
    fun focusLossReleasesKeysWhenTheOtherWindowCallbackArrivesLater() = withGame { scenario ->
        lateinit var callback: ExternalDisplayManager.ExternalDisplayCallback
        scenario.onActivity { game ->
            connect(game)
            callback = Game::class.java.getDeclaredMethod("createExternalDisplayCallback").apply {
                isAccessible = true
            }.invoke(game) as ExternalDisplayManager.ExternalDisplayCallback
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            game.onWindowFocusChanged(false)
        }
        idle()
        scenario.onActivity { game ->
            assertTrue(forwarded(game).isEmpty())
            callback.onStreamWindowFocusChanged(true)
            assertTrue(forwarded(game).isEmpty())
        }
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
    fun stoppingConnectionCancelsPendingShortcutWhileUngrabbed() = withGame { scenario ->
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

    @Test
    fun accessibilityCaptureLossCancelsOnlyItsHeldControllerButtons() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            withAccessibilityService(game) { service ->
                val controller = game.controllerHandler
                val deviceId = controllerDeviceId()
                val bDown = controllerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, deviceId)
                controller.handleButtonDown(bDown)
                val context = requireNotNull(controller.existingContextForEvent(bDown))
                val aDown = controllerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, deviceId)
                assertTrue(ControllerHandler.isGameControllerDevice(aDown.device))
                assertTrue(send(service, aDown))
                assertEquals(ControllerPacket.A_FLAG or ControllerPacket.B_FLAG, context.inputMap)

                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(ControllerPacket.B_FLAG, context.inputMap)
                assertTrue(send(service, controllerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, deviceId)))
                assertEquals(ControllerPacket.B_FLAG, context.inputMap)

                controller.handleButtonUp(controllerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, deviceId))
                KeyboardAccessibilityService.setIntercepting(true)
                assertTrue(send(service, controllerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, deviceId)))
                assertEquals(ControllerPacket.A_FLAG, context.inputMap)
                assertTrue(send(service, controllerKey(
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, deviceId, InputDevice.SOURCE_KEYBOARD
                )))
                assertEquals(0, context.inputMap)
                controller.handleButtonDown(aDown)
                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals("A completed accessibility key must not cancel a later input", ControllerPacket.A_FLAG, context.inputMap)
                controller.handleButtonUp(controllerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, deviceId))
            }
        }
    }

    @Test
    fun accessibilityCaptureLossCancelsStartWithoutTriggeringGesturesOrExit() = withGame { scenario ->
        lateinit var controllerContext: InputDeviceContext
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableStartKeyMenu = true
            withAccessibilityService(game) { service ->
                val deviceId = controllerDeviceId()
                val startDown = controllerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START, deviceId)
                assertTrue(send(service, startDown))
                val context = requireNotNull(game.controllerHandler.existingContextForEvent(startDown))
                controllerContext = context
                assertTrue(context.startGesture.state() != StartGestureReducer.State.IDLE)
                context.pendingExit = true

                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(StartGestureReducer.State.IDLE, context.startGesture.state())
                assertFalse(context.pendingExit)
                assertEquals(0, context.inputMap)
                assertFalse(game.isFinishing)
                assertTrue(send(service, controllerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START, deviceId)))
                assertFalse(game.isFinishing)
            }
        }
        SystemClock.sleep(800)
        scenario.onActivity {
            assertFalse(it.isFinishing)
            assertEquals(StartGestureReducer.State.IDLE, controllerContext.startGesture.state())
        }
    }

    @Test
    fun cancellingCapturedStartClearsEmulatedButtonsEvenWhenItsWheelIsOpen() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableStartKeyMenu = true
            withAccessibilityService(game) { service ->
                val controller = game.controllerHandler
                val deviceId = controllerDeviceId()
                val lbDown = controllerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1, deviceId)
                controller.handleButtonDown(lbDown)
                val context = requireNotNull(controller.existingContextForEvent(lbDown))
                context.hasSelect = false
                context.hasMode = false
                assertTrue(send(service, controllerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START, deviceId)))
                assertEquals(ControllerPacket.BACK_FLAG, context.inputMap)
                context.startGesture.onLongPressTimeout(SystemClock.uptimeMillis() + 1000, true)
                context.startGesture.onSelection(1f, 0f, 0f, 0f, context.inputMap)
                assertTrue(context.isLocalInputCaptureActive())

                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(0, context.inputMap)
                assertEquals(0, context.emulatingButtonFlags)
                assertFalse(game.isFinishing)
                controller.handleButtonUp(controllerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1, deviceId))
            }
        }
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
    private fun withAccessibilityService(game: Game, block: (KeyboardAccessibilityService) -> Unit) {
        val instance = KeyboardAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(null)
        val enabled = KeyboardAccessibilityService.interceptingEnabled
        KeyboardAccessibilityService.setIntercepting(false)
        val service = KeyboardAccessibilityService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, ApplicationProvider.getApplicationContext<Context>())
        try {
            instance.set(null, service)
            service.keyEventCallback = game
            KeyboardAccessibilityService.setIntercepting(true)
            block(service)
        } finally {
            KeyboardAccessibilityService.setIntercepting(false)
            service.keyEventCallback = null
            instance.set(null, previous)
            KeyboardAccessibilityService.setIntercepting(enabled)
        }
    }

    private fun send(service: KeyboardAccessibilityService, event: KeyEvent): Boolean =
        KeyboardAccessibilityService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply {
            isAccessible = true
        }.invoke(service, event) as Boolean

    private fun controllerDeviceId(): Int {
        if (ControllerHandler.isGameControllerDevice(InputDevice.getDevice(0))) return 0
        return InputDevice.getDeviceIds().first { ControllerHandler.isGameControllerDevice(InputDevice.getDevice(it)) }
    }

    private fun controllerKey(action: Int, code: Int, deviceId: Int, source: Int = InputDevice.SOURCE_GAMEPAD) = KeyEvent(
        SystemClock.uptimeMillis() - 100, SystemClock.uptimeMillis(), action, code, 0, 0, deviceId, 0, 0, source
    )
    private fun key(action: Int, code: Int, source: Int = InputDevice.SOURCE_KEYBOARD) = KeyEvent(
        1, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source
    )
    private fun shortcut(game: Game, code: Int) {
        val keys = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, code)
        keys.forEach { game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, it)) }
        keys.reversed().forEach { game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, it)) }
    }
}
