package com.limelight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import android.util.Log

import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

abstract class AbstractPlayStationUsbController(
    protected val device: UsbDevice,
    protected val connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractController(deviceId, listener, device.vendorId, device.productId) {

    private var inputThread: Thread? = null
    @Volatile
    private var stopped = false
    private val stopLifecycleLock = Any()
    private var stopStarted = false
    private var stopCompleted = false
    private val stopCallbacks = mutableListOf<() -> Unit>()

    // Serializes output reports with the teardown sequence so no queued effect can
    // be sent after the final clear or alongside connection.close().
    protected val outputLock = Any()

    @Volatile
    protected var outputClosed = false

    private val transportClosed = AtomicBoolean(false)

    protected var inEndpt: UsbEndpoint? = null
    protected var outEndpt: UsbEndpoint? = null

    // IMU data fields
    protected var gyroX = 0f
    protected var gyroY = 0f
    protected var gyroZ = 0f
    protected var accelX = 0f
    protected var accelY = 0f
    protected var accelZ = 0f

    init {
        type = MoonBridge.LI_CTYPE_PS
        capabilities = (MoonBridge.LI_CCAP_GYRO.toInt() or MoonBridge.LI_CCAP_ACCEL.toInt() or MoonBridge.LI_CCAP_RUMBLE.toInt()).toShort()
        buttonFlags = ControllerPacket.A_FLAG or ControllerPacket.B_FLAG or ControllerPacket.X_FLAG or ControllerPacket.Y_FLAG or
                ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG or
                ControllerPacket.LB_FLAG or ControllerPacket.RB_FLAG or
                ControllerPacket.LS_CLK_FLAG or ControllerPacket.RS_CLK_FLAG or
                ControllerPacket.BACK_FLAG or ControllerPacket.PLAY_FLAG or ControllerPacket.SPECIAL_BUTTON_FLAG
        supportedButtonFlags = buttonFlags
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
                var res = -1

                do {
                    if (stopped || Thread.currentThread().isInterrupted) {
                        res = -1
                        break
                    }

                    val lastMillis = SystemClock.uptimeMillis()
                    if (inEndpt == null) {
                        Log.w(TAG, "Connection or endpoint is null")
                        res = -1
                        break
                    }
                    res = connection.bulkTransfer(inEndpt, buffer, buffer.size, 3000)

                    if (res == 0) {
                        res = -1
                    }

                    if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                        Log.d(TAG, "Detected device I/O error")
                        this@AbstractPlayStationUsbController.stop()
                        break
                    }
                } while (res == -1 && !Thread.currentThread().isInterrupted && !stopped)

                if (res == -1 || stopped || Thread.currentThread().isInterrupted) {
                    break
                }

                if (res > 0 && handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                    reportInput()
                    onInputReportPublished()
                    reportMotion()
                }
            }
        }
    }

    private val ifaces = mutableListOf<UsbInterface>()

    override fun start(): Boolean {
        outEndpt = null
        inEndpt = null
        ifaces.clear()
        Log.d(TAG, "start")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (!connection.claimInterface(iface, true)) {
                Log.d(TAG, "Failed to claim interface: $i")
                return failStart()
            } else {
                ifaces.add(iface)
            }
        }
        Log.d(TAG, "getInterfaceCount:" + device.interfaceCount)

        val iface = findInterface(device) ?: run {
            Log.e(TAG, "Failed to find interface")
            return failStart()
        }

        for (i in 0 until iface.endpointCount) {
            val endpt = iface.getEndpoint(i)
            if (endpt.direction == UsbConstants.USB_DIR_OUT) {
                if (outEndpt != null) {
                    Log.d(TAG, "Found duplicate OUT endpoint")
                    return failStart()
                }
                outEndpt = endpt
            } else if (endpt.direction == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    Log.d(TAG, "Found duplicate IN endpoint")
                    return failStart()
                }
                inEndpt = endpt
            }
        }
        Log.d(TAG, "inEndpt: $inEndpt")
        Log.d(TAG, "outEndpt: $outEndpt")

        if (inEndpt == null || outEndpt == null) {
            Log.d(TAG, "Missing required endpoint")
            return failStart()
        }

        if (!doInit()) {
            return failStart()
        }

        onUsbInterfacesReady()

        inputThread = createInputThread()
        inputThread!!.start()
        return true
    }

    private fun failStart(): Boolean {
        synchronized(ifaces) {
            for (iface in ifaces.asReversed()) {
                runCatching { connection.releaseInterface(iface) }
                    .onFailure { Log.w(TAG, "Failed to release interface after start failure", it) }
            }
            ifaces.clear()
        }
        inEndpt = null
        outEndpt = null
        return false
    }

    override fun stop() {
        stopAndThen {}
    }

    override fun stopAndThen(onStopped: () -> Unit) {
        var runImmediately = false
        val shouldStart = synchronized(stopLifecycleLock) {
            if (stopCompleted) {
                runImmediately = true
                false
            } else {
                stopCallbacks += onStopped
                if (stopStarted) {
                    false
                } else {
                    stopStarted = true
                    stopped = true
                    true
                }
            }
        }

        if (runImmediately) {
            onStopped()
        } else if (shouldStart) {
            runCatching(::performStop).onFailure {
                Log.w(TAG, "Controller stop failed; closing USB transport", it)
                closeUsbTransport()
            }
        }
    }

    private fun performStop() {
        // Hold the output lock across the final clear so a report already queued by
        // the rumble worker cannot re-engage an effect after this point.
        synchronized(outputLock) {
            try {
                rumble(0, 0)
                clearControllerSpecificOutput()
            } catch (e: Exception) {
                Log.d(TAG, "Failed to clear controller output during stop", e)
            } finally {
                outputClosed = true
            }
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

        closeUsbTransportWhenReady(::closeUsbTransport)
    }

    private fun closeUsbTransport() {
        if (!transportClosed.compareAndSet(false, true)) return

        if (ifaces.isNotEmpty()) {
            synchronized(ifaces) {
                for (iface in ifaces) {
                    try {
                        connection.releaseInterface(iface)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release interface", e)
                    }
                }
                ifaces.clear()
            }
        }

        synchronized(outputLock) {
            try {
                connection.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close connection", e)
            }
        }

        try {
            notifyDeviceRemoved()
        } finally {
            completeStop()
        }
    }

    private fun completeStop() {
        val callbacks = synchronized(stopLifecycleLock) {
            if (stopCompleted) return
            stopCompleted = true
            stopCallbacks.toList().also { stopCallbacks.clear() }
        }
        callbacks.forEach { callback ->
            runCatching(callback).onFailure {
                Log.w(TAG, "Controller stop callback failed", it)
            }
        }
    }

    protected fun reportMotion() {
        notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ)
        notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_ACCEL, accelX, accelY, accelZ)
    }

    protected abstract fun handleRead(buffer: ByteBuffer): Boolean

    /** Called after all USB interfaces are claimed and the controller is initialized. */
    protected open fun onUsbInterfacesReady() = Unit

    /** Called after reportInput() has assigned the controller number and published arrival. */
    protected open fun onInputReportPublished() = Unit

    /** Defers final interface and connection release until transport-specific output has stopped. */
    protected open fun closeUsbTransportWhenReady(closeTransport: () -> Unit) = closeTransport()

    /** Clears output features that aren't represented by the common rumble API. */
    protected open fun clearControllerSpecificOutput() = Unit

    protected abstract fun doInit(): Boolean

    protected abstract fun sendCommand(data: ByteArray)

    companion object {
        private const val TAG = "PlayStationUsbCtrl"

        private fun findInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_HID && intf.endpointCount >= 2) {
                    Log.d(TAG, "Found HID interface: $i")
                    return intf
                }
            }
            return null
        }
    }
}
