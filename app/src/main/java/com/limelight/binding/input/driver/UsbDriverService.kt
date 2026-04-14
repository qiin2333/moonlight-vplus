package com.limelight.binding.input.driver

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.InputDevice
import android.widget.Toast

import com.limelight.LimeLog
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration

class UsbDriverService : Service(), UsbDriverListener {

    private var usbManager: UsbManager? = null
    private var prefConfig: PreferenceConfiguration? = null
    private var started = false

    private val receiver = UsbEventReceiver()
    private val binder = UsbDriverBinder()

    private val controllers = ArrayList<AbstractController>()

    private var listener: UsbDriverListener? = null
    private var stateListener: UsbDriverStateListener? = null
    private var nextDeviceId = 0

    override fun reportControllerState(
        controllerId: Int, buttonFlags: Int,
        leftStickX: Float, leftStickY: Float,
        rightStickX: Float, rightStickY: Float,
        leftTrigger: Float, rightTrigger: Float
    ) {
        listener?.reportControllerState(
            controllerId, buttonFlags,
            leftStickX, leftStickY, rightStickX, rightStickY,
            leftTrigger, rightTrigger
        )
    }

    override fun reportControllerMotion(controllerId: Int, motionType: Byte, x: Float, y: Float, z: Float) {
        listener?.reportControllerMotion(controllerId, motionType, x, y, z)
    }

    override fun deviceRemoved(controller: AbstractController) {
        controllers.remove(controller)
        listener?.deviceRemoved(controller)
    }

    override fun deviceAdded(controller: AbstractController) {
        listener?.deviceAdded(controller)
    }

    inner class UsbEventReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action

            if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                @Suppress("DEPRECATION")
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                Handler(Looper.getMainLooper()).postDelayed({
                    device?.let { handleUsbDeviceState(it) }
                }, 1000)
            } else if (action == ACTION_USB_PERMISSION) {
                @Suppress("DEPRECATION")
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                stateListener?.onUsbPermissionPromptCompleted()

                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let { handleUsbDeviceState(it) }
                }
            }
        }
    }

    inner class UsbDriverBinder : Binder() {
        fun setListener(listener: UsbDriverListener?) {
            this@UsbDriverService.listener = listener

            if (listener != null) {
                for (controller in controllers) {
                    listener.deviceAdded(controller)
                }
            }
        }

        fun setStateListener(stateListener: UsbDriverStateListener?) {
            this@UsbDriverService.stateListener = stateListener
        }

        fun start() {
            this@UsbDriverService.start()
        }

        fun stop() {
            this@UsbDriverService.stop()
        }
    }

    private fun handleUsbDeviceState(device: UsbDevice) {
        val mgr = usbManager ?: return
        val config = prefConfig ?: return

        if (shouldClaimDevice(device, config.bindAllUsb)) {
            if (!mgr.hasPermission(device)) {
                try {
                    stateListener?.onUsbPermissionPromptStarting()

                    var intentFlags = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        intentFlags = intentFlags or PendingIntent.FLAG_MUTABLE
                    }

                    val i = Intent(ACTION_USB_PERMISSION)
                    i.setPackage(packageName)

                    mgr.requestPermission(device, PendingIntent.getBroadcast(this, 0, i, intentFlags))
                } catch (e: SecurityException) {
                    Toast.makeText(this, this.getText(R.string.error_usb_prohibited), Toast.LENGTH_LONG).show()
                    stateListener?.onUsbPermissionPromptCompleted()
                }
                return
            }

            val connection = mgr.openDevice(device)
            if (connection == null) {
                LimeLog.warning("Unable to open USB device: " + device.deviceName)
                return
            }

            val controller: AbstractController = when {
                XboxOneController.canClaimDevice(device) ->
                    XboxOneController(device, connection, nextDeviceId++, this)
                Xbox360Controller.canClaimDevice(device) ->
                    Xbox360Controller(device, connection, nextDeviceId++, this)
                Xbox360WirelessDongle.canClaimDevice(device) ->
                    Xbox360WirelessDongle(device, connection, nextDeviceId++, this)
                SwitchProController.canClaimDevice(device) ->
                    SwitchProController(device, connection, nextDeviceId++, this)
                DualSenseController.canClaimDevice(device) ->
                    DualSenseController(device, connection, nextDeviceId++, this)
                Dualshock4Controller.canClaimDevice(device) ->
                    Dualshock4Controller(device, connection, nextDeviceId++, this)
                else -> return
            }

            if (!controller.start()) {
                connection.close()
                return
            }

            controllers.add(controller)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun start() {
        if (started || usbManager == null) {
            return
        }

        started = true

        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        val mgr = usbManager!!
        val config = prefConfig!!
        for (dev in mgr.deviceList.values) {
            if (shouldClaimDevice(dev, config.bindAllUsb)) {
                handleUsbDeviceState(dev)
            }
        }
    }

    private fun stop() {
        if (!started) {
            return
        }

        started = false

        unregisterReceiver(receiver)

        while (controllers.size > 0) {
            controllers.removeAt(0).stop()
        }
    }

    override fun onCreate() {
        this.usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        this.prefConfig = PreferenceConfiguration.readPreferences(this)
    }

    override fun onDestroy() {
        stop()

        listener = null
        stateListener = null
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    interface UsbDriverStateListener {
        fun onUsbPermissionPromptStarting()
        fun onUsbPermissionPromptCompleted()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.limelight.USB_PERMISSION"

        @JvmStatic
        fun isRecognizedInputDevice(device: UsbDevice): Boolean {
            for (id in InputDevice.getDeviceIds()) {
                val inputDev = InputDevice.getDevice(id) ?: continue

                if (inputDev.vendorId == device.vendorId &&
                    inputDev.productId == device.productId
                ) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun kernelSupportsXboxOne(): Boolean {
            val kernelVersion = System.getProperty("os.version")
            LimeLog.info("Kernel Version: $kernelVersion")

            return when {
                kernelVersion == null -> true
                kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.") -> false
                kernelVersion.startsWith("4.4.") || kernelVersion.startsWith("4.9.") -> false
                else -> true
            }
        }

        @JvmStatic
        fun kernelSupportsXbox360W(): Boolean {
            val kernelVersion = System.getProperty("os.version")
            if (kernelVersion != null) {
                if (kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.") ||
                    kernelVersion.startsWith("4.0.") || kernelVersion.startsWith("4.1.")
                ) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun shouldClaimDevice(device: UsbDevice, claimAllAvailable: Boolean): Boolean {
            return ((!kernelSupportsXboxOne() || !isRecognizedInputDevice(device) || claimAllAvailable) && XboxOneController.canClaimDevice(device)) ||
                    ((!isRecognizedInputDevice(device) || claimAllAvailable) && Xbox360Controller.canClaimDevice(device)) ||
                    ((!kernelSupportsXbox360W() || claimAllAvailable) && Xbox360WirelessDongle.canClaimDevice(device)) ||
                    ((!isRecognizedInputDevice(device) || claimAllAvailable) && SwitchProController.canClaimDevice(device)) ||
                    ((!isRecognizedInputDevice(device) || claimAllAvailable) && DualSenseController.canClaimDevice(device)) ||
                    ((!isRecognizedInputDevice(device) || claimAllAvailable) && Dualshock4Controller.canClaimDevice(device))
        }
    }
}
