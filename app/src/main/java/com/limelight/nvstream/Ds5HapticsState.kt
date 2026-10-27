package com.limelight.nvstream

/** Validated absolute actuator controls received from the host, independent of local HID reports. */
class Ds5HapticsState(bytes: ByteArray) {
    init {
        require(bytes.size == 8 && bytes[0].toInt() and 3.inv() == 0 &&
            bytes[1].toInt() and 4.inv() == 0 && bytes[2].toInt() and 0x62.inv() == 0 &&
            bytes[7].toInt() and 1.inv() == 0)
    }

    val modeFlags0: Int = bytes[0].toInt()
    val modeFlags2: Int = bytes[1].toInt()
    val controlGroups: Int = bytes[2].toInt()
    val motorRight: Byte = bytes[3]
    val motorLeft: Byte = bytes[4]
    val powerSaveControl: Byte = bytes[5]
    val vibrationReduction: Byte = bytes[6]
    val lowPassFilter: Int = bytes[7].toInt()
}
