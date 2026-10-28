package com.limelight.binding.input.driver.wireless.dualsense

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class DualSenseBluetoothHapticsReportTest {
    @Test fun nativeCarrierPreservesStereoPayloadAndSignsEntireReport() {
        val pcm = ByteArray(64) { (it - 32).toByte() }
        val report = DualSenseBluetoothHapticsReport.encode(pcm, 15, 257)
        assertEquals(142, report.size)
        assertArrayEquals(byteArrayOf(0x32, 0xF0.toByte(), 0x91.toByte(), 4, 0x66, 32, 32, 1,
            0x92.toByte(), 64), report.copyOfRange(0, 10))
        assertArrayEquals(pcm, report.copyOfRange(10, 74))
        val crc = CRC32().apply { update(0xA2); update(report, 0, 138) }.value
        val stored = (0..3).fold(0L) { n, i -> n or ((report[138 + i].toLong() and 255) shl (i * 8)) }
        assertEquals(crc, stored)
    }

    @Test fun transportAcceptsOnlyKnownReportAndLengthPairs() {
        assertTrue(DualSenseBluetoothHapticsReport.accepts(DualSenseBluetoothHapticsReport.encode(ByteArray(64), 0, 0)))
        assertTrue(DualSenseBluetoothHapticsReport.accepts(DualSenseBluetoothOutputEncoder.neutral(0)))
        assertFalse(DualSenseBluetoothHapticsReport.accepts(ByteArray(142)))
        assertFalse(DualSenseBluetoothHapticsReport.accepts(ByteArray(78).apply { this[0] = 0x32 }))
        assertFalse(DualSenseBluetoothHapticsReport.accepts(byteArrayOf()))
    }

}
