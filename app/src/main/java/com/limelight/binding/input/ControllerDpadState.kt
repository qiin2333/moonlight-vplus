package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket

/** A neutral hat report must not release a held direction key. */
internal class ControllerDpadState {
    private val keys = mutableMapOf<Int, Int>()
    private var hat = 0

    fun key(keyCode: Int, flags: Int, pressed: Boolean) {
        if (pressed) keys[keyCode] = flags and MASK else keys.remove(keyCode)
    }

    fun hat(x: Float, y: Float) {
        hat = 0
        if (x < -0.5f) hat = hat or ControllerPacket.LEFT_FLAG
        if (x > 0.5f) hat = hat or ControllerPacket.RIGHT_FLAG
        if (y < -0.5f) hat = hat or ControllerPacket.UP_FLAG
        if (y > 0.5f) hat = hat or ControllerPacket.DOWN_FLAG
    }

    fun applyTo(buttons: Int): Int =
        (buttons and MASK.inv()) or keys.values.fold(hat) { state, flags -> state or flags }

    companion object {
        const val MASK = ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG or
            ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG
    }
}
