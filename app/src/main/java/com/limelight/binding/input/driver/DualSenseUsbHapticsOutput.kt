package com.limelight.binding.input.driver

import com.limelight.nvstream.Ds5HapticsState

/** Called under the USB controller's existing output lock. */
internal class DualSenseUsbHapticsOutput {
    var control: Ds5HapticsState? = null
    var nativeActive = false
    private var right = 0.toByte()
    private var left = 0.toByte()

    fun applyTo(report: ByteArray) {
        if (report.size < 48 || report[0] != 2.toByte()) return
        control?.let { it.applyTo(report, 1); return }
        if (report[1].toInt() and 1 != 0) {
            right = report[3]
            left = report[4]
        }
        if (!nativeActive) return
        report[1] = ((report[1].toInt() and 3.inv()) or
            if (right != 0.toByte() || left != 0.toByte()) 3 else 0).toByte()
        report[3] = right
        report[4] = left
    }

    fun reset() { control = null; nativeActive = false; right = 0; left = 0 }
}
