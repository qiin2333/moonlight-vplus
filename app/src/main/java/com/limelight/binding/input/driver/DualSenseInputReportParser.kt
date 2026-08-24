package com.limelight.binding.input.driver

import com.limelight.nvstream.input.ControllerPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class DualSenseVector3(
    val x: Float,
    val y: Float,
    val z: Float
)

internal data class DualSenseTouchContact(
    val active: Boolean,
    val trackingId: Int,
    val x: Float,
    val y: Float
)

internal enum class DualSenseBatteryStatus {
    DISCHARGING,
    CHARGING,
    FULL,
    NOT_CHARGING,
    UNKNOWN
}

internal data class DualSenseBattery(
    val status: DualSenseBatteryStatus,
    val percentage: Int?
)

/**
 * Logical state carried by the common DualSense input payload.
 *
 * Transport framing (USB report ID, Bluetooth sequence and CRC) is intentionally excluded. Both
 * transports can validate their envelope and feed the same payload parser.
 */
internal data class DualSenseInputState(
    val leftStickX: Int,
    val leftStickY: Int,
    val rightStickX: Int,
    val rightStickY: Int,
    val leftTrigger: Int,
    val rightTrigger: Int,
    val buttonFlags: Int,
    val gyro: DualSenseVector3,
    val acceleration: DualSenseVector3,
    val touches: List<DualSenseTouchContact>,
    val battery: DualSenseBattery,
    val sequence: Int? = null
)

/** Parses the transport-independent payload shared by wired and Bluetooth DualSense reports. */
internal object DualSenseInputReportParser {
    private const val USB_REPORT_SIZE = 64
    private const val USB_REPORT_ID = 0x01
    private const val COMMON_PAYLOAD_SIZE = 53

    private const val GYRO_SCALE = 2000.0f / 32768.0f
    private const val ACCEL_SCALE = 4.0f / 32768.0f
    private const val GRAVITY_METERS_PER_SECOND_SQUARED = 9.81f

    private const val TOUCHPAD_WIDTH = 1920f
    private const val TOUCHPAD_HEIGHT = 1080f

    fun parseUsbReport(report: ByteBuffer): DualSenseInputState? {
        val view = report.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (view.remaining() < USB_REPORT_SIZE || view.u8(0) != USB_REPORT_ID) {
            return null
        }
        view.position(1)
        return parseCommonPayload(view.slice().order(ByteOrder.LITTLE_ENDIAN))
    }

    /**
     * Parses a payload beginning with left-stick X. Bluetooth framing can call this after checking
     * its report ID, sequence tag, packet length, and CRC.
     */
    fun parseCommonPayload(payload: ByteBuffer, sequence: Int? = null): DualSenseInputState? {
        val view = payload.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (view.remaining() < COMMON_PAYLOAD_SIZE) {
            return null
        }

        val buttonByte0 = view.u8(7)
        val buttonByte1 = view.u8(8)
        val buttonByte2 = view.u8(9)

        var buttons = 0
        val dpad = buttonByte0 and 0x0F
        buttons = buttons.withFlag(ControllerPacket.UP_FLAG, dpad == 0 || dpad == 1 || dpad == 7)
        buttons = buttons.withFlag(ControllerPacket.DOWN_FLAG, dpad == 3 || dpad == 4 || dpad == 5)
        buttons = buttons.withFlag(ControllerPacket.LEFT_FLAG, dpad == 5 || dpad == 6 || dpad == 7)
        buttons = buttons.withFlag(ControllerPacket.RIGHT_FLAG, dpad == 1 || dpad == 2 || dpad == 3)
        buttons = buttons.withFlag(ControllerPacket.A_FLAG, buttonByte0 and 0x20 != 0)
        buttons = buttons.withFlag(ControllerPacket.B_FLAG, buttonByte0 and 0x40 != 0)
        buttons = buttons.withFlag(ControllerPacket.X_FLAG, buttonByte0 and 0x10 != 0)
        buttons = buttons.withFlag(ControllerPacket.Y_FLAG, buttonByte0 and 0x80 != 0)
        buttons = buttons.withFlag(ControllerPacket.LB_FLAG, buttonByte1 and 0x01 != 0)
        buttons = buttons.withFlag(ControllerPacket.RB_FLAG, buttonByte1 and 0x02 != 0)
        buttons = buttons.withFlag(ControllerPacket.BACK_FLAG, buttonByte1 and 0x10 != 0)
        buttons = buttons.withFlag(ControllerPacket.PLAY_FLAG, buttonByte1 and 0x20 != 0)
        buttons = buttons.withFlag(ControllerPacket.LS_CLK_FLAG, buttonByte1 and 0x40 != 0)
        buttons = buttons.withFlag(ControllerPacket.RS_CLK_FLAG, buttonByte1 and 0x80 != 0)
        buttons = buttons.withFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, buttonByte2 and 0x01 != 0)
        buttons = buttons.withFlag(ControllerPacket.TOUCHPAD_FLAG, buttonByte2 and 0x02 != 0)
        buttons = buttons.withFlag(ControllerPacket.MISC_FLAG, buttonByte2 and 0x04 != 0)

        val gyro = DualSenseVector3(
            view.getShort(15) * GYRO_SCALE,
            view.getShort(17) * GYRO_SCALE,
            view.getShort(19) * GYRO_SCALE
        )
        val acceleration = DualSenseVector3(
            view.getShort(21) * ACCEL_SCALE * GRAVITY_METERS_PER_SECOND_SQUARED,
            view.getShort(23) * ACCEL_SCALE * GRAVITY_METERS_PER_SECOND_SQUARED,
            view.getShort(25) * ACCEL_SCALE * GRAVITY_METERS_PER_SECOND_SQUARED
        )

        return DualSenseInputState(
            leftStickX = view.u8(0),
            leftStickY = view.u8(1),
            rightStickX = view.u8(2),
            rightStickY = view.u8(3),
            leftTrigger = view.u8(4),
            rightTrigger = view.u8(5),
            buttonFlags = buttons,
            gyro = gyro,
            acceleration = acceleration,
            touches = listOf(
                view.touchAt(counterOffset = 32, dataOffset = 33),
                view.touchAt(counterOffset = 36, dataOffset = 37)
            ),
            battery = parseBattery(view.u8(52)),
            sequence = sequence
        )
    }

    private fun parseBattery(value: Int): DualSenseBattery {
        val status = (value ushr 4) and 0x0F
        val percentage = ((value and 0x0F) * 10 + 5).coerceAtMost(100)
        return when (status) {
            0 -> DualSenseBattery(DualSenseBatteryStatus.DISCHARGING, percentage)
            1 -> DualSenseBattery(DualSenseBatteryStatus.CHARGING, percentage)
            2 -> DualSenseBattery(DualSenseBatteryStatus.FULL, 100)
            // Match hid-playstation: charging error states report an unknown/empty capacity.
            0x0A, 0x0B -> DualSenseBattery(DualSenseBatteryStatus.NOT_CHARGING, 0)
            else -> DualSenseBattery(DualSenseBatteryStatus.UNKNOWN, null)
        }
    }

    private fun ByteBuffer.touchAt(counterOffset: Int, dataOffset: Int): DualSenseTouchContact {
        val counter = u8(counterOffset)
        val active = counter and 0x80 == 0
        val data0 = u8(dataOffset)
        val data1 = u8(dataOffset + 1)
        val data2 = u8(dataOffset + 2)
        return DualSenseTouchContact(
            active = active,
            trackingId = counter and 0x7F,
            x = (data0 or ((data1 and 0x0F) shl 8)) / TOUCHPAD_WIDTH,
            y = ((data1 ushr 4) or (data2 shl 4)) / TOUCHPAD_HEIGHT
        )
    }

    private fun ByteBuffer.u8(offset: Int): Int = get(offset).toInt() and 0xFF

    private fun Int.withFlag(flag: Int, enabled: Boolean): Int =
        if (enabled) this or flag else this
}
