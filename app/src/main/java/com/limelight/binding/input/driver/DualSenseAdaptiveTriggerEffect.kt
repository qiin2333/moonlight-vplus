package com.limelight.binding.input.driver

/** Transport-independent adaptive trigger effect semantics used by DualSense reports. */
internal object DualSenseAdaptiveTriggerEffect {
    const val PAYLOAD_SIZE = 10
    const val TYPE_OFF: Byte = 0x05
    const val RIGHT_FLAG = 0x04
    const val LEFT_FLAG = 0x08
    const val BOTH_FLAGS = RIGHT_FLAG or LEFT_FLAG
}
