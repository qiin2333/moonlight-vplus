package com.limelight.binding.input.driver.wireless.dualsense

import java.util.zip.CRC32

/**
 * Native haptics-only Bluetooth carrier. Protocol references: awalol/DS5Dongle src/audio.cpp
 * and hbashton/DS4Windows docs/dualsense-bluetooth-audio-haptics.md. Experimental until hardware
 * validated on the target ROM. Speaker Opus and microphone routing are deliberately independent.
 */
internal object DualSenseBluetoothHapticsReport {
    const val REPORT_ID = 0x32
    const val REPORT_SIZE = 142

    fun encode(samples: ByteArray, sequence: Int, packetCounter: Int): ByteArray {
        require(samples.size == 64)
        require(sequence in 0..15)
        return ByteArray(REPORT_SIZE).apply {
            this[0] = REPORT_ID.toByte()
            this[1] = (sequence shl 4).toByte()
            this[2] = 0x91.toByte() // sized audio configuration subpacket
            // Haptics-only config: two buffer controls and the packet counter.
            // Leave speaker/headphone volume and microphone routing unclaimed.
            this[3] = 4
            this[4] = 0x66
            fill(32, 5, 7) // controller buffer profile, not sample count
            this[7] = packetCounter.toByte()
            this[8] = 0x92.toByte() // sized native haptics subpacket
            this[9] = 64
            samples.copyInto(this, 10)
            val bytes = this
            val crc = CRC32().apply { update(0xA2); update(bytes, 0, REPORT_SIZE - 4) }
            for (i in 0..3) this[REPORT_SIZE - 4 + i] = (crc.value ushr (i * 8)).toByte()
        }
    }

    fun accepts(report: ByteArray): Boolean = when (report.firstOrNull()?.toInt()?.and(255)) {
        DualSenseBluetoothOutputEncoder.REPORT_ID -> report.size == DualSenseBluetoothOutputEncoder.REPORT_SIZE
        REPORT_ID -> report.size == REPORT_SIZE
        else -> false
    }
}
