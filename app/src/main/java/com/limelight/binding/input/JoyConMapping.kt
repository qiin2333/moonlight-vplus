package com.limelight.binding.input

import android.view.KeyEvent
import android.view.MotionEvent

/** Vertical Android layout, matching SDL's Android Joy-Con mapping (SDL_gamepad.c). */
internal object JoyConMapping {
    fun stickAxes(side: JoyConSide, available: Set<Int>): Pair<Int, Int>? {
        val candidates = if (side == JoyConSide.LEFT) {
            listOf(MotionEvent.AXIS_X to MotionEvent.AXIS_Y)
        } else {
            listOf(
                MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_RX to MotionEvent.AXIS_RY,
                MotionEvent.AXIS_X to MotionEvent.AXIS_Y
            )
        }
        return candidates.firstOrNull { (x, y) -> x in available && y in available }
    }

    fun key(side: JoyConSide?, keyCode: Int, scanCode: Int = 0): Int = if (
        side == JoyConSide.LEFT && keyCode == KeyEvent.KEYCODE_UNKNOWN
    ) {
        // Linux BTN_DPAD_* may be absent in Android's Generic.kl.
        when (scanCode) {
            0x220 -> KeyEvent.KEYCODE_DPAD_UP
            0x221 -> KeyEvent.KEYCODE_DPAD_DOWN
            0x222 -> KeyEvent.KEYCODE_DPAD_LEFT
            0x223 -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> keyCode
        }
    } else if (side == JoyConSide.RIGHT) {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }
    } else keyCode
}
