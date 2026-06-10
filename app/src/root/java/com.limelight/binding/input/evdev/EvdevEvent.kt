package com.limelight.binding.input.evdev

class EvdevEvent(
    val type: Short,
    val code: Short,
    val value: Int,
    val deviceId: Int = 0,
    val deviceClass: Int = DEVICE_CLASS_LEGACY,
    val absXMin: Int = 0,
    val absXMax: Int = 0,
    val absYMin: Int = 0,
    val absYMax: Int = 0,
    val absXResolution: Int = 0,
    val absYResolution: Int = 0
) {
    val isTouchpad: Boolean
        get() = deviceClass and DEVICE_CLASS_TOUCHPAD != 0

    companion object {
        const val EVDEV_MIN_EVENT_SIZE = 16
        const val EVDEV_MAX_EVENT_SIZE = 24
        const val EVDEV_WRAPPED_EVENT_SIZE = 64
        const val EVDEV_PACKET_VERSION = 1

        const val DEVICE_CLASS_LEGACY = 0
        const val DEVICE_CLASS_MOUSE = 0x01
        const val DEVICE_CLASS_KEYBOARD = 0x02
        const val DEVICE_CLASS_TOUCHPAD = 0x04

        /* Event types */
        const val EV_SYN: Short = 0x00
        const val EV_KEY: Short = 0x01
        const val EV_REL: Short = 0x02
        const val EV_ABS: Short = 0x03
        const val EV_MSC: Short = 0x04

        /* Synchronization events */
        const val SYN_REPORT: Short = 0x00
        const val SYN_DROPPED: Short = 0x03

        /* Relative axes */
        const val REL_X: Short = 0x00
        const val REL_Y: Short = 0x01
        const val REL_HWHEEL: Short = 0x06
        const val REL_WHEEL: Short = 0x08

        /* Multi-touch absolute axes */
        const val ABS_MT_SLOT: Short = 0x2f
        const val ABS_MT_TOUCH_MAJOR: Short = 0x30
        const val ABS_MT_TOUCH_MINOR: Short = 0x31
        const val ABS_MT_POSITION_X: Short = 0x35
        const val ABS_MT_POSITION_Y: Short = 0x36
        const val ABS_MT_TRACKING_ID: Short = 0x39
        const val ABS_MT_PRESSURE: Short = 0x3a

        /* Buttons */
        const val BTN_LEFT: Short = 0x110
        const val BTN_RIGHT: Short = 0x111
        const val BTN_MIDDLE: Short = 0x112
        const val BTN_SIDE: Short = 0x113
        const val BTN_EXTRA: Short = 0x114
        const val BTN_FORWARD: Short = 0x115
        const val BTN_BACK: Short = 0x116
        const val BTN_TASK: Short = 0x117
        const val BTN_TOOL_QUINTTAP: Short = 0x148
        const val BTN_TOOL_FINGER: Short = 0x145
        const val BTN_TOOL_DOUBLETAP: Short = 0x14d
        const val BTN_TOOL_TRIPLETAP: Short = 0x14e
        const val BTN_TOOL_QUADTAP: Short = 0x14f
    }
}
