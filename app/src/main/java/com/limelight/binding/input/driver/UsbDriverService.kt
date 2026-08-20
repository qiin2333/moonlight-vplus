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
    @Volatile private var started = false
    private var receiverRegistered = false
    @Volatile private var claimAllAvailableOverride: Boolean? = null

    private val receiver = UsbEventReceiver()
    private val binder = UsbDriverBinder()

    private val controllers = ArrayList<AbstractController>()
    private val controllersLock = Any()
    private val sessionLock = Any()
    private val sessionOwner = UsbDriverSessionOwner()

    @Volatile private var listener: UsbDriverListener? = null
    @Volatile private var stateListener: UsbDriverStateListener? = null
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
        synchronized(controllersLock) {
            controllers.remove(controller)
        }
        listener?.deviceRemoved(controller)
    }

    override fun deviceAdded(controller: AbstractController) {
        listener?.deviceAdded(controller)
    }

    inner class UsbEventReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                val action = intent.action

                if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    Handler(Looper.getMainLooper()).postDelayed({
                        device?.let { handleUsbDeviceStateSafely(it) }
                    }, 1000)
                } else if (action == ACTION_USB_PERMISSION) {
                    try {
                        @Suppress("DEPRECATION")
                        val device: UsbDevice? =
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { handleUsbDeviceStateSafely(it) }
                        }
                    } finally {
                        notifyPermissionPromptCompleted()
                    }
                }
            }.onFailure {
                LimeLog.warning("Unable to process USB permission result: ${it.message}")
            }
        }
    }

    inner class UsbDriverBinder : Binder() {
        fun setListener(listener: UsbDriverListener?) {
            synchronized(sessionLock) {
                if (sessionOwner.hasActiveSession()) {
                    LimeLog.warning("Ignoring legacy USB listener update while a stream session owns the driver")
                    return
                }
                setListenerLocked(listener)
            }
        }

        fun setStateListener(stateListener: UsbDriverStateListener?) {
            synchronized(sessionLock) {
                if (sessionOwner.hasActiveSession()) {
                    LimeLog.warning("Ignoring legacy USB state-listener update while a stream session owns the driver")
                    return
                }
                this@UsbDriverService.stateListener = stateListener
            }
        }

        fun start() {
            synchronized(sessionLock) {
                if (sessionOwner.hasActiveSession()) {
                    LimeLog.warning("Ignoring legacy USB start while a stream session owns the driver")
                    return
                }
                this@UsbDriverService.start(claimAllAvailableOverride = null)
            }
        }

        fun attachSession(
            listener: UsbDriverListener?,
            stateListener: UsbDriverStateListener
        ): Long {
            return synchronized(sessionLock) {
                val token = sessionOwner.acquire()
                setListenerLocked(listener)
                this@UsbDriverService.stateListener = stateListener
                this@UsbDriverService.start(claimAllAvailableOverride = null)
                token
            }
        }

        fun updateSessionListener(token: Long, listener: UsbDriverListener?) {
            synchronized(sessionLock) {
                if (sessionOwner.owns(token)) {
                    setListenerLocked(listener)
                }
            }
        }

        fun releaseSession(token: Long) {
            synchronized(sessionLock) {
                if (!sessionOwner.release(token)) return
                setListenerLocked(null)
                stateListener = null
                this@UsbDriverService.stop()
            }
        }

        /**
         * Temporarily claims every supported USB controller for the shortcut test screen.
         * Returns true when at least one controller has started and will report readiness through
         * [UsbDriverListener.deviceAdded]. A live stream session keeps exclusive ownership.
         */
        fun startForDiagnostics(): Boolean {
            return synchronized(sessionLock) {
                if (sessionOwner.hasActiveSession()) {
                    return@synchronized false
                }
                this@UsbDriverService.start(claimAllAvailableOverride = true)
                synchronized(controllersLock) { controllers.isNotEmpty() }
            }
        }

        /** Returns whether a claimed controller is still active or initializing. */
        fun hasActiveControllers(): Boolean {
            return synchronized(controllersLock) { controllers.isNotEmpty() }
        }

        fun stop() {
            synchronized(sessionLock) {
                if (sessionOwner.hasActiveSession()) {
                    LimeLog.warning("Ignoring legacy USB stop while a stream session owns the driver")
                    return
                }
                this@UsbDriverService.stop()
            }
        }
    }

    private fun setListenerLocked(listener: UsbDriverListener?) {
        this.listener = listener

        if (listener != null) {
            val controllerSnapshot = synchronized(controllersLock) {
                controllers.toList()
            }
            for (controller in controllerSnapshot) {
                listener.deviceAdded(controller)
            }
        }
    }

    private fun handleUsbDeviceState(device: UsbDevice) {
        val mgr = usbManager ?: return
        val config = prefConfig ?: return

        if (shouldClaimDevice(device, claimAllAvailableOverride ?: config.bindAllUsb)) {
            if (!mgr.hasPermission(device)) {
                try {
                    notifyPermissionPromptStarting()

                    var intentFlags = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        intentFlags = intentFlags or PendingIntent.FLAG_MUTABLE
                    }

                    val i = Intent(ACTION_USB_PERMISSION)
                    i.setPackage(packageName)

                    mgr.requestPermission(device, PendingIntent.getBroadcast(this, 0, i, intentFlags))
                } catch (e: RuntimeException) {
                    LimeLog.warning("Unable to request USB controller permission: ${e.message}")
                    Handler(Looper.getMainLooper()).post {
                        runCatching {
                            Toast.makeText(
                                this,
                                this.getText(R.string.error_usb_prohibited),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    notifyPermissionPromptCompleted()
                }
                return
            }

            val connection = runCatching { mgr.openDevice(device) }
                .onFailure {
                    LimeLog.warning("Unable to open USB controller: ${it.message}")
                }
                .getOrNull()
            if (connection == null) {
                return
            }

            val controller = runCatching {
                when {
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
                    else -> null
                }
            }.onFailure {
                LimeLog.warning("Unable to initialize USB controller: ${it.message}")
            }.getOrNull()

            if (controller == null || !runCatching { controller.start() }.getOrDefault(false)) {
                runCatching { connection.close() }
                return
            }

            val retained = synchronized(controllersLock) {
                if (started) {
                    controllers.add(controller)
                    true
                } else {
                    false
                }
            }
            if (!retained) {
                runCatching { controller.stop() }.onFailure {
                    LimeLog.warning("Unable to stop stale USB controller: ${it.message}")
                }
            }
        }
    }

    private fun handleUsbDeviceStateSafely(device: UsbDevice) {
        synchronized(sessionLock) {
            if (!started) {
                return
            }
            runCatching { handleUsbDeviceState(device) }.onFailure {
                LimeLog.warning("Unable to process USB controller: ${it.message}")
            }
        }
    }

    private fun notifyPermissionPromptStarting() {
        runCatching { stateListener?.onUsbPermissionPromptStarting() }.onFailure {
            LimeLog.warning("Unable to notify USB permission start: ${it.message}")
        }
    }

    private fun notifyPermissionPromptCompleted() {
        runCatching { stateListener?.onUsbPermissionPromptCompleted() }.onFailure {
            LimeLog.warning("Unable to notify USB permission completion: ${it.message}")
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun start(claimAllAvailableOverride: Boolean?) {
        if (usbManager == null) {
            return
        }

        if (started) {
            if (this.claimAllAvailableOverride == claimAllAvailableOverride) {
                return
            }
            stop()
        }

        this.claimAllAvailableOverride = claimAllAvailableOverride
        started = true

        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(ACTION_USB_PERMISSION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (e: RuntimeException) {
            LimeLog.warning("Unable to register USB controller receiver: ${e.message}")
            started = false
            this.claimAllAvailableOverride = null
            return
        }

        val mgr = usbManager!!
        val config = prefConfig!!
        for (dev in mgr.deviceList.values) {
            if (shouldClaimDevice(dev, claimAllAvailableOverride ?: config.bindAllUsb)) {
                handleUsbDeviceStateSafely(dev)
            }
        }
    }

    private fun stop() {
        if (!started) {
            return
        }

        started = false

        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }.onFailure {
                LimeLog.warning("Unable to unregister USB controller receiver: ${it.message}")
            }
            receiverRegistered = false
        }

        val controllersToStop = synchronized(controllersLock) {
            controllers.toList().also { controllers.clear() }
        }
        for (controller in controllersToStop) {
            runCatching { controller.stop() }.onFailure {
                LimeLog.warning("Unable to stop USB controller: ${it.message}")
            }
        }
        claimAllAvailableOverride = null
    }

    override fun onCreate() {
        this.usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        this.prefConfig = PreferenceConfiguration.readPreferences(this)
    }

    override fun onDestroy() {
        synchronized(sessionLock) {
            sessionOwner.reset()
            stop()
            listener = null
            stateListener = null
        }
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

internal class UsbDriverSessionOwner {
    private var nextToken = 0L
    private var activeToken: Long? = null

    @Synchronized
    fun acquire(): Long {
        val token = ++nextToken
        activeToken = token
        return token
    }

    @Synchronized
    fun owns(token: Long): Boolean = activeToken == token

    @Synchronized
    fun hasActiveSession(): Boolean = activeToken != null

    @Synchronized
    fun release(token: Long): Boolean {
        if (activeToken != token) return false
        activeToken = null
        return true
    }

    @Synchronized
    fun reset() {
        activeToken = null
    }
}
