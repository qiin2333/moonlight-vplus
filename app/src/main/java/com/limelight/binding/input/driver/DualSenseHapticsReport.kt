package com.limelight.binding.input.driver

import com.limelight.nvstream.Ds5HapticsState

/** Shared 47-byte HID state starts at 1 for USB reports and 3 for Bluetooth reports. */
internal fun Ds5HapticsState.applyToHidReport(report: ByteArray, offset: Int) {
    require(report.size >= offset + 47)
    report[offset] = ((report[offset].toInt() and 3.inv()) or modeFlags0).toByte()
    report[offset + 1] = (report[offset + 1].toInt() or controlGroups).toByte()
    report[offset + 2] = motorRight
    report[offset + 3] = motorLeft
    report[offset + 38] = ((report[offset + 38].toInt() and 4.inv()) or modeFlags2).toByte()
    if (controlGroups and 2 != 0) report[offset + 9] = powerSaveControl
    if (controlGroups and 0x40 != 0) report[offset + 36] = vibrationReduction
    if (controlGroups and 0x20 != 0) report[offset + 39] =
        ((report[offset + 39].toInt() and 1.inv()) or lowPassFilter).toByte()
}
