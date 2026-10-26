package com.limelight.nvstream

/** Absolute actuator controls; unrelated audio, triggers and LEDs retain their own ownership. */
class Ds5HapticsState(bytes: ByteArray) {
    private val state = bytes.copyOf()
    init {
        require(state.size == 8 && state[0].toInt() and 3.inv() == 0 &&
            state[1].toInt() and 4.inv() == 0 && state[2].toInt() and 0x62.inv() == 0 &&
            state[7].toInt() and 1.inv() == 0)
    }

    /** Common 47-byte DS5 state starts at 1 in USB reports and 3 in Bluetooth reports. */
    fun applyTo(report: ByteArray, offset: Int) {
        require(report.size >= offset + 47)
        report[offset] = ((report[offset].toInt() and 3.inv()) or state[0].toInt()).toByte()
        report[offset + 1] = (report[offset + 1].toInt() or state[2].toInt()).toByte()
        report[offset + 2] = state[3]
        report[offset + 3] = state[4]
        report[offset + 38] = ((report[offset + 38].toInt() and 4.inv()) or state[1].toInt()).toByte()
        if (state[2].toInt() and 2 != 0) report[offset + 9] = state[5]
        if (state[2].toInt() and 0x40 != 0) report[offset + 36] = state[6]
        if (state[2].toInt() and 0x20 != 0) report[offset + 39] =
            ((report[offset + 39].toInt() and 1.inv()) or state[7].toInt()).toByte()
    }
}
