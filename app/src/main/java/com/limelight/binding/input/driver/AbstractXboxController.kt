package com.limelight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock

import com.limelight.LimeLog
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class AbstractXboxController(
    protected val device: UsbDevice,
    protected val connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractController(deviceId, listener, device.vendorId, device.productId) {

    private var inputThread: Thread? = null
    private var stopped = false

    protected var inEndpt: UsbEndpoint? = null
    protected var outEndpt: UsbEndpoint? = null

    init {
        type = MoonBridge.LI_CTYPE_XBOX
        capabilities = (MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt()).toShort()
        supportedButtonFlags =
            ControllerPacket.A_FLAG or ControllerPacket.B_FLAG or ControllerPacket.X_FLAG or ControllerPacket.Y_FLAG or
                ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG or
                ControllerPacket.LB_FLAG or ControllerPacket.RB_FLAG or
                ControllerPacket.LS_CLK_FLAG or ControllerPacket.RS_CLK_FLAG or
                ControllerPacket.BACK_FLAG or ControllerPacket.PLAY_FLAG or ControllerPacket.SPECIAL_BUTTON_FLAG
    }

    private fun createInputThread(): Thread {
        return Thread {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return@Thread
            }

            notifyDeviceAdded()

            while (!Thread.currentThread().isInterrupted && !stopped) {
                val buffer = ByteArray(64)
                var res: Int

                do {
                    val lastMillis = SystemClock.uptimeMillis()
                    res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 3000)

                    if (res == 0) {
                        res = -1
                    }

                    if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                        LimeLog.warning("Detected device I/O error")
                        this@AbstractXboxController.stop()
                        break
                    }
                } while (res == -1 && !Thread.currentThread().isInterrupted && !stopped)

                if (res == -1 || stopped) {
                    break
                }

                if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                    reportInput()
                }
            }
        }
    }

    override fun start(): Boolean {
        // Force claim all interfaces
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (!connection.claimInterface(iface, true)) {
                LimeLog.warning("Failed to claim interfaces")
                return false
            }
        }

        // Find the endpoints
        val iface = device.getInterface(0)
        for (i in 0 until iface.endpointCount) {
            val endpt = iface.getEndpoint(i)
            if (endpt.direction == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    LimeLog.warning("Found duplicate IN endpoint")
                    return false
                }
                inEndpt = endpt
            } else if (endpt.direction == UsbConstants.USB_DIR_OUT) {
                if (outEndpt != null) {
                    LimeLog.warning("Found duplicate OUT endpoint")
                    return false
                }
                outEndpt = endpt
            }
        }

        // Make sure the required endpoints were present
        if (inEndpt == null || outEndpt == null) {
            LimeLog.warning("Missing required endpoint")
            return false
        }

        // Run the init function
        if (!doInit()) {
            return false
        }

        // Start listening for controller input
        inputThread = createInputThread()
        inputThread!!.start()

        return true
    }

    override fun stop() {
        if (stopped) return

        stopped = true

        // Cancel any rumble effects
        rumble(0, 0)

        // Stop the input thread
        inputThread?.interrupt()
        inputThread = null

        // Close the USB connection
        connection.close()

        // Report the device removed
        notifyDeviceRemoved()
    }

    protected abstract fun handleRead(buffer: ByteBuffer): Boolean
    protected abstract fun doInit(): Boolean
}
