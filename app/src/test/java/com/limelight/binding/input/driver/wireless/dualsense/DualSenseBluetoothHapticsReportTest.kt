package com.limelight.binding.input.driver.wireless.dualsense

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DualSenseBluetoothHapticsReportTest {
    @Test fun nativeCarrierPreservesStereoPayloadAndSignsEntireReport() {
        val pcm = ByteArray(64) { (it - 32).toByte() }
        val report = DualSenseBluetoothHapticsReport.encode(pcm, 15, 257)
        assertEquals(142, report.size)
        assertEquals(0x32, report[0].toInt() and 255)
        assertEquals(0xF0, report[1].toInt() and 255)
        assertEquals(4, report[3].toInt())
        assertEquals(0x66, report[4].toInt())
        assertEquals(1, report[7].toInt())
        assertEquals(0x92, report[8].toInt() and 255)
        assertEquals(64, report[9].toInt())
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

    @Test fun authoritativeHostZeroRumbleModeSurvivesPcmAndGenericOutputs() {
        val reports = mutableListOf<ByteArray>()
        val writer = DualSenseBluetoothOutputWriter(sendReport = { report ->
            synchronized(reports) { reports.add(report) }; true
        })
        // V2 compatible rumble, with zero motors. Magnitudes alone cannot identify this mode.
        writer.updateControl(com.limelight.nvstream.Ds5HapticsState(
            byteArrayOf(2, 4, 0x62, 0, 0, 0x10, 0x23, 1)))
        writer.updateRumble(0x5500, 0x3300)
        assertTrue(writer.writeHaptics(ByteArray(64)))
        writer.pauseHaptics()
        writer.close()
        val state = synchronized(reports) { reports.filter { it[0] == 0x31.toByte() }.dropLast(1) }
        assertTrue(state.isNotEmpty())
        for (report in state) {
            assertEquals(2, report[3].toInt() and 3)
            assertEquals(4, report[41].toInt() and 4)
            assertEquals(0, report[5].toInt())
            assertEquals(0, report[6].toInt())
            assertEquals(0x10, report[12].toInt())
            assertEquals(0x23, report[39].toInt())
            assertEquals(1, report[42].toInt() and 1)
        }
    }

    @Test fun pcmAndRumbleShareOneWriterAndModeChangesDoNotEraseOtherState() {
        val reports = mutableListOf<ByteArray>()
        val rumbleOn = CountDownLatch(1)
        val rumbleOff = CountDownLatch(1)
        val writer = DualSenseBluetoothOutputWriter(sendReport = { report ->
            synchronized(reports) { reports.add(report) }
            if (report[0] == 0x31.toByte() && report[6] != 0.toByte()) rumbleOn.countDown()
            if (report[0] == 0x31.toByte() && report[6] == 0.toByte() &&
                report[3].toInt() and 3 == 0 && rumbleOn.count == 0L) rumbleOff.countDown()
            true
        })
        assertTrue(writer.writeHaptics(ByteArray(64)))
        assertTrue(writer.updateRumble(0x5500, 0x3300))
        assertTrue(rumbleOn.await(1, TimeUnit.SECONDS))
        assertTrue(writer.writeHaptics(ByteArray(64)))
        val count = synchronized(reports) { reports.size }
        assertTrue(writer.updateRumble(0, 0))
        assertTrue(rumbleOff.await(1, TimeUnit.SECONDS))
        writer.close(sendNeutral = true)
        val captured = synchronized(reports) { reports.toList() }
        assertTrue(captured.drop(count).any { it[0] == 0x31.toByte() && it[3].toInt() and 3 == 0 })
        assertEquals(0x31.toByte(), captured.last()[0])
        assertFalse(writer.writeHaptics(ByteArray(64)))
    }
}
