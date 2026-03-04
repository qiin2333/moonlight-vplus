package com.limelight.nvstream.input

object ControllerPacket {
    const val A_FLAG = 0x1000
    const val B_FLAG = 0x2000
    const val X_FLAG = 0x4000
    const val Y_FLAG = 0x8000
    const val UP_FLAG = 0x0001
    const val DOWN_FLAG = 0x0002
    const val LEFT_FLAG = 0x0004
    const val RIGHT_FLAG = 0x0008
    const val LB_FLAG = 0x0100
    const val RB_FLAG = 0x0200
    const val PLAY_FLAG = 0x0010
    const val BACK_FLAG = 0x0020
    const val LS_CLK_FLAG = 0x0040
    const val RS_CLK_FLAG = 0x0080
    const val SPECIAL_BUTTON_FLAG = 0x0400

    // Extended buttons (Sunshine only)
    const val PADDLE1_FLAG = 0x010000
    const val PADDLE2_FLAG = 0x020000
    const val PADDLE3_FLAG = 0x040000
    const val PADDLE4_FLAG = 0x080000
    const val TOUCHPAD_FLAG = 0x100000 // Touchpad buttons on Sony controllers
    const val MISC_FLAG = 0x200000 // Share/Mic/Capture/Mute buttons on various controllers
}
