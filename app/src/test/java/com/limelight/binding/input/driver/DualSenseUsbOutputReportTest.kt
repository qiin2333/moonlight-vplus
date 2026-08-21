package com.limelight.binding.input.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DualSenseUsbOutputReportTest {
    @Test
    fun adaptiveTriggersMapsProtocolOrderToUsbReport() {
        val left = ByteArray(DualSenseUsbOutputReport.EFFECT_PAYLOAD_SIZE) { (0x20 + it).toByte() }
        val right = ByteArray(DualSenseUsbOutputReport.EFFECT_PAYLOAD_SIZE) { (0x40 + it).toByte() }

        val report = DualSenseUsbOutputReport.adaptiveTriggers(
            DualSenseUsbOutputReport.BOTH_TRIGGER_FLAGS.toByte(),
            0x11,
            0x22,
            left,
            right
        )

        assertEquals(48, report.size)
        assertEquals(0x02, report[0].toInt() and 0xFF)
        assertEquals(0x0C, report[1].toInt() and 0xFF)
        assertEquals(0x22, report[11].toInt() and 0xFF)
        assertArrayEquals(right, report.copyOfRange(12, 22))
        assertEquals(0x11, report[22].toInt() and 0xFF)
        assertArrayEquals(left, report.copyOfRange(23, 33))
    }

    @Test
    fun clearAdaptiveTriggersSendsOffOpcodeWithZeroedPayloads() {
        val report = DualSenseUsbOutputReport.clearAdaptiveTriggers()

        assertEquals(0x0C, report[1].toInt() and 0xFF)
        assertEquals(0, report[3].toInt())
        assertEquals(0, report[4].toInt())
        // 0x05 is the DualSense "effect off" opcode; 0x00 is not a recognized mode.
        assertEquals(0x05, report[11].toInt() and 0xFF)
        assertEquals(0x05, report[22].toInt() and 0xFF)
        assertArrayEquals(ByteArray(10), report.copyOfRange(12, 22))
        assertArrayEquals(ByteArray(10), report.copyOfRange(23, 33))
    }

    @Test
    fun rumbleDoesNotMarkAdaptiveTriggerFieldsValid() {
        val report = DualSenseUsbOutputReport.rumble(0x5500, 0x3300)

        assertEquals(0x03, report[1].toInt() and 0xFF)
        assertEquals(0x33, report[3].toInt() and 0xFF)
        assertEquals(0x55, report[4].toInt() and 0xFF)
    }

    @Test
    fun controllerLEDMarksOnlyLightbarValid() {
        val report = DualSenseUsbOutputReport.controllerLED(0x11, 0x22, 0x33)

        assertEquals(0x02, report[0].toInt() and 0xFF)
        // valid_flag0: no motor or trigger effect bits must be set.
        assertEquals(0, report[1].toInt())
        // valid_flag1: lightbar color only.
        assertEquals(0x04, report[2].toInt() and 0xFF)
        assertEquals(0x11, report[45].toInt() and 0xFF)
        assertEquals(0x22, report[46].toInt() and 0xFF)
        assertEquals(0x33, report[47].toInt() and 0xFF)
    }
}
