package com.limelight.nvstream.input

object MouseButtonPacket {
    const val PRESS_EVENT: Byte = 0x07
    const val RELEASE_EVENT: Byte = 0x08

    const val BUTTON_LEFT: Byte = 0x01
    const val BUTTON_MIDDLE: Byte = 0x02
    const val BUTTON_RIGHT: Byte = 0x03
    const val BUTTON_X1: Byte = 0x04
    const val BUTTON_X2: Byte = 0x05
}
