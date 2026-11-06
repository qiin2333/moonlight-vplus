package com.limelight.ui

import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerInputTest {
    @Test
    fun textAndPointerSourcesDoNotActivateControllerNavigation() {
        for (source in listOf(
            InputDevice.SOURCE_KEYBOARD,
            InputDevice.SOURCE_MOUSE,
            InputDevice.SOURCE_TOUCHSCREEN,
            InputDevice.SOURCE_HDMI,
            InputDevice.SOURCE_UNKNOWN
        )) {
            assertFalse("Unexpected controller source: $source", event(source).isGamepadNavigationSource())
        }
    }

    @Test
    fun gamepadDpadAndJoystickSourcesActivateControllerNavigation() {
        for (source in listOf(
            InputDevice.SOURCE_GAMEPAD,
            InputDevice.SOURCE_DPAD,
            InputDevice.SOURCE_JOYSTICK
        )) {
            assertTrue("Missing controller source: $source", event(source).isGamepadNavigationSource())
        }
    }

    @Test
    fun joystickClassOnlySourcesRemainSupported() {
        assertTrue(event(InputDevice.SOURCE_CLASS_JOYSTICK).isGamepadNavigationSource())
    }

    @Test
    fun keyboardTaggedControllerSourcesRemainSupported() {
        for (source in listOf(
            InputDevice.SOURCE_GAMEPAD,
            InputDevice.SOURCE_DPAD,
            InputDevice.SOURCE_JOYSTICK,
            InputDevice.SOURCE_CLASS_JOYSTICK
        )) {
            assertTrue(
                "Keyboard flag must not exclude a controller: $source",
                event(source or InputDevice.SOURCE_KEYBOARD).isGamepadNavigationSource()
            )
        }
    }

    private fun event(source: Int): KeyEvent =
        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1).apply { this.source = source }
}
