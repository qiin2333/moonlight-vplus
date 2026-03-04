package com.limelight.nvstream.input

object KeyboardPacket {
    const val KEY_DOWN: Byte = 0x03
    const val KEY_UP: Byte = 0x04

    const val MODIFIER_SHIFT: Byte = 0x01
    const val MODIFIER_CTRL: Byte = 0x02
    const val MODIFIER_ALT: Byte = 0x04
    const val MODIFIER_META: Byte = 0x08
}
