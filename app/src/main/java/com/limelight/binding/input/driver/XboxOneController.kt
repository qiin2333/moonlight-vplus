package com.limelight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

import com.limelight.LimeLog
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge

import java.nio.ByteBuffer
import java.util.Arrays

class XboxOneController(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractXboxController(device, connection, deviceId, listener) {

    private var seqNum: Byte = 0
    private var lowFreqMotor: Short = 0
    private var highFreqMotor: Short = 0
    private var leftTriggerMotor: Short = 0
    private var rightTriggerMotor: Short = 0

    init {
        capabilities = (capabilities.toInt() or MoonBridge.LI_CCAP_TRIGGER_RUMBLE.toInt()).toShort()
    }

    private fun processButtons(buffer: ByteBuffer) {
        var b = buffer.get().toInt()

        setButtonFlag(ControllerPacket.PLAY_FLAG, b and 0x04)
        setButtonFlag(ControllerPacket.BACK_FLAG, b and 0x08)

        setButtonFlag(ControllerPacket.A_FLAG, b and 0x10)
        setButtonFlag(ControllerPacket.B_FLAG, b and 0x20)
        setButtonFlag(ControllerPacket.X_FLAG, b and 0x40)
        setButtonFlag(ControllerPacket.Y_FLAG, b and 0x80)

        b = buffer.get().toInt()
        setButtonFlag(ControllerPacket.LEFT_FLAG, b and 0x04)
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b and 0x08)
        setButtonFlag(ControllerPacket.UP_FLAG, b and 0x01)
        setButtonFlag(ControllerPacket.DOWN_FLAG, b and 0x02)

        setButtonFlag(ControllerPacket.LB_FLAG, b and 0x10)
        setButtonFlag(ControllerPacket.RB_FLAG, b and 0x20)

        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b and 0x40)
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b and 0x80)

        leftTrigger = buffer.short / 1023.0f
        rightTrigger = buffer.short / 1023.0f

        leftStickX = buffer.short / 32767.0f
        leftStickY = buffer.short.toInt().inv() / 32767.0f

        rightStickX = buffer.short / 32767.0f
        rightStickY = buffer.short.toInt().inv() / 32767.0f
    }

    private fun ackModeReport(seqNum: Byte) {
        val payload = byteArrayOf(
            0x01, 0x20, seqNum, 0x09, 0x00, 0x07, 0x20, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        connection.bulkTransfer(outEndpt, payload, payload.size, 3000)
    }

    override fun handleRead(buffer: ByteBuffer): Boolean {
        when (buffer.get()) {
            0x20.toByte() -> {
                if (buffer.remaining() < 17) {
                    LimeLog.severe("XBone button/axis read too small: ${buffer.remaining()}")
                    return false
                }

                buffer.position(buffer.position() + 3)
                processButtons(buffer)
                return true
            }

            0x07.toByte() -> {
                if (buffer.remaining() < 4) {
                    LimeLog.severe("XBone mode read too small: ${buffer.remaining()}")
                    return false
                }

                if (buffer.get() == 0x30.toByte()) {
                    ackModeReport(buffer.get())
                    buffer.position(buffer.position() + 1)
                } else {
                    buffer.position(buffer.position() + 2)
                }
                setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, buffer.get().toInt() and 0x01)
                return true
            }
        }

        return false
    }

    override fun doInit(): Boolean {
        for (pkt in INIT_PKTS) {
            if (pkt.vendorId != 0 && device.vendorId != pkt.vendorId) continue
            if (pkt.productId != 0 && device.productId != pkt.productId) continue

            val data = Arrays.copyOf(pkt.data, pkt.data.size)
            data[2] = seqNum++

            val res = connection.bulkTransfer(outEndpt, data, data.size, 3000)
            if (res != data.size) {
                LimeLog.warning("Initialization transfer failed: $res")
                return false
            }
        }

        return true
    }

    private fun sendRumblePacket() {
        val data = byteArrayOf(
            0x09, 0x00, seqNum++, 0x09, 0x00,
            0x0F,
            (leftTriggerMotor.toInt() shr 9).toByte(),
            (rightTriggerMotor.toInt() shr 9).toByte(),
            (lowFreqMotor.toInt() shr 9).toByte(),
            (highFreqMotor.toInt() shr 9).toByte(),
            0xFF.toByte(), 0x00, 0xFF.toByte()
        )
        val res = connection.bulkTransfer(outEndpt, data, data.size, 100)
        if (res != data.size) {
            LimeLog.warning("Rumble transfer failed: $res")
        }
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        this.lowFreqMotor = lowFreqMotor
        this.highFreqMotor = highFreqMotor
        sendRumblePacket()
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        this.leftTriggerMotor = leftTrigger
        this.rightTriggerMotor = rightTrigger
        sendRumblePacket()
    }

    private class InitPacket(val vendorId: Int, val productId: Int, val data: ByteArray)

    companion object {
        private const val XB1_IFACE_SUBCLASS = 71
        private const val XB1_IFACE_PROTOCOL = 208

        private val SUPPORTED_VENDORS = intArrayOf(
            0x045e, // Microsoft
            0x0738, // Mad Catz
            0x0e6f, // Unknown
            0x0f0d, // Hori
            0x1532, // Razer Wildcat
            0x20d6, // PowerA
            0x24c6, // PowerA
            0x2e24, // Hyperkin
            0x2dc8, // 8BitDo
            0x2f24, // GameSir
            0x3537, // GameSir (G7 Pro / Cyclone 2 / Kaleid Flux)
        )

        private val FW2015_INIT = byteArrayOf(0x05, 0x20, 0x00, 0x01, 0x00)
        private val ONE_S_INIT = byteArrayOf(0x05, 0x20, 0x00, 0x0f, 0x06)
        private val SERIES_S_INIT = byteArrayOf(0x05, 0x20, 0x00, 0x0f, 0x06)
        private val HORI_INIT = byteArrayOf(
            0x01, 0x20, 0x00, 0x09, 0x00, 0x04, 0x20, 0x3a,
            0x00, 0x00, 0x00, 0x80.toByte(), 0x00
        )
        private val PDP_INIT1 = byteArrayOf(0x0a, 0x20, 0x00, 0x03, 0x00, 0x01, 0x14)
        private val PDP_INIT2 = byteArrayOf(0x06, 0x20, 0x00, 0x02, 0x01, 0x00)
        private val RUMBLE_INIT1 = byteArrayOf(
            0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00, 0x00,
            0x1D, 0x1D, 0xFF.toByte(), 0x00, 0x00
        )
        private val RUMBLE_INIT2 = byteArrayOf(
            0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00
        )

        private val INIT_PKTS = arrayOf(
            InitPacket(0x0e6f, 0x0165, HORI_INIT),
            InitPacket(0x0f0d, 0x0067, HORI_INIT),
            InitPacket(0x0000, 0x0000, FW2015_INIT),
            InitPacket(0x045e, 0x02ea, ONE_S_INIT),
            InitPacket(0x045e, 0x0b00, ONE_S_INIT),
            InitPacket(0x045e, 0x0b05, SERIES_S_INIT),
            InitPacket(0x045e, 0x0b12, SERIES_S_INIT),
            InitPacket(0x045e, 0x0b13, SERIES_S_INIT),
            InitPacket(0x0e6f, 0x0000, PDP_INIT1),
            InitPacket(0x0e6f, 0x0000, PDP_INIT2),
            InitPacket(0x24c6, 0x541a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x542a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x543a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x541a, RUMBLE_INIT2),
            InitPacket(0x24c6, 0x542a, RUMBLE_INIT2),
            InitPacket(0x24c6, 0x543a, RUMBLE_INIT2),
        )

        @JvmStatic
        fun canClaimDevice(device: UsbDevice): Boolean {
            for (supportedVid in SUPPORTED_VENDORS) {
                if (device.vendorId == supportedVid &&
                    device.interfaceCount >= 1 &&
                    device.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).interfaceSubclass == XB1_IFACE_SUBCLASS &&
                    device.getInterface(0).interfaceProtocol == XB1_IFACE_PROTOCOL
                ) {
                    return true
                }
            }
            return false
        }
    }
}
