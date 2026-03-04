package com.limelight.binding.input.evdev

interface EvdevListener {
    fun mouseMove(deltaX: Int, deltaY: Int)
    fun mouseButtonEvent(buttonId: Int, down: Boolean)
    fun mouseVScroll(amount: Byte)
    fun mouseHScroll(amount: Byte)
    fun keyboardEvent(buttonDown: Boolean, keyCode: Short)

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_MIDDLE = 2
        const val BUTTON_RIGHT = 3
        const val BUTTON_X1 = 4
        const val BUTTON_X2 = 5
    }
}
