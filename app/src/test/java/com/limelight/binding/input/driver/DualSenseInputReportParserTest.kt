package com.limelight.binding.input.driver

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DualSenseInputReportParserTest {
    @Test
    fun usbEnvelopeRejectsMalformedReports() {
        assertNull(DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(ByteArray(63))))

        val wrongReport = ByteArray(64)
        wrongReport[0] = 0x31
        assertNull(DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(wrongReport)))
    }

    @Test
    fun usbAndCommonPayloadProduceTheSameLogicalState() {
        val report = populatedUsbReport()

        val usbState = DualSenseInputReportParser.parseUsbReport(
            ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)
        )
        val payloadState = DualSenseInputReportParser.parseCommonPayload(
            ByteBuffer.wrap(report, 1, report.size - 1).slice().order(ByteOrder.LITTLE_ENDIAN)
        )

        assertEquals(payloadState, usbState)
    }

    @Test
    fun parsesAxesButtonsMotionTouchesAndBattery() {
        val state = DualSenseInputReportParser.parseUsbReport(
            ByteBuffer.wrap(populatedUsbReport()).order(ByteOrder.LITTLE_ENDIAN)
        )!!

        assertEquals(0x80, state.leftStickX)
        assertEquals(0xFF, state.leftStickY)
        assertEquals(0xA1, state.rightStickX)
        assertEquals(0xC0, state.rightStickY)
        assertEquals(0x81, state.leftTrigger)
        assertEquals(0xFE, state.rightTrigger)

        val expectedButtons = ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG or
            ControllerPacket.A_FLAG or ControllerPacket.LB_FLAG or ControllerPacket.BACK_FLAG or
            ControllerPacket.PLAY_FLAG or ControllerPacket.SPECIAL_BUTTON_FLAG or
            ControllerPacket.TOUCHPAD_FLAG or ControllerPacket.MISC_FLAG
        assertEquals(expectedButtons, state.buttonFlags)

        assertEquals(61.035156f, state.gyro.x, 0.0001f)
        assertEquals(-122.07031f, state.gyro.y, 0.0001f)
        assertEquals(183.10547f, state.gyro.z, 0.0001f)
        assertEquals(4.905f, state.acceleration.x, 0.0001f)

        assertEquals(-4.905f, state.acceleration.y, 0.0001f)
        assertEquals(2.4525f, state.acceleration.z, 0.0001f)

        assertTrue(state.touches[0].active)
        assertEquals(1, state.touches[0].trackingId)
        assertEquals(0x456 / 1920f, state.touches[0].x, 0.0001f)
        assertEquals(0x321 / 1080f, state.touches[0].y, 0.0001f)
        assertFalse(state.touches[1].active)
        assertEquals(0, state.touches[1].trackingId)

        assertEquals(DualSenseBatteryStatus.CHARGING, state.battery.status)
        assertEquals(75, state.battery.percentage)
    }

    @Test
    fun decodesEveryDpadDirectionAndTreatsReservedValuesAsNeutral() {
        val expected = listOf(
            ControllerPacket.UP_FLAG,
            ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG,
            ControllerPacket.RIGHT_FLAG,
            ControllerPacket.DOWN_FLAG or ControllerPacket.RIGHT_FLAG,
            ControllerPacket.DOWN_FLAG,
            ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG,
            ControllerPacket.LEFT_FLAG,
            ControllerPacket.UP_FLAG or ControllerPacket.LEFT_FLAG,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        expected.forEachIndexed { direction, flags ->
            val report = ByteArray(64).apply { this[0] = 1; this[8] = direction.toByte() }
            assertEquals("dpad=$direction", flags,
                DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(report))!!.buttonFlags)
        }
    }

    @Test
    fun decodesIndividualButtonsWithoutLeakingUnusedBits() {
        val cases = listOf(
            Triple(8, 0x10, ControllerPacket.X_FLAG), Triple(8, 0x20, ControllerPacket.A_FLAG),
            Triple(8, 0x40, ControllerPacket.B_FLAG), Triple(8, 0x80, ControllerPacket.Y_FLAG),
            Triple(9, 0x01, ControllerPacket.LB_FLAG), Triple(9, 0x02, ControllerPacket.RB_FLAG),
            Triple(9, 0x10, ControllerPacket.BACK_FLAG), Triple(9, 0x20, ControllerPacket.PLAY_FLAG),
            Triple(9, 0x40, ControllerPacket.LS_CLK_FLAG), Triple(9, 0x80, ControllerPacket.RS_CLK_FLAG),
            Triple(10, 0x01, ControllerPacket.SPECIAL_BUTTON_FLAG),
            Triple(10, 0x02, ControllerPacket.TOUCHPAD_FLAG), Triple(10, 0x04, ControllerPacket.MISC_FLAG),
            Triple(9, 0x0C, 0), Triple(10, 0xF8, 0)
        )
        cases.forEach { (offset, bits, expected) ->
            val report = ByteArray(64).apply {
                this[0] = 1
                this[8] = 8 // Neutral D-pad, including when a face button is pressed.
                this[offset] = (this[offset].toInt() or bits).toByte()
            }
            assertEquals("byte=$offset bits=$bits", expected,
                DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(report))!!.buttonFlags)
        }
        val combined = ByteArray(64).apply {
            this[0] = 1; this[8] = 0xF8.toByte(); this[9] = 0xFF.toByte(); this[10] = 0xFF.toByte()
        }
        val allButtons = cases.map { it.third }.fold(0) { flags, button -> flags or button }
        assertEquals(allButtons,
            DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(combined))!!.buttonFlags)
    }

    @Test
    fun decodesSignedLittleEndianMotionAtSensorRangeBoundaries() {
        val report = populatedUsbReport().apply {
            putShort(16, -32768); putShort(18, 32767); putShort(20, 0)
            putShort(22, 32767); putShort(24, 0); putShort(26, -32768)
        }
        val state = DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(report))!!
        assertEquals(-2000f, state.gyro.x, 0.0001f)
        assertEquals(1999.939f, state.gyro.y, 0.0001f)
        assertEquals(0f, state.gyro.z, 0f)
        assertEquals(39.238804f, state.acceleration.x, 0.0001f)
        assertEquals(0f, state.acceleration.y, 0f)
        assertEquals(-39.24f, state.acceleration.z, 0.0001f)
    }

    private fun populatedUsbReport(): ByteArray = ByteArray(64).apply {
        this[0] = 0x01
        this[1] = 0x80.toByte()
        this[2] = 0xFF.toByte()
        this[3] = 0xA1.toByte()
        this[4] = 0xC0.toByte()
        this[5] = 0x81.toByte()
        this[6] = 0xFE.toByte()

        this[8] = 0x21 // D-pad up-right + cross/A
        this[9] = 0x31 // L1 + create/back + options/start
        this[10] = 0x07 // PS + touchpad click + mute/misc

        putShort(16, 1000)
        putShort(18, -2000)
        putShort(20, 3000)
        putShort(22, 4096)
        putShort(24, -4096)
        putShort(26, 2048)

        this[33] = 0x01 // active contact
        this[34] = 0x56
        this[35] = 0x14
        this[36] = 0x32
        this[37] = 0x80.toByte() // inactive second contact

        this[53] = 0x17 // charging, 75%
    }

    private fun ByteArray.putShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }
}
