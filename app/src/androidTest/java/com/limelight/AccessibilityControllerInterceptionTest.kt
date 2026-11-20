package com.limelight

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
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
import com.limelight.services.KeyboardAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class AccessibilityControllerInterceptionTest {
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
}
