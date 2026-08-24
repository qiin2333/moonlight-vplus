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

        assertEquals(0x10, state.leftStickX)
        assertEquals(0x20, state.leftStickY)
        assertEquals(0x30, state.rightStickX)
        assertEquals(0x40, state.rightStickY)
        assertEquals(0x50, state.leftTrigger)
        assertEquals(0x60, state.rightTrigger)

        val expectedButtons = ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG or
            ControllerPacket.A_FLAG or ControllerPacket.LB_FLAG or ControllerPacket.BACK_FLAG or
            ControllerPacket.PLAY_FLAG or ControllerPacket.SPECIAL_BUTTON_FLAG or
            ControllerPacket.TOUCHPAD_FLAG or ControllerPacket.MISC_FLAG
        assertEquals(expectedButtons, state.buttonFlags)

        assertEquals(1000 * (2000f / 32768f), state.gyro.x, 0.0001f)
        assertEquals(-2000 * (2000f / 32768f), state.gyro.y, 0.0001f)
        assertEquals(3000 * (2000f / 32768f), state.gyro.z, 0.0001f)
        assertEquals(4096 * (4f / 32768f) * 9.81f, state.acceleration.x, 0.0001f)

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
    fun reportsZeroCapacityForChargingErrorStates() {
        val report = populatedUsbReport()
        report[53] = 0xA6.toByte()

        val state = DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(report))!!

        assertEquals(DualSenseBatteryStatus.NOT_CHARGING, state.battery.status)
        assertEquals(0, state.battery.percentage)
    }

    private fun populatedUsbReport(): ByteArray = ByteArray(64).apply {
        this[0] = 0x01
        this[1] = 0x10
        this[2] = 0x20
        this[3] = 0x30
        this[4] = 0x40
        this[5] = 0x50
        this[6] = 0x60

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
