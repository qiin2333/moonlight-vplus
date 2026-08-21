package com.limelight.binding.input.driver

/** Transport-independent adaptive trigger effect semantics used by DualSense reports. */
internal object DualSenseAdaptiveTriggerEffect {
    const val PAYLOAD_SIZE = 10
    const val TYPE_OFF: Byte = 0x05
    const val RIGHT_FLAG = 0x04
    const val LEFT_FLAG = 0x08
    const val BOTH_FLAGS = RIGHT_FLAG or LEFT_FLAG
    const val PLAYER_LED_FLAG = 0x80

    fun triggerRumble(strength: Short): Pair<Byte, ByteArray> {
        val normalized = ((strength.toInt() and 0xFFFF) * 0x3F / 0xFFFF)
        if (normalized == 0) return TYPE_OFF to ByteArray(PAYLOAD_SIZE)
        return 0x27.toByte() to ByteArray(PAYLOAD_SIZE).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0x03
            this[2] = normalized.toByte()
        }
    }
}
