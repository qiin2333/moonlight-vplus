package com.limelight.binding.input.driver.wireless.dualsense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class DualSenseBluetoothInputCodecTest {
    @Test
    fun validatesEnvelopeAndParsesCommonPayload() {
        val codec = DualSenseBluetoothInputCodec()

        assertEquals(
            DualSenseBluetoothInputDisposition.INVALID_LENGTH,
            codec.decode(ByteArray(77)).disposition
        )
        val wrongId = report(1).also { it[0] = 0x01 }
        assertEquals(
            DualSenseBluetoothInputDisposition.INVALID_REPORT_ID,
            codec.decode(wrongId).disposition
        )
        val wrongCrc = report(1).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertEquals(
            DualSenseBluetoothInputDisposition.INVALID_CRC,
            codec.decode(wrongCrc).disposition
        )

        val result = codec.decode(report(1))
        assertTrue(result.accepted)
        assertEquals(1, result.sequence)
        assertEquals(0x10, result.state!!.leftStickX)
        assertEquals(9, result.state.touches[0].trackingId)
        assertEquals(1, result.state.sequence)
    }

    @Test
    fun rejectsDuplicatesAndOldPacketsButAcceptsWrapAndReportsGaps() {
        val codec = DualSenseBluetoothInputCodec()

        assertTrue(codec.decode(report(254)).accepted)
        val gap = codec.decode(report(0))
        assertTrue(gap.accepted)
        assertEquals(1, gap.missingReports)

        assertEquals(
            DualSenseBluetoothInputDisposition.DUPLICATE_SEQUENCE,
            codec.decode(report(0)).disposition
        )
        assertEquals(
            DualSenseBluetoothInputDisposition.OUT_OF_ORDER_SEQUENCE,
            codec.decode(report(255)).disposition
        )
        assertFalse(codec.decode(report(255)).accepted)
    }

    @Test
    fun resynchronizesAfterInputSilence() {
        var nowMs = 0L
        val codec = DualSenseBluetoothInputCodec(
            monotonicTimeMs = { nowMs },
            sequenceResyncTimeoutMs = 500L
        )
        assertTrue(codec.decode(report(120)).accepted)

        nowMs = 100L
        assertEquals(
            DualSenseBluetoothInputDisposition.OUT_OF_ORDER_SEQUENCE,
            codec.decode(report(5)).disposition
        )

        nowMs = 500L
        val result = codec.decode(report(5))
        assertTrue(result.accepted)
        assertEquals(DualSenseBluetoothInputDisposition.RESYNCHRONIZED, result.disposition)
    }

    companion object {
        internal fun report(sequence: Int): ByteArray =
            ByteArray(DualSenseBluetoothInputCodec.REPORT_SIZE).apply {
                this[0] = DualSenseBluetoothInputCodec.REPORT_ID.toByte()
                this[1] = 0x00
                this[2] = 0x10
                this[3] = 0x20
                this[4] = 0x30
                this[5] = 0x40
                this[6] = 0x50
                this[7] = 0x60
                this[8] = sequence.toByte()
                this[9] = 0x08
                this[34] = 0x09
                this[35] = 0x56
                this[36] = 0x14
                this[37] = 0x32
                this[38] = 0x80.toByte()
                this[54] = 0x20
                writeInputCrc(this)
            }

        internal fun writeInputCrc(report: ByteArray) {
            val offset = report.size - 4
            val crc = CRC32()
            crc.update(0xA1)
            crc.update(report, 0, offset)
            val value = crc.value
            for (index in 0 until 4) {
                report[offset + index] = (value ushr (index * 8)).toByte()
            }
        }
    }
}
