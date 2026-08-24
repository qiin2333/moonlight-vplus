package com.limelight.binding.input.driver

import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

/**
 * Differential regression coverage for the wired DualSense path that existed before the
 * transport-neutral input refactor.
 *
 * The oracle below intentionally mirrors the old master implementation instead of reusing the
 * production parser. This makes valid USB reports fail here if the shared USB/Bluetooth parser
 * silently changes the legacy buttons, axes, motion, or battery contract.
 */
class DualSenseUsbLegacyCompatibilityTest {
    @Test
    fun validUsbReportsMatchLegacyMappingAcrossDeterministicCorpus() {
        val random = Random(0x524C0DEL)

        repeat(8_192) { sample ->
            val report = ByteArray(USB_REPORT_SIZE).also(random::nextBytes)
            report[0] = USB_REPORT_ID

            val parsed = DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(report))
            assertNotNull("sample=$sample", parsed)
            parsed!!

            assertEquals("buttons sample=$sample", legacyButtons(report), parsed.buttonFlags)
            assertEquals("leftStickX sample=$sample", report.u8(1), parsed.leftStickX)
            assertEquals("leftStickY sample=$sample", report.u8(2), parsed.leftStickY)
            assertEquals("rightStickX sample=$sample", report.u8(3), parsed.rightStickX)
            assertEquals("rightStickY sample=$sample", report.u8(4), parsed.rightStickY)
            assertEquals("leftTrigger sample=$sample", report.u8(5), parsed.leftTrigger)
            assertEquals("rightTrigger sample=$sample", report.u8(6), parsed.rightTrigger)

            assertEquals("gyroX sample=$sample", report.s16(16) * GYRO_SCALE, parsed.gyro.x, 0f)
            assertEquals("gyroY sample=$sample", report.s16(18) * GYRO_SCALE, parsed.gyro.y, 0f)
            assertEquals("gyroZ sample=$sample", report.s16(20) * GYRO_SCALE, parsed.gyro.z, 0f)
            assertEquals(
                "accelX sample=$sample",
                report.s16(22) * ACCEL_SCALE * GRAVITY,
                parsed.acceleration.x,
                0f
            )
            assertEquals(
                "accelY sample=$sample",
                report.s16(24) * ACCEL_SCALE * GRAVITY,
                parsed.acceleration.y,
                0f
            )
            assertEquals(
                "accelZ sample=$sample",
                report.s16(26) * ACCEL_SCALE * GRAVITY,
                parsed.acceleration.z,
                0f
            )

            val batteryEvents = mutableListOf<Pair<Byte, Byte>>()
            val normalized = DualSenseInputSession(
                isControllerReady = { false },
                reportBattery = { state, percentage -> batteryEvents += state to percentage },
                reportTouch = { _, _, _, _ -> error("touch must be deferred until ready") }
            ).accept(parsed)

            assertEquals(
                "normalized axes sample=$sample",
                legacyNormalizedAxes(report),
                listOf(
                    normalized.leftStickX,
                    normalized.leftStickY,
                    normalized.rightStickX,
                    normalized.rightStickY,
                    normalized.leftTrigger,
                    normalized.rightTrigger
                )
            )
            assertEquals("battery sample=$sample", legacyBattery(report[53]), batteryEvents.single())
        }
    }

    private fun legacyButtons(report: ByteArray): Int {
        val button0 = report.u8(8)
        val button1 = report.u8(9)
        val button2 = report.u8(10)
        val dpad = button0 and 0x0F
        var buttons = 0

        fun set(flag: Int, pressed: Boolean) {
            if (pressed) buttons = buttons or flag
        }

        set(ControllerPacket.UP_FLAG, dpad == 0 || dpad == 1 || dpad == 7)
        set(ControllerPacket.DOWN_FLAG, dpad == 3 || dpad == 4 || dpad == 5)
        set(ControllerPacket.LEFT_FLAG, dpad == 5 || dpad == 6 || dpad == 7)
        set(ControllerPacket.RIGHT_FLAG, dpad == 1 || dpad == 2 || dpad == 3)
        set(ControllerPacket.A_FLAG, button0 and 0x20 != 0)
        set(ControllerPacket.B_FLAG, button0 and 0x40 != 0)
        set(ControllerPacket.X_FLAG, button0 and 0x10 != 0)
        set(ControllerPacket.Y_FLAG, button0 and 0x80 != 0)
        set(ControllerPacket.LB_FLAG, button1 and 0x01 != 0)
        set(ControllerPacket.RB_FLAG, button1 and 0x02 != 0)
        set(ControllerPacket.BACK_FLAG, button1 and 0x10 != 0)
        set(ControllerPacket.PLAY_FLAG, button1 and 0x20 != 0)
        set(ControllerPacket.LS_CLK_FLAG, button1 and 0x40 != 0)
        set(ControllerPacket.RS_CLK_FLAG, button1 and 0x80 != 0)
        set(ControllerPacket.SPECIAL_BUTTON_FLAG, button2 and 0x01 != 0)
        set(ControllerPacket.TOUCHPAD_FLAG, button2 and 0x02 != 0)
        set(ControllerPacket.MISC_FLAG, button2 and 0x04 != 0)
        return buttons
    }

    private fun legacyNormalizedAxes(report: ByteArray): List<Float> = listOf(
        normalizeStick(report.u8(1)),
        normalizeStick(report.u8(2)),
        normalizeStick(report.u8(3)),
        normalizeStick(report.u8(4)),
        normalizeTrigger(report.u8(5)),
        normalizeTrigger(report.u8(6))
    )

    private fun legacyBattery(value: Byte): Pair<Byte, Byte> {
        val unsigned = value.toInt() and 0xFF
        val status = unsigned ushr 4
        val percentage = ((unsigned and 0x0F) * 10 + 5).coerceAtMost(100).toByte()
        return when (status) {
            0 -> MoonBridge.LI_BATTERY_STATE_DISCHARGING to percentage
            1 -> MoonBridge.LI_BATTERY_STATE_CHARGING to percentage
            2 -> MoonBridge.LI_BATTERY_STATE_FULL to 100.toByte()
            0x0A, 0x0B -> MoonBridge.LI_BATTERY_STATE_NOT_CHARGING to 0.toByte()
            else -> MoonBridge.LI_BATTERY_STATE_UNKNOWN to MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN
        }
    }

    private fun normalizeStick(value: Int): Float = (2f * value / 255f) - 1f

    private fun normalizeTrigger(value: Int): Float = value / 255f

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.s16(offset: Int): Int =
        (u8(offset) or (u8(offset + 1) shl 8)).toShort().toInt()

    private companion object {
        const val USB_REPORT_SIZE = 64
        const val USB_REPORT_ID: Byte = 0x01
        const val GYRO_SCALE = 2000f / 32768f
        const val ACCEL_SCALE = 4f / 32768f
        const val GRAVITY = 9.81f
    }
}
