package com.limelight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.view.InputDevice

import com.limelight.LimeLog

class Xbox360WirelessDongle(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractController(deviceId, listener, device.vendorId, device.productId) {

    private fun sendLedCommandToEndpoint(endpoint: UsbEndpoint, controllerIndex: Int) {
        val commandBuffer = byteArrayOf(
            0x00, 0x00, 0x08,
            (0x40 + (2 + (controllerIndex % 4))).toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val res = connection.bulkTransfer(endpoint, commandBuffer, commandBuffer.size, 3000)
        if (res != commandBuffer.size) {
            LimeLog.warning("LED set transfer failed: $res")
        }
    }

    private fun sendLedCommandToInterface(iface: UsbInterface, controllerIndex: Int) {
        if (!connection.claimInterface(iface, true)) {
            LimeLog.warning("Failed to claim interface: ${iface.id}")
            return
        }

        for (i in 0 until iface.endpointCount) {
            val endpt = iface.getEndpoint(i)
            if (endpt.direction == UsbConstants.USB_DIR_OUT) {
                sendLedCommandToEndpoint(endpt, controllerIndex)
                break
            }
        }

        connection.releaseInterface(iface)
    }

    override fun start(): Boolean {
        var controllerIndex = 0

        for (id in InputDevice.getDeviceIds()) {
            val inputDev = InputDevice.getDevice(id) ?: continue

            if (inputDev.vendorId == device.vendorId &&
                (inputDev.productId == device.productId || inputDev.productId == 0x02a1) &&
                inputDev.controllerNumber > 0
            ) {
                controllerIndex = inputDev.controllerNumber - 1
                break
            }
        }

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)

            if (iface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC ||
                iface.interfaceSubclass != XB360W_IFACE_SUBCLASS ||
                iface.interfaceProtocol != XB360W_IFACE_PROTOCOL
            ) {
                continue
            }

            sendLedCommandToInterface(iface, controllerIndex++)
        }

        // "Fail" to give control back to the kernel driver
        return false
    }

    override fun stop() {
        // Nothing to do
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        // Unreachable.
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        // Unreachable.
    }

    companion object {
        private const val XB360W_IFACE_SUBCLASS = 93
        private const val XB360W_IFACE_PROTOCOL = 129 // Wireless only

        private val SUPPORTED_VENDORS = intArrayOf(0x045e) // Microsoft

        @JvmStatic
        fun canClaimDevice(device: UsbDevice): Boolean {
            for (supportedVid in SUPPORTED_VENDORS) {
                if (device.vendorId == supportedVid &&
                    device.interfaceCount >= 1 &&
                    device.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).interfaceSubclass == XB360W_IFACE_SUBCLASS &&
                    device.getInterface(0).interfaceProtocol == XB360W_IFACE_PROTOCOL
                ) {
                    return true
                }
            }
            return false
        }
    }
}
