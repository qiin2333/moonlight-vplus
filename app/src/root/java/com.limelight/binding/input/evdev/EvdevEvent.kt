package com.limelight.binding.input.evdev

class EvdevEvent(
    val type: Short,
    val code: Short,
    val value: Int
) {
    companion object {
        const val EVDEV_MIN_EVENT_SIZE = 16
        const val EVDEV_MAX_EVENT_SIZE = 24

        /* Event types */
        const val EV_SYN: Short = 0x00
        const val EV_KEY: Short = 0x01
        const val EV_REL: Short = 0x02
        const val EV_MSC: Short = 0x04

        /* Relative axes */
        const val REL_X: Short = 0x00
        const val REL_Y: Short = 0x01
        const val REL_HWHEEL: Short = 0x06
        const val REL_WHEEL: Short = 0x08

        /* Buttons */
        const val BTN_LEFT: Short = 0x110
        const val BTN_RIGHT: Short = 0x111
        const val BTN_MIDDLE: Short = 0x112
        const val BTN_SIDE: Short = 0x113
        const val BTN_EXTRA: Short = 0x114
        const val BTN_FORWARD: Short = 0x115
        const val BTN_BACK: Short = 0x116
        const val BTN_TASK: Short = 0x117
    }
}
