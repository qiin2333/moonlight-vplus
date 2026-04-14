package com.limelight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock

import com.limelight.LimeLog
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class SwitchProController(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractController(deviceId, listener, device.vendorId, device.productId) {

    private var inEndpt: UsbEndpoint? = null
    private var outEndpt: UsbEndpoint? = null
    private var inputThread: Thread? = null
    private var stopped = false
    private var sendPacketCount: Byte = 0

    // IMU data fields
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f

    // Stick calibration data: [stick][axis][min, center, max]
    private val stickCalibration = Array(2) { Array(2) { IntArray(3) } }
    // Pre-calculated scale for each axis: [stick][axis][negative, positive]
    private val stickExtends = Array(2) { Array(2) { FloatArray(2) } }
    private val ifaces = mutableListOf<UsbInterface>()

    init {
        type = MoonBridge.LI_CTYPE_NINTENDO
        capabilities = (MoonBridge.LI_CCAP_GYRO.toInt() or MoonBridge.LI_CCAP_ACCEL.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt()).toShort()
        buttonFlags = ControllerPacket.A_FLAG or ControllerPacket.B_FLAG or ControllerPacket.X_FLAG or ControllerPacket.Y_FLAG or
                ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG or
                ControllerPacket.LB_FLAG or ControllerPacket.RB_FLAG or
                ControllerPacket.LS_CLK_FLAG or ControllerPacket.RS_CLK_FLAG or
                ControllerPacket.BACK_FLAG or ControllerPacket.PLAY_FLAG or ControllerPacket.SPECIAL_BUTTON_FLAG or ControllerPacket.MISC_FLAG
        supportedButtonFlags = buttonFlags
    }

    private fun isJoyCon(): Boolean {
        val pid = device.productId
        return pid == JOYCON_LEFT_PID || pid == JOYCON_RIGHT_PID || pid == JOYCON_PAIR_PID
    }

    private fun isSingleJoyCon(): Boolean {
        val pid = device.productId
        return pid == JOYCON_LEFT_PID || pid == JOYCON_RIGHT_PID
    }

    private fun isJoyConLeft(): Boolean = device.productId == JOYCON_LEFT_PID

    private fun isJoyConRight(): Boolean = device.productId == JOYCON_RIGHT_PID

    private fun sendData(data: ByteArray, size: Int): Boolean {
        if (outEndpt == null) return false
        return connection.bulkTransfer(outEndpt, data, size, 100) == size
    }

    private fun sendCommand(id: Byte, waitReply: Boolean): Boolean {
        val data = byteArrayOf(0x80.toByte(), id)
        for (i in 0 until COMMAND_RETRIES) {
            if (!sendData(data, data.size)) continue
            if (!waitReply) return true

            val buffer = ByteArray(PACKET_SIZE)
            var retries = 0
            do {
                if (inEndpt == null) return false
                val res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 100)
                if (res > 0 && (buffer[0].toInt() and 0xFF) == 0x81 && (buffer[1].toInt() and 0xFF) == id.toInt() and 0xFF) {
                    return true
                }
                retries++
            } while (retries < 20 && !Thread.currentThread().isInterrupted && !stopped)
        }
        return false
    }

    private fun sendSubcommand(subcommand: Byte, payload: ByteArray, buffer: ByteArray): Boolean {
        if (buffer.size < 15) {
            LimeLog.warning("SwitchPro: Response buffer too small: ${buffer.size}")
            return false
        }
        val data = ByteArray(11 + payload.size)
        data[0] = 0x01
        data[1] = sendPacketCount++
        if (sendPacketCount > 0xF) sendPacketCount = 0
        data[10] = subcommand
        System.arraycopy(payload, 0, data, 11, payload.size)

        for (i in 0 until COMMAND_RETRIES) {
            if (!sendData(data, data.size)) continue

            var retries = 0
            do {
                if (inEndpt == null) return false
                val res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 100)
                if (res < 0 || res < 15 || buffer[0] != 0x21.toByte() || buffer[14] != subcommand) {
                    retries++
                } else {
                    return true
                }
            } while (retries < 20 && !Thread.currentThread().isInterrupted && !stopped)
            val bufferInfo = if (buffer.isNotEmpty()) String.format(null as Locale?, "0x%02x", buffer[0].toInt() and 0xFF) else "N/A"
            LimeLog.warning("SwitchPro: Failed to get subcmd reply: $bufferInfo")
        }
        return false
    }

    private fun handshake(): Boolean = sendCommand(0x02, true)
    private fun highSpeed(): Boolean = sendCommand(0x03, true)
    private fun forceUSB(): Boolean = sendCommand(0x04, true)

    private fun setInputReportMode(mode: Byte): Boolean {
        return sendSubcommand(0x03, byteArrayOf(mode), ByteArray(PACKET_SIZE))
    }

    private fun setPlayerLED(id: Int): Boolean {
        return sendSubcommand(0x30, byteArrayOf((id and 0b1111).toByte()), ByteArray(PACKET_SIZE))
    }

    private fun enableIMU(enable: Boolean): Boolean {
        return sendSubcommand(0x40, byteArrayOf(if (enable) 0x01 else 0x00), ByteArray(PACKET_SIZE))
    }

    private fun enableVibration(enable: Boolean): Boolean {
        return sendSubcommand(0x48, byteArrayOf(if (enable) 0x01 else 0x00), ByteArray(PACKET_SIZE))
    }

    private fun spiFlashRead(offset: Int, length: Int, buffer: ByteArray): Boolean {
        if (buffer.size < length + 20) return false
        val address = byteArrayOf(
            (offset and 0xFF).toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            ((offset shr 16) and 0xFF).toByte(),
            ((offset shr 24) and 0xFF).toByte(),
            length.toByte()
        )
        if (!sendSubcommand(0x10, address, buffer)) {
            LimeLog.warning("SwitchPro: Failed to receive SPI Flash data.")
            return false
        }
        return true
    }

    private fun checkUserCalMagic(offset: Int): Boolean {
        val buffer = ByteArray(PACKET_SIZE)
        if (!spiFlashRead(offset, 2, buffer)) return false
        return (buffer[20].toInt() and 0xFF) == 0xB2 && (buffer[21].toInt() and 0xFF) == 0xA1
    }

    private fun loadStickCalibration(): Boolean {
        val buffer = ByteArray(PACKET_SIZE)
        val isJoyCon = isJoyCon()
        val isLeft = isJoyConLeft()

        var lsAddr = FACTORY_LS_CALIBRATION_OFFSET
        var rsAddr = FACTORY_RS_CALIBRATION_OFFSET

        if (checkUserCalMagic(USER_LS_MAGIC_OFFSET)) {
            lsAddr = USER_LS_CALIBRATION_OFFSET
            LimeLog.info("SwitchPro: LS has user calibration!")
        }
        if (checkUserCalMagic(USER_RS_MAGIC_OFFSET)) {
            rsAddr = USER_RS_CALIBRATION_OFFSET
            LimeLog.info("SwitchPro: RS has user calibration!")
        }

        val needLeftStick = !isJoyCon || isLeft || device.productId == JOYCON_PAIR_PID
        val needRightStick = !isJoyCon || !isLeft || device.productId == JOYCON_PAIR_PID

        var lsCalibrated = false
        if (needLeftStick && spiFlashRead(lsAddr, STICK_CALIBRATION_LENGTH, buffer)) {
            val xMax = (buffer[20].toInt() and 0xFF) or ((buffer[21].toInt() and 0x0F) shl 8)
            val yMax = ((buffer[21].toInt() and 0xF0) ushr 4) or ((buffer[22].toInt() and 0xFF) shl 4)
            val xCenter = (buffer[23].toInt() and 0xFF) or ((buffer[24].toInt() and 0x0F) shl 8)
            val yCenter = ((buffer[24].toInt() and 0xF0) ushr 4) or ((buffer[25].toInt() and 0xFF) shl 4)
            val xMin = (buffer[26].toInt() and 0xFF) or ((buffer[27].toInt() and 0x0F) shl 8)
            val yMin = ((buffer[27].toInt() and 0xF0) ushr 4) or ((buffer[28].toInt() and 0xFF) shl 4)
            stickCalibration[0][0][0] = xCenter - xMin
            stickCalibration[0][0][1] = xCenter
            stickCalibration[0][0][2] = xCenter + xMax
            stickCalibration[0][1][0] = 0x1000 - yCenter - yMax
            stickCalibration[0][1][1] = 0x1000 - yCenter
            stickCalibration[0][1][2] = 0x1000 - yCenter + yMin
            stickExtends[0][0][0] = ((xCenter - stickCalibration[0][0][0]) * -0.7).toFloat()
            stickExtends[0][0][1] = ((stickCalibration[0][0][2] - xCenter) * 0.7).toFloat()
            stickExtends[0][1][0] = ((yCenter - stickCalibration[0][1][0]) * -0.7).toFloat()
            stickExtends[0][1][1] = ((stickCalibration[0][1][2] - yCenter) * 0.7).toFloat()
            lsCalibrated = true
        }

        if (!lsCalibrated && needLeftStick) applyDefaultCalibration(0)
        else if (!needLeftStick) applyDefaultCalibration(0)

        var rsCalibrated = false
        if (needRightStick && spiFlashRead(rsAddr, STICK_CALIBRATION_LENGTH, buffer)) {
            val xCenter = (buffer[20].toInt() and 0xFF) or ((buffer[21].toInt() and 0x0F) shl 8)
            val yCenter = ((buffer[21].toInt() and 0xF0) ushr 4) or ((buffer[22].toInt() and 0xFF) shl 4)
            val xMin = (buffer[23].toInt() and 0xFF) or ((buffer[24].toInt() and 0x0F) shl 8)
            val yMin = ((buffer[24].toInt() and 0xF0) ushr 4) or ((buffer[25].toInt() and 0xFF) shl 4)
            val xMax = (buffer[26].toInt() and 0xFF) or ((buffer[27].toInt() and 0x0F) shl 8)
            val yMax = ((buffer[27].toInt() and 0xF0) ushr 4) or ((buffer[28].toInt() and 0xFF) shl 4)
            stickCalibration[1][0][0] = xCenter - xMin
            stickCalibration[1][0][1] = xCenter
            stickCalibration[1][0][2] = xCenter + xMax
            stickCalibration[1][1][0] = 0x1000 - yCenter - yMax
            stickCalibration[1][1][1] = 0x1000 - yCenter
            stickCalibration[1][1][2] = 0x1000 - yCenter + yMin
            stickExtends[1][0][0] = ((xCenter - stickCalibration[1][0][0]) * -0.7).toFloat()
            stickExtends[1][0][1] = ((stickCalibration[1][0][2] - xCenter) * 0.7).toFloat()
            stickExtends[1][1][0] = ((yCenter - stickCalibration[1][1][0]) * -0.7).toFloat()
            stickExtends[1][1][1] = ((stickCalibration[1][1][2] - yCenter) * 0.7).toFloat()
            rsCalibrated = true
        }

        if (!rsCalibrated && needRightStick) applyDefaultCalibration(1)
        else if (!needRightStick) applyDefaultCalibration(1)

        return true
    }

    private fun applyDefaultCalibration(stick: Int) {
        for (axis in 0..1) {
            stickCalibration[stick][axis][0] = 0x000
            stickCalibration[stick][axis][1] = 0x800
            stickCalibration[stick][axis][2] = 0xFFF
            stickExtends[stick][axis][0] = -0x700f.toFloat()
            stickExtends[stick][axis][1] = 0x700f.toFloat()
        }
    }

    private fun applyStickCalibration(value: Int, stick: Int, axis: Int): Float {
        val center = stickCalibration[stick][axis][1]
        var v = value
        if (v < 0) v += 0x1000
        v -= center

        if (v < stickExtends[stick][axis][0]) {
            stickExtends[stick][axis][0] = v.toFloat()
            return -1f
        } else if (v > stickExtends[stick][axis][1]) {
            stickExtends[stick][axis][1] = v.toFloat()
            return 1f
        }

        if (v > 0) {
            val divisor = stickExtends[stick][axis][1]
            if (Math.abs(divisor) < 0.0001f) return 0f
            return v / divisor
        } else if (v < 0) {
            val divisor = stickExtends[stick][axis][0]
            if (Math.abs(divisor) < 0.0001f) return 0f
            return -v / divisor
        }
        return 0f
    }

    private fun createInputThread(): Thread {
        return Thread {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return@Thread
            }

            val handshakeSuccess = handshake()
            if (!handshakeSuccess) {
                LimeLog.warning("SwitchPro: Initial handshake failed!")
                this@SwitchProController.stop()
                return@Thread
            }

            val isJoyCon = isJoyCon()
            val deviceType = if (isJoyCon) "Joy-Con" else "Switch Pro"

            LimeLog.info("$deviceType: handshake $handshakeSuccess")
            LimeLog.info("$deviceType: highspeed ${highSpeed()}")
            LimeLog.info("$deviceType: handshake ${handshake()}")
            LimeLog.info("$deviceType: loadstickcalibration ${loadStickCalibration()}")
            LimeLog.info("$deviceType: setinputreportmode ${setInputReportMode(0x30)}")
            LimeLog.info("$deviceType: forceusb ${forceUSB()}")

            if (!isJoyCon) {
                LimeLog.info("$deviceType: enablevibration ${enableVibration(true)}")
                LimeLog.info("$deviceType: setplayerled ${setPlayerLED(getControllerId() + 1)}")
            } else {
                val vibrationEnabled = enableVibration(true)
                LimeLog.info("$deviceType: enablevibration $vibrationEnabled (may not be supported)")
                val ledSet = setPlayerLED(getControllerId() + 1)
                LimeLog.info("$deviceType: setplayerled $ledSet (may not be supported)")
            }

            LimeLog.info("$deviceType: enableimu ${enableIMU(true)}")
            LimeLog.info("$deviceType: initialized!")

            notifyDeviceAdded()

            while (!Thread.currentThread().isInterrupted && !stopped) {
                val buffer = ByteArray(64)
                var res = -1
                do {
                    if (stopped || Thread.currentThread().isInterrupted) break
                    val lastMillis = SystemClock.uptimeMillis()
                    if (inEndpt == null) break
                    res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 1000)
                    if (res == 0) res = -1
                    if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                        LimeLog.warning("SwitchPro: Detected device I/O error")
                        this@SwitchProController.stop()
                        break
                    }
                } while (res == -1 && !Thread.currentThread().isInterrupted && !stopped)

                if (res == -1 || stopped) break

                if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                    reportInput()
                    reportMotion()
                }
            }
        }
    }

    override fun start(): Boolean {
        ifaces.clear()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (!connection.claimInterface(iface, true)) {
                LimeLog.warning("SwitchPro: Failed to claim interface: $i")
                return false
            } else {
                ifaces.add(iface)
            }
        }

        val iface = device.getInterface(0)
        for (i in 0 until iface.endpointCount) {
            val endpt = iface.getEndpoint(i)
            if (endpt.direction == UsbConstants.USB_DIR_IN && inEndpt == null) {
                inEndpt = endpt
            } else if (endpt.direction == UsbConstants.USB_DIR_OUT && outEndpt == null) {
                outEndpt = endpt
            }
        }

        if (inEndpt == null || outEndpt == null) {
            LimeLog.warning("SwitchPro: Missing required endpoint")
            return false
        }

        inputThread = createInputThread()
        inputThread!!.start()
        return true
    }

    override fun stop() {
        synchronized(this) {
            if (stopped) return
            stopped = true
        }

        try {
            rumble(0, 0)
        } catch (e: Exception) {
            LimeLog.warning("SwitchPro: Failed to cancel rumble during stop")
        }

        inputThread?.let {
            it.interrupt()
            try {
                it.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            inputThread = null
        }

        if (ifaces.isNotEmpty()) {
            synchronized(ifaces) {
                for (iface in ifaces) {
                    try {
                        connection.releaseInterface(iface)
                    } catch (e: Exception) {
                        LimeLog.warning("SwitchPro: Failed to release interface")
                    }
                }
                ifaces.clear()
            }
        }

        try {
            connection.close()
        } catch (e: Exception) {
            LimeLog.warning("SwitchPro: Failed to close connection")
        }

        notifyDeviceRemoved()
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        if (outEndpt == null) return

        val data = ByteArray(10)
        data[0] = 0x10
        data[1] = sendPacketCount++
        if (sendPacketCount > 0xF) sendPacketCount = 0

        if (lowFreqMotor.toInt() != 0) {
            val shifted = (lowFreqMotor.toInt() and 0xFFFF) ushr 12
            data[4] = (0x50 - shifted).toByte()
            data[8] = data[4]
            data[5] = ((((lowFreqMotor.toInt() and 0xFFFF) ushr 8) / 5) + 0x40).toByte()
            data[9] = data[5]
        }
        if (highFreqMotor.toInt() != 0) {
            data[6] = ((0x70 - ((highFreqMotor.toInt() and 0xFFFF) ushr 10)) and -0x04).toByte()
            data[7] = (((highFreqMotor.toInt() and 0xFFFF) ushr 8) * 0xC8 / 0xFF).toByte()
        }

        data[2] = (data[2].toInt() or 0x00).toByte()
        data[3] = (data[3].toInt() or 0x01).toByte()
        data[5] = (data[5].toInt() or 0x40).toByte()
        data[6] = (data[6].toInt() or 0x00).toByte()
        data[7] = (data[7].toInt() or 0x01).toByte()
        data[9] = (data[9].toInt() or 0x40).toByte()

        sendData(data, data.size)
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        // Switch Pro does not support trigger-specific rumble
    }

    private fun reportMotion() {
        notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ)
        val accelXMs2 = accelX * 9.81f
        val accelYMs2 = accelY * 9.81f
        val accelZMs2 = accelZ * 9.81f
        notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_ACCEL, accelXMs2, accelYMs2, accelZMs2)
    }

    private fun handleRead(buf: ByteBuffer): Boolean {
        if (buf.remaining() < PACKET_SIZE) return false

        val reportId = buf.get(0).toInt() and 0xFF
        if (reportId != 0x30) return false

        buf.get()

        val capacity = buf.capacity()
        if (capacity < 6) {
            LimeLog.warning("SwitchPro: Buffer too small for button data")
            return false
        }

        buttonFlags = 0

        val b3 = buf.get(3).toInt()
        val b4 = buf.get(4).toInt()
        val b5 = buf.get(5).toInt()

        setButtonFlag(ControllerPacket.B_FLAG, b3 and 0x08)
        setButtonFlag(ControllerPacket.A_FLAG, b3 and 0x04)
        setButtonFlag(ControllerPacket.Y_FLAG, b3 and 0x02)
        setButtonFlag(ControllerPacket.X_FLAG, b3 and 0x01)
        setButtonFlag(ControllerPacket.UP_FLAG, b5 and 0x02)
        setButtonFlag(ControllerPacket.DOWN_FLAG, b5 and 0x01)
        setButtonFlag(ControllerPacket.LEFT_FLAG, b5 and 0x08)
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b5 and 0x04)
        setButtonFlag(ControllerPacket.BACK_FLAG, b4 and 0x01)
        setButtonFlag(ControllerPacket.PLAY_FLAG, b4 and 0x02)
        setButtonFlag(ControllerPacket.MISC_FLAG, b4 and 0x20)
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b4 and 0x10)
        setButtonFlag(ControllerPacket.LB_FLAG, b5 and 0x40)
        setButtonFlag(ControllerPacket.RB_FLAG, b3 and 0x40)
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b4 and 0x08)
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b4 and 0x04)

        // Triggers
        leftTrigger = if ((b5 and 0x80) != 0) 1.0f else 0.0f
        rightTrigger = if ((b3 and 0x80) != 0) 1.0f else 0.0f

        if (buf.capacity() < 12) {
            LimeLog.warning("SwitchPro: Buffer too small for stick data")
            return false
        }

        val isJoyCon = isJoyCon()
        val isLeft = isJoyConLeft()
        val needLeftStick = !isJoyCon || isLeft || device.productId == JOYCON_PAIR_PID
        val needRightStick = !isJoyCon || !isLeft || device.productId == JOYCON_PAIR_PID

        val _leftStickX = (buf.get(6).toInt() and 0xFF) or ((buf.get(7).toInt() and 0x0F) shl 8)
        val _leftStickY = ((buf.get(7).toInt() and 0xF0) ushr 4) or ((buf.get(8).toInt() and 0xFF) shl 4)
        val _rightStickX = (buf.get(9).toInt() and 0xFF) or ((buf.get(10).toInt() and 0x0F) shl 8)
        val _rightStickY = ((buf.get(10).toInt() and 0xF0) ushr 4) or ((buf.get(11).toInt() and 0xFF) shl 4)

        if (needLeftStick) {
            leftStickX = applyStickCalibration(_leftStickX, 0, 0)
            leftStickY = applyStickCalibration((-_leftStickY) and 0xFFF, 0, 1)
        } else {
            leftStickX = 0f
            leftStickY = 0f
        }

        if (needRightStick) {
            rightStickX = applyStickCalibration(_rightStickX, 1, 0)
            rightStickY = applyStickCalibration((-_rightStickY) and 0xFFF, 1, 1)
        } else {
            rightStickX = 0f
            rightStickY = 0f
        }

        // IMU data
        if (buf.capacity() >= 48) {
            try {
                accelX = buf.getShort(37) / 4096.0f
                accelY = buf.getShort(39) / 4096.0f
                accelZ = buf.getShort(41) / 4096.0f
                gyroZ = -buf.getShort(43) / 16.0f
                gyroX = -buf.getShort(45) / 16.0f
                gyroY = buf.getShort(47) / 16.0f
            } catch (e: IndexOutOfBoundsException) {
                LimeLog.warning("SwitchPro: IMU data out of bounds")
            }
        }

        return true
    }

    companion object {
        private const val NINTENDO_VID = 0x057e
        private const val PRO_PID = 0x2009
        private const val JOYCON_LEFT_PID = 0x2006
        private const val JOYCON_RIGHT_PID = 0x2007
        private const val JOYCON_PAIR_PID = 0x2008
        private const val PACKET_SIZE = 64
        private const val COMMAND_RETRIES = 10

        private const val FACTORY_LS_CALIBRATION_OFFSET = 0x603D
        private const val FACTORY_RS_CALIBRATION_OFFSET = 0x6046
        private const val USER_LS_MAGIC_OFFSET = 0x8010
        private const val USER_LS_CALIBRATION_OFFSET = 0x8012
        private const val USER_RS_MAGIC_OFFSET = 0x801B
        private const val USER_RS_CALIBRATION_OFFSET = 0x801D
        private const val STICK_CALIBRATION_LENGTH = 9

        @JvmStatic
        fun canClaimDevice(device: UsbDevice?): Boolean {
            if (device == null) return false
            if (device.vendorId != NINTENDO_VID) return false
            val pid = device.productId
            if (pid != PRO_PID && pid != JOYCON_LEFT_PID && pid != JOYCON_RIGHT_PID && pid != JOYCON_PAIR_PID) return false
            if (device.interfaceCount < 1) return false
            val iface = device.getInterface(0)
            return iface.interfaceClass == UsbConstants.USB_CLASS_HID
        }
    }
}
