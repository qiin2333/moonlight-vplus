package com.limelight.services

import android.content.Context
import android.content.ContextWrapper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardAccessibilityInterceptionTest {
    @Test
    fun chordsKeepOwnershipWhenUpCarriesAnotherKeysDownTime() = withService { service ->
        val forwarded = mutableListOf<Int>()
        var releases = 0
        service.keyEventCallback = object : KeyboardAccessibilityService.KeyEventCallback {
            override fun onKeyEvent(event: KeyEvent) { forwarded.add(event.keyCode) }
            override fun onKeyboardCaptureLost() { releases++ }
        }
        KeyboardAccessibilityService.setIntercepting(true)
        assertTrue(send(service, key(0, KeyEvent.KEYCODE_CTRL_LEFT, 100, 100)))
        assertTrue(send(service, key(0, KeyEvent.KEYCODE_A, 110, 110)))
        KeyboardAccessibilityService.setIntercepting(false)
        assertEquals(1, releases)
        KeyboardAccessibilityService.setIntercepting(true)
        assertTrue(send(service, key(1, KeyEvent.KEYCODE_CTRL_LEFT, 110, 120)))
        assertTrue(send(service, key(1, KeyEvent.KEYCODE_A, 110, 130)))
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A), forwarded)

        KeyboardAccessibilityService.setIntercepting(false)
        assertFalse(send(service, key(0, KeyEvent.KEYCODE_CTRL_LEFT, 200, 200)))
        assertFalse(send(service, key(0, KeyEvent.KEYCODE_A, 210, 210)))
        KeyboardAccessibilityService.setIntercepting(true)
        assertFalse(send(service, key(1, KeyEvent.KEYCODE_CTRL_LEFT, 210, 220)))
        assertFalse(send(service, key(1, KeyEvent.KEYCODE_A, 210, 230)))
        assertEquals(2, forwarded.size)
    }

    @Test
    fun callbackChangesDrainOldKeysAndAbsentCallbacksPassNewKeys() = withService { service ->
        KeyboardAccessibilityService.setIntercepting(true)
        assertFalse(send(service, key(0, KeyEvent.KEYCODE_B, 10, 10)))
        val forwarded = mutableListOf<Int>()
        service.keyEventCallback = object : KeyboardAccessibilityService.KeyEventCallback {
            override fun onKeyEvent(event: KeyEvent) { forwarded.add(event.keyCode) }
            override fun onKeyboardCaptureLost() = Unit
        }
        assertFalse(send(service, key(1, KeyEvent.KEYCODE_B, 10, 20)))
        val a = key(0, KeyEvent.KEYCODE_A, 30, 30)
        assertTrue(send(service, a))
        assertTrue(KeyboardAccessibilityService.wasConsumed(a))
        assertFalse(KeyboardAccessibilityService.wasConsumed(key(0, KeyEvent.KEYCODE_C, 40, 40)))
        service.keyEventCallback = null
        assertTrue(send(service, key(1, KeyEvent.KEYCODE_A, 30, 50)))
        assertFalse(send(service, key(0, KeyEvent.KEYCODE_C, 60, 60)))
        assertEquals(listOf(KeyEvent.KEYCODE_A), forwarded)
    }

    @Test
    fun systemNavigationAndUnmatchedReleasePassThrough() = withService { service ->
        service.keyEventCallback = object : KeyboardAccessibilityService.KeyEventCallback {
            override fun onKeyEvent(event: KeyEvent) { fail("Unexpected forwarding") }
            override fun onKeyboardCaptureLost() = Unit
        }
        KeyboardAccessibilityService.setIntercepting(true)
        assertFalse(send(service, key(0, KeyEvent.KEYCODE_VOLUME_UP, 10, 10)))
        assertFalse(send(service, key(1, KeyEvent.KEYCODE_VOLUME_UP, 10, 20)))
        assertFalse(send(service, key(1, KeyEvent.KEYCODE_ESCAPE, 30, 40)))
    }

    private fun withService(block: (KeyboardAccessibilityService) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Exercise the real adapter without changing system accessibility settings.
            val instanceField = KeyboardAccessibilityService::class.java.getDeclaredField("instance").apply {
                isAccessible = true
            }
            val previous = instanceField.get(null)
            val service = KeyboardAccessibilityService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
            }.invoke(service, ApplicationProvider.getApplicationContext<Context>())
            try {
                instanceField.set(null, service)
                KeyboardAccessibilityService.setIntercepting(false)
                block(service)
            } finally {
                KeyboardAccessibilityService.setIntercepting(false)
                service.keyEventCallback = null
                instanceField.set(null, previous)
                KeyboardAccessibilityService.setIntercepting(false)
            }
        }
    }

    private fun send(service: KeyboardAccessibilityService, event: KeyEvent): Boolean =
        KeyboardAccessibilityService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply {
            isAccessible = true
        }.invoke(service, event) as Boolean

    private fun key(action: Int, code: Int, downTime: Long, time: Long) =
        KeyEvent(downTime, time, action, code, 0, 0, 42, code, 0, InputDevice.SOURCE_KEYBOARD)
}
