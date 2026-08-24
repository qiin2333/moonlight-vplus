package com.limelight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log

import com.limelight.binding.input.haptics.DualSenseUsbHapticsSink
import com.limelight.nvstream.jni.MoonBridge

import java.nio.ByteBuffer

class DualSenseUsbController(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    deviceId: Int,
    private val driverListener: UsbDriverListener
) : AbstractPlayStationUsbController(device, connection, deviceId, driverListener) {

    override val supportsAdaptiveTriggers: Boolean = true

    private val inputSession = DualSenseInputSession(
        isControllerReady = ::isControllerReady,
        reportBattery = ::notifyBatteryState,
        reportTouch = ::notifyControllerTouch
    )
    private val nativeHapticsLifecycleLock = Any()
    private var nativeHapticsSink: DualSenseUsbHapticsSink? = null
    private var nativeHapticsSinkAnnounced = false
    private var nativeHapticsClosing = false

    init {
        capabilities = (capabilities.toInt() or
                MoonBridge.LI_CCAP_BATTERY_STATE.toInt() or
                MoonBridge.LI_CCAP_RGB_LED.toInt() or
                MoonBridge.LI_CCAP_TOUCHPAD.toInt() or
                MoonBridge.LI_CCAP_PREFER_DS5.toInt()).toShort()
    }

    override fun handleRead(buffer: ByteBuffer): Boolean {
        val state = DualSenseInputReportParser.parseUsbReport(buffer) ?: run {
            Log.d(TAG, "Ignoring malformed DualSense USB input report")
            return false
        }
        val normalized = inputSession.accept(state)

        buttonFlags = normalized.buttonFlags
        leftStickX = normalized.leftStickX
        leftStickY = normalized.leftStickY
        rightStickX = normalized.rightStickX
        rightStickY = normalized.rightStickY
        leftTrigger = normalized.leftTrigger
        rightTrigger = normalized.rightTrigger

        gyroX = normalized.gyro.x
        gyroY = normalized.gyro.y
        gyroZ = normalized.gyro.z
        accelX = normalized.acceleration.x
        accelY = normalized.acceleration.y
        accelZ = normalized.acceleration.z

        return true
    }

    override fun resetTouchState() {
        inputSession.resetTouchState()
    }

    override fun onUsbInterfacesReady() {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO ||
                iface.interfaceSubclass != USB_AUDIO_STREAMING_SUBCLASS
            ) {
                continue
            }
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                ) {
                    Log.i(
                        TAG,
                        "UAC streaming OUT iface=${iface.id} " +
                            "ep=0x${Integer.toHexString(endpoint.address)}"
                    )
                    nativeHapticsSink = DualSenseUsbHapticsSink(connection, iface, endpoint)
                    return
                }
            }
        }
    }

    override fun onInputReportPublished() {
        synchronized(nativeHapticsLifecycleLock) {
            if (nativeHapticsClosing || nativeHapticsSinkAnnounced) return
            val sink = nativeHapticsSink ?: return
            driverListener.onDualSenseNativeHapticsSinkAvailable(deviceId, sink)
            nativeHapticsSinkAnnounced = true
        }
    }

    override fun onBeforeUsbTransportClose() {
        synchronized(nativeHapticsLifecycleLock) {
            nativeHapticsClosing = true
            nativeHapticsSink = null
            if (nativeHapticsSinkAnnounced) {
                nativeHapticsSinkAnnounced = false
                driverListener.onDualSenseNativeHapticsSinkGone(deviceId)
            }
        }
    }

    override fun clearControllerSpecificOutput() {
        setAdaptiveTriggers(
            DualSenseUsbOutputReport.BOTH_TRIGGER_FLAGS.toByte(),
            DualSenseUsbOutputReport.EFFECT_TYPE_OFF,
            DualSenseUsbOutputReport.EFFECT_TYPE_OFF,
            ByteArray(DualSenseUsbOutputReport.EFFECT_PAYLOAD_SIZE),
            ByteArray(DualSenseUsbOutputReport.EFFECT_PAYLOAD_SIZE)
        )
    }

    override fun stop() {
        // Release held touchpad contacts so the host doesn't keep a stuck finger.
        inputSession.releaseTouches()
        super.stop()
    }

    override fun doInit(): Boolean {
        Log.d(TAG, "doInit")
        sendCommand(getDualSenseInit())
        return true
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        sendCommand(DualSenseUsbOutputReport.rumble(lowFreqMotor, highFreqMotor))
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        // DS5 supports trigger rumble but implementation is complex
    }

    override fun setAdaptiveTriggers(
        eventFlags: Byte,
        typeLeft: Byte,
        typeRight: Byte,
        left: ByteArray,
        right: ByteArray
    ) {
        if (left.size != DualSenseUsbOutputReport.EFFECT_PAYLOAD_SIZE ||
            right.size != DualSenseUsbOutputReport.EFFECT_PAYLOAD_SIZE
        ) {
            Log.w(TAG, "Ignoring malformed adaptive trigger payload")
            return
        }

        sendCommand(
            DualSenseUsbOutputReport.adaptiveTriggers(
                eventFlags,
                typeLeft,
                typeRight,
                left,
                right
            )
        )
    }

    override fun setControllerLED(r: Byte, g: Byte, b: Byte) {
        sendCommand(DualSenseUsbOutputReport.controllerLED(r, g, b))
    }

    override fun sendCommand(data: ByteArray) {
        if (outEndpt == null) {
            Log.w(TAG, "Cannot send command: invalid parameters")
            return
        }
        synchronized(outputLock) {
            // Re-check under the lock: stop() sets this after sending its final
            // clear, so no stale report may follow it.
            if (outputClosed) {
                return
            }
            Log.d(TAG, "sendCommand")
            val res = connection.bulkTransfer(outEndpt, data, data.size, 1000)
            if (res != data.size) {
                Log.w(TAG, "Command transfer failed: expected ${data.size}, got $res")
            }
        }
    }

    private fun getDualSenseInit(): ByteArray {
        return byteArrayOf(
            0x02, // Report ID
            (0x10 or 0x20 or 0x40 or 0x80).toByte(), // valid_flag0
            0xf7.toByte(), // valid_flag1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, // mute_button_led
            0x10, // power_save_control
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // R2 trigger effect
            0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // L2 trigger effect
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x02, 0x00,
            0x00, // player leds
            0x78, 0x78, 0xEF.toByte() // RGB values
        )
    }

    companion object {
        private const val TAG = "DualSenseUsbController"
        private const val USB_AUDIO_STREAMING_SUBCLASS = 0x02
        private val SUPPORTED_VENDORS = intArrayOf(0x054C, 0x1532)
        private val SUPPORTED_PRODUCTS = intArrayOf(0x0CE6, 0x0DF2, 0x100b, 0x100c)

        @JvmStatic
        fun canClaimDevice(device: UsbDevice?): Boolean {
            if (device == null) return false
            for (vid in SUPPORTED_VENDORS) {
                for (pid in SUPPORTED_PRODUCTS) {
                    if (device.vendorId == vid && device.productId == pid && device.interfaceCount >= 1) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
