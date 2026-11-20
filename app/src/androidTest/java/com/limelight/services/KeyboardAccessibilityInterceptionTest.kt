package com.limelight.services

import android.view.InputDevice
import android.view.KeyEvent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardAccessibilityInterceptionTest {
    @Test
    fun pauseAndResumeKeepEachKeySequenceWithItsOriginalOwner() {
        var service = KeyboardAccessibilityService.instance
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (service == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            service = KeyboardAccessibilityService.instance
        }
        assumeNotNull(service)
        val connectedService = service
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val keyboardService = requireNotNull(connectedService)
            val callbackField = KeyboardAccessibilityService::class.java.getDeclaredField("keyEventCallback")
                .apply { isAccessible = true }
            val oldCallback = callbackField.get(keyboardService)
            val oldEnabled = KeyboardAccessibilityService.interceptingEnabled
            val forwarded = mutableListOf<Int>()
            val dispatch = KeyboardAccessibilityService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java)
                .apply { isAccessible = true }
            fun send(event: KeyEvent): Boolean = dispatch.invoke(keyboardService, event) as Boolean
            try {
                callbackField.set(keyboardService, object : KeyboardAccessibilityService.KeyEventCallback {
                    override fun onKeyEvent(event: KeyEvent) {
                        forwarded.add(event.keyCode)
                    }
                })
                KeyboardAccessibilityService.setIntercepting(true)
                val capturedDown = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 100, 100)
                assertTrue(send(capturedDown))
                assertTrue(KeyboardAccessibilityService.wasForwarded(capturedDown))

                KeyboardAccessibilityService.setIntercepting(false)
                assertTrue(send(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 100, 101, 1)))
                val passedDown = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 200, 200)
                assertFalse(send(passedDown))

                KeyboardAccessibilityService.setIntercepting(true)
                assertFalse(send(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, 200, 201)))
                assertFalse(KeyboardAccessibilityService.wasForwarded(passedDown))
                assertTrue(send(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 100, 102)))
                assertEquals(listOf(KeyEvent.KEYCODE_A), forwarded)

                assertTrue(send(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 300, 300)))
                assertTrue(send(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 300, 301)))
                assertEquals(listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_C), forwarded)
            } finally {
                KeyboardAccessibilityService.setIntercepting(false)
                callbackField.set(keyboardService, oldCallback)
                KeyboardAccessibilityService.setIntercepting(oldEnabled)
            }
        }
    }

    private fun key(action: Int, keyCode: Int, downTime: Long, eventTime: Long, repeat: Int = 0) =
        KeyEvent(downTime, eventTime, action, keyCode, repeat, 0, 42, keyCode, 0, InputDevice.SOURCE_KEYBOARD)
}
