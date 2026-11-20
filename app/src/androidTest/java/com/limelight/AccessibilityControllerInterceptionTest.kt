package com.limelight

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.InputDeviceContext
import com.limelight.binding.input.StartGestureReducer
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.services.KeyboardAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class AccessibilityControllerInterceptionTest {
    @Test
    fun captureLossKeepsOrdinaryKeyboardKeysUntilTheirOwnRelease() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                val deviceId = controllerDeviceId()
                val wDown = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertFalse(send(service, wDown))
                game.keyboardInputHandler.handleKeyDown(wDown)
                KeyboardAccessibilityService.setIntercepting(true)
                val aDown = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertTrue(send(service, aDown))
                assertEquals(setOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A), forwarded(game).keys)

                service.keyEventCallback = null
                assertEquals(setOf(KeyEvent.KEYCODE_W), forwarded(game).keys)
                assertTrue(send(service, KeyEvent.changeAction(aDown, KeyEvent.ACTION_UP)))
                val repeat = KeyEvent.changeTimeRepeat(wDown, SystemClock.uptimeMillis(), 1)
                assertFalse(send(service, repeat))
                game.keyboardInputHandler.handleKeyDown(repeat)
                assertEquals(setOf(KeyEvent.KEYCODE_W), forwarded(game).keys)
                val wUp = KeyEvent.changeAction(wDown, KeyEvent.ACTION_UP)
                assertFalse(send(service, wUp))
                game.keyboardInputHandler.handleKeyUp(wUp)
                assertTrue(forwarded(game).isEmpty())
            }
        }
    }

    @Test
    fun sameKeyHeldByBothOriginsRemainsDownForOrdinaryOwner() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                val ordinary = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, KeyCharacterMap.VIRTUAL_KEYBOARD, InputDevice.SOURCE_KEYBOARD)
                assertFalse(send(service, ordinary))
                game.keyboardInputHandler.handleKeyDown(ordinary)
                KeyboardAccessibilityService.setIntercepting(true)
                val accessible = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, controllerDeviceId(), InputDevice.SOURCE_KEYBOARD)
                assertTrue(send(service, accessible))
                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(setOf(KeyEvent.KEYCODE_W), forwarded(game).keys)
                assertTrue(send(service, KeyEvent.changeAction(accessible, KeyEvent.ACTION_UP)))
                val ordinaryUp = KeyEvent.changeAction(ordinary, KeyEvent.ACTION_UP)
                assertFalse(send(service, ordinaryUp))
                game.keyboardInputHandler.handleKeyUp(ordinaryUp)
                assertTrue(forwarded(game).isEmpty())
            }
        }
    }

    @Test
    fun captureLossPreservesOrdinaryModifiers() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                val deviceId = controllerDeviceId()
                val ctrl = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertFalse(send(service, ctrl))
                game.keyboardInputHandler.handleKeyDown(ctrl)
                KeyboardAccessibilityService.setIntercepting(true)
                val shift = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertTrue(send(service, shift))
                assertEquals(KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt(), field(game.keyboardInputHandler, "modifierFlags"))
                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(KeyboardPacket.MODIFIER_CTRL.toInt(), field(game.keyboardInputHandler, "modifierFlags"))
                val ctrlUp = KeyEvent.changeAction(ctrl, KeyEvent.ACTION_UP)
                assertFalse(send(service, ctrlUp))
                game.keyboardInputHandler.handleKeyUp(ctrlUp)
                assertEquals(0, field(game.keyboardInputHandler, "modifierFlags"))
            }
        }
    }

    @Test
    fun captureLossDoesNotCancelOrdinaryEscapeHold() = withGame { scenario ->
        var deviceId = 0
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                deviceId = controllerDeviceId()
                val down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertFalse(send(service, down))
                game.keyboardInputHandler.handleKeyDown(down)
                KeyboardAccessibilityService.setIntercepting(true)
                service.keyEventCallback = null
            }
        }
        SystemClock.sleep(250)
        scenario.onActivity { game ->
            assertTrue(forwarded(game).containsKey(KeyEvent.KEYCODE_ESCAPE))
            game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, deviceId, InputDevice.SOURCE_KEYBOARD))
            assertTrue(forwarded(game).isEmpty())
        }
    }

    @Test
    fun captureLossKeepsOrdinaryEscapeTapRelease() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                val down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, controllerDeviceId(), InputDevice.SOURCE_KEYBOARD)
                assertFalse(send(service, down))
                game.keyboardInputHandler.handleKeyDown(down)
                val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
                assertFalse(send(service, up))
                game.keyboardInputHandler.handleKeyUp(up)
                assertTrue(forwarded(game).containsKey(KeyEvent.KEYCODE_ESCAPE))
                KeyboardAccessibilityService.setIntercepting(true)
                service.keyEventCallback = null
                assertTrue(forwarded(game).containsKey(KeyEvent.KEYCODE_ESCAPE))
            }
        }
        SystemClock.sleep(100)
        scenario.onActivity { assertTrue(forwarded(it).isEmpty()) }
    }

    @Test
    fun captureLossReleasesAccessibleEscapeTapButKeepsOrdinaryKey() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                val deviceId = controllerDeviceId()
                val w = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertFalse(send(service, w))
                game.keyboardInputHandler.handleKeyDown(w)
                KeyboardAccessibilityService.setIntercepting(true)
                val esc = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertTrue(send(service, esc))
                assertTrue(send(service, KeyEvent.changeAction(esc, KeyEvent.ACTION_UP)))
                assertEquals(setOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_ESCAPE), forwarded(game).keys)
                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(setOf(KeyEvent.KEYCODE_W), forwarded(game).keys)
                val wUp = KeyEvent.changeAction(w, KeyEvent.ACTION_UP)
                assertFalse(send(service, wUp))
                game.keyboardInputHandler.handleKeyUp(wUp)
                assertTrue(forwarded(game).isEmpty())
            }
        }
        SystemClock.sleep(100)
        scenario.onActivity { assertTrue(forwarded(it).isEmpty()) }
    }

    @Test
    fun captureLossKeepsOrdinaryMappedKeyUntilItsRelease() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                val deviceId = controllerDeviceId()
                val one = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertFalse(send(service, one))
                game.keyboardInputHandler.handleKeyDown(one)
                KeyboardAccessibilityService.setIntercepting(true)
                val esc = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertTrue(send(service, esc))
                val repeat = KeyEvent.changeTimeRepeat(one, SystemClock.uptimeMillis(), 1)
                assertFalse(send(service, repeat))
                game.keyboardInputHandler.handleKeyDown(repeat)
                assertEquals(setOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_F1), forwarded(game).keys)

                service.keyEventCallback = null
                assertEquals(setOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_F1), forwarded(game).keys)
                assertTrue(send(service, KeyEvent.changeAction(esc, KeyEvent.ACTION_UP)))
                val oneUp = KeyEvent.changeAction(one, KeyEvent.ACTION_UP)
                assertFalse(send(service, oneUp))
                game.keyboardInputHandler.handleKeyUp(oneUp)
                assertTrue(forwarded(game).isEmpty())
            }
        }
    }

    @Test
    fun captureLossCancelsAccessibleEscapeHold() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            withService(game) { service ->
                val down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, controllerDeviceId(), InputDevice.SOURCE_KEYBOARD)
                assertTrue(send(service, down))
                KeyboardAccessibilityService.setIntercepting(false)
                assertTrue(send(service, KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
            }
        }
        SystemClock.sleep(250)
        scenario.onActivity { assertTrue(forwarded(it).isEmpty()) }
    }

    @Test
    fun captureLossDoesNotCancelOrdinaryGrabShortcut() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.setInputGrabState(false)
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                shortcut(service, game, controllerDeviceId(), captured = false)
                KeyboardAccessibilityService.setIntercepting(true)
                service.keyEventCallback = null
            }
        }
        SystemClock.sleep(350)
        scenario.onActivity { assertTrue(it.grabbedInput) }
    }

    @Test
    fun captureLossCancelsAccessibleGrabShortcut() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.setInputGrabState(false)
            withService(game) { service ->
                shortcut(service, game, controllerDeviceId(), captured = true)
                KeyboardAccessibilityService.setIntercepting(false)
            }
        }
        SystemClock.sleep(350)
        scenario.onActivity { assertFalse(it.grabbedInput) }
    }

    @Test
    fun captureLossCancelsOnlyAccessiblePendingShortcut() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.setInputGrabState(false)
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                shortcut(service, game, controllerDeviceId(), captured = false)
                KeyboardAccessibilityService.setIntercepting(true)
                shortcut(service, game, controllerDeviceId(), captured = true)
                KeyboardAccessibilityService.setIntercepting(false)
            }
        }
        SystemClock.sleep(350)
        scenario.onActivity { assertTrue(it.grabbedInput) }
    }

    @Test
    fun captureLossCancelsShortcutJoinedByAccessibleModifier() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.setInputGrabState(false)
            withService(game) { service ->
                KeyboardAccessibilityService.setIntercepting(false)
                val deviceId = controllerDeviceId()
                val modifiers = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT)
                fun ordinary(action: Int, code: Int) {
                    val event = key(action, code, deviceId, InputDevice.SOURCE_KEYBOARD)
                    assertFalse(send(service, event))
                    if (action == KeyEvent.ACTION_DOWN) game.keyboardInputHandler.handleKeyDown(event)
                    else game.keyboardInputHandler.handleKeyUp(event)
                }
                modifiers.forEach { ordinary(KeyEvent.ACTION_DOWN, it) }
                ordinary(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z)
                ordinary(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z)
                KeyboardAccessibilityService.setIntercepting(true)
                val meta = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT, deviceId, InputDevice.SOURCE_KEYBOARD)
                assertTrue(send(service, meta))
                modifiers.reversed().forEach { ordinary(KeyEvent.ACTION_UP, it) }
                KeyboardAccessibilityService.setIntercepting(false)
                assertTrue(send(service, KeyEvent.changeAction(meta, KeyEvent.ACTION_UP)))
                ordinary(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
                ordinary(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)
            }
        }
        SystemClock.sleep(350)
        scenario.onActivity { assertFalse(it.grabbedInput) }
    }

    @Test
    fun captureLossReleasesOnlyOwnedControllerKeys() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            withService(game) { service ->
                val controller = game.controllerHandler
                val deviceId = controllerDeviceId()
                val bDown = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, deviceId)
                controller.handleButtonDown(bDown)
                val context = requireNotNull(controller.existingContextForEvent(bDown))
                val aDown = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, deviceId)
                assertTrue(send(service, aDown))
                assertEquals(ControllerPacket.A_FLAG or ControllerPacket.B_FLAG, context.inputMap)

                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(ControllerPacket.B_FLAG, context.inputMap)
                assertTrue(send(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, deviceId)))
                assertEquals(ControllerPacket.B_FLAG, context.inputMap)

                KeyboardAccessibilityService.setIntercepting(true)
                assertTrue(send(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, deviceId)))
                assertTrue(send(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, deviceId, InputDevice.SOURCE_KEYBOARD)))
                controller.handleButtonDown(aDown)
                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(ControllerPacket.A_FLAG or ControllerPacket.B_FLAG, context.inputMap)
                controller.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, deviceId))
                controller.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, deviceId))
                assertEquals(0, context.inputMap)
            }
        }
    }

    @Test
    fun captureLossCancelsStartWithoutCommittingGestureOrExit() = withGame { scenario ->
        lateinit var context: InputDeviceContext
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableStartKeyMenu = true
            withService(game) { service ->
                val deviceId = controllerDeviceId()
                val down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START, deviceId)
                assertTrue(send(service, down))
                context = requireNotNull(game.controllerHandler.existingContextForEvent(down))
                assertTrue(context.startGesture.state() != StartGestureReducer.State.IDLE)
                context.pendingExit = true
                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(StartGestureReducer.State.IDLE, context.startGesture.state())
                assertFalse(context.pendingExit)
                assertEquals(0, context.inputMap)
                assertFalse(game.isFinishing)
                assertTrue(send(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START, deviceId)))
            }
        }
        SystemClock.sleep(800)
        scenario.onActivity {
            assertFalse(it.isFinishing)
            assertEquals(StartGestureReducer.State.IDLE, context.startGesture.state())
        }
    }

    @Test
    fun captureLossClearsEmulatedButtonsWhenStartWheelIsOpen() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableStartKeyMenu = true
            withService(game) { service ->
                val controller = game.controllerHandler
                val deviceId = controllerDeviceId()
                val lbDown = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1, deviceId)
                controller.handleButtonDown(lbDown)
                val context = requireNotNull(controller.existingContextForEvent(lbDown))
                context.hasSelect = false
                context.hasMode = false
                assertTrue(send(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START, deviceId)))
                assertEquals(ControllerPacket.BACK_FLAG, context.inputMap)
                context.startGesture.onLongPressTimeout(SystemClock.uptimeMillis() + 1000, true)
                context.startGesture.onSelection(1f, 0f, 0f, 0f, context.inputMap)
                assertTrue(context.isLocalInputCaptureActive())
                KeyboardAccessibilityService.setIntercepting(false)
                assertEquals(0, context.inputMap)
                assertEquals(0, context.emulatingButtonFlags)
                assertFalse(game.isFinishing)
                controller.handleButtonUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1, deviceId))
            }
        }
    }

    @Test
    fun passedKeyboardEventsAreNotDiscardedJustBecauseServiceExists() = withGame { scenario ->
        scenario.onActivity { game ->
            connect(game)
            withService(game) {
                KeyboardAccessibilityService.setIntercepting(false)
                val event = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, controllerDeviceId(), InputDevice.SOURCE_KEYBOARD)
                game.keyboardInputHandler.handleKeyDown(event)
                assertFalse(forwarded(game).isEmpty())
                game.keyboardInputHandler.handleKeyUp(KeyEvent.changeAction(event, KeyEvent.ACTION_UP))
                assertTrue(forwarded(game).isEmpty())
            }
        }
    }

    @Test
    fun ungrabbedControllerClassifiedKeypadDoesNotSendEscapeMapping() = withGame { scenario ->
        var deviceId = 0
        scenario.onActivity { game ->
            connect(game)
            game.prefConfig.enableCustomKeyMap = true
            game.setInputGrabState(false)
            deviceId = controllerDeviceId()
            game.keyboardInputHandler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, deviceId, InputDevice.SOURCE_KEYBOARD))
        }
        SystemClock.sleep(250)
        scenario.onActivity { game ->
            assertTrue(forwarded(game).isEmpty())
            game.keyboardInputHandler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, deviceId, InputDevice.SOURCE_KEYBOARD))
            assertTrue(forwarded(game).isEmpty())
        }
    }

    private fun withGame(block: (ActivityScenario<Game>) -> Unit) {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is Game && stage == Stage.CREATED) {
                activity.streamView.holder.removeCallback(activity)
                activity.activeStreamView?.holder?.removeCallback(activity)
                activity.progressOverlay?.dismiss()
                activity.progressOverlay = null
            }
        }
        monitor.addLifecycleCallback(callback)
        try {
            val intent = Intent(ApplicationProvider.getApplicationContext(), Game::class.java)
                .putExtra(Game.EXTRA_APP_ID, 1).putExtra(Game.EXTRA_APP_NAME, "app")
                .putExtra(Game.EXTRA_HOST, InetAddress.getLoopbackAddress().hostAddress)
                .putExtra(Game.EXTRA_PORT, 1)
            ActivityScenario.launch<Game>(intent).use(block)
        } finally {
            monitor.removeLifecycleCallback(callback)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { KeyboardAccessibilityService.setIntercepting(false) }
        }
    }

    private fun withService(game: Game, block: (KeyboardAccessibilityService) -> Unit) {
        val instance = KeyboardAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(null)
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
        }
    }

    private fun connect(game: Game) {
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
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun send(service: KeyboardAccessibilityService, event: KeyEvent): Boolean =
        KeyboardAccessibilityService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply {
            isAccessible = true
        }.invoke(service, event) as Boolean

    private fun controllerDeviceId(): Int {
        if (ControllerHandler.isGameControllerDevice(InputDevice.getDevice(0))) return 0
        return requireNotNull(InputDevice.getDeviceIds().firstOrNull {
            ControllerHandler.isGameControllerDevice(InputDevice.getDevice(it))
        }) { "The test requires a controller-class input device" }
    }

    private fun key(action: Int, code: Int, deviceId: Int, source: Int = InputDevice.SOURCE_GAMEPAD) = KeyEvent(
        SystemClock.uptimeMillis() - 100, SystemClock.uptimeMillis(), action, code, 0, 0, deviceId, 0, 0, source
    )

    private fun shortcut(service: KeyboardAccessibilityService, game: Game, deviceId: Int, captured: Boolean) {
        val keys = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_Z)
        fun dispatch(action: Int, code: Int) {
            val event = key(action, code, deviceId, InputDevice.SOURCE_KEYBOARD)
            assertEquals(captured, send(service, event))
            if (!captured) {
                if (action == KeyEvent.ACTION_DOWN) game.keyboardInputHandler.handleKeyDown(event)
                else game.keyboardInputHandler.handleKeyUp(event)
            }
        }
        keys.forEach { dispatch(KeyEvent.ACTION_DOWN, it) }
        keys.reversed().forEach { dispatch(KeyEvent.ACTION_UP, it) }
    }
}
