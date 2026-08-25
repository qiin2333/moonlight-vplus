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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.input.haptics.DualSenseNativeHapticsSink
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseWirelessBridgeFailure
import com.limelight.binding.input.driver.wireless.dualsense.HciDualSenseWirelessBridgeHost
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseWirelessBridgeListener
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseWirelessBridgeManager
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseWirelessBridgeState
import com.limelight.binding.input.driver.wireless.hci.AndroidKeystoreHciLinkKeyStore
import com.limelight.binding.input.driver.wireless.hci.EphemeralHciLinkKeyStore
import com.limelight.binding.input.driver.wireless.hci.HciAdapterBootstrap
import com.limelight.binding.input.driver.wireless.hci.HciAdapterCapabilities
import com.limelight.binding.input.driver.wireless.hci.HciDiscoveredDevice
import com.limelight.binding.input.driver.wireless.hci.HciLinkKeyStore
import com.limelight.binding.input.driver.wireless.hci.HciUsbDeviceProbe
import com.limelight.binding.input.driver.wireless.hci.HciUsbTransportFactory
import com.limelight.preferences.PreferenceConfiguration

class UsbDriverService : Service(), UsbDriverListener {

    private data class StartRequest(val claimAllAvailableOverride: Boolean?)

    private var usbManager: UsbManager? = null
    private var prefConfig: PreferenceConfiguration? = null
    @Volatile private var started = false
    private var receiverRegistered = false
    @Volatile private var claimAllAvailableOverride: Boolean? = null
    @Volatile private var wirelessBridge: DualSenseWirelessBridgeManager? = null
    @Volatile private var wirelessBridgeDeviceId: Int? = null
    @Volatile private var wirelessDiscoveryStarted = false
    @Volatile private var wirelessConnectAttempted = false

    private val receiver = UsbEventReceiver()
    private val binder = UsbDriverBinder()

    private val controllers = ArrayList<AbstractController>()
    private val controllersLock = Any()
    private val sessionLock = ReentrantLock()
    private val sessionOwner = UsbDriverSessionOwner()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionHandoff = UsbDriverSessionHandoff<StartRequest>()
    private val stopCallbacks = mutableListOf<() -> Unit>()

    @Volatile private var listener: ControllerDriverListener? = null
    @Volatile private var stateListener: UsbDriverStateListener? = null

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

    override fun reportControllerBattery(
        controllerId: Int,
        batteryState: Byte,
        batteryPercentage: Byte
    ) {
        listener?.reportControllerBattery(controllerId, batteryState, batteryPercentage)
    }

    override fun reportControllerTouch(
        controllerId: Int,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float
    ) {
        listener?.reportControllerTouch(controllerId, eventType, pointerId, x, y)
    }

    override fun isControllerReady(controllerId: Int): Boolean =
        listener?.isControllerReady(controllerId) ?: false

    override fun onDualSenseNativeHapticsSinkAvailable(
        controllerId: Int,
        sink: DualSenseNativeHapticsSink
    ) {
        listener?.onDualSenseNativeHapticsSinkAvailable(controllerId, sink)
    }

    override fun onDualSenseNativeHapticsSinkGone(controllerId: Int) {
        listener?.onDualSenseNativeHapticsSinkGone(controllerId)
    }

    override fun deviceRemoved(controller: AbstractController) {
        val suppressCallback = sessionLock.withLock {
            synchronized(controllersLock) {
                controllers.remove(controller)
            }
            sessionHandoff.isStoppingController(controller.getControllerId())
        }
        if (!suppressCallback) listener?.deviceRemoved(controller)
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
                } else if (action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    sessionLock.withLock {
                        if (device?.deviceId == wirelessBridgeDeviceId) {
                            stopWirelessBridgeLocked(adapterPresent = false)
                        }
                    }
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
            sessionLock.withLock {
                if (sessionOwner.hasActiveSession()) {
                    LimeLog.warning("Ignoring legacy USB listener update while a stream session owns the driver")
                    return
                }
                setListenerLocked(listener)
            }
        }

        fun setStateListener(stateListener: UsbDriverStateListener?) {
            sessionLock.withLock {
                if (sessionOwner.hasActiveSession()) {
                    LimeLog.warning("Ignoring legacy USB state-listener update while a stream session owns the driver")
                    return
                }
                this@UsbDriverService.stateListener = stateListener
            }
        }

        fun start() {
            sessionLock.withLock {
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
            return sessionLock.withLock {
                if (sessionOwner.hasActiveSession()) {
                    LimeLog.warning("Replacing an active USB driver session with a new stream session")
                }
                val token = sessionOwner.acquire()
                setListenerLocked(listener)
                this@UsbDriverService.stateListener = stateListener
                this@UsbDriverService.start(claimAllAvailableOverride = null)
                token
            }
        }

        fun updateSessionListener(token: Long, listener: UsbDriverListener?) {
            sessionLock.withLock {
                if (sessionOwner.owns(token)) {
                    setListenerLocked(listener)
                }
            }
        }

        fun releaseSession(token: Long, onReleased: () -> Unit = {}) {
            sessionLock.withLock {
                if (!sessionOwner.release(token)) {
                    mainHandler.post { onReleased() }
                    return
                }
                setListenerLocked(null)
                stateListener = null
                sessionHandoff.cancelPendingStart()
                this@UsbDriverService.stop(onReleased)
            }
        }

        /**
         * Temporarily claims every supported USB controller for the shortcut test screen.
         * Returns true when at least one controller has started and will report readiness through
         * [UsbDriverListener.deviceAdded]. A live stream session keeps exclusive ownership.
         */
        fun startForDiagnostics(): Boolean {
            return sessionLock.withLock {
                if (sessionOwner.hasActiveSession()) {
                    return@withLock false
                }
                this@UsbDriverService.start(claimAllAvailableOverride = true)
                synchronized(controllersLock) { controllers.isNotEmpty() } ||
                    (sessionHandoff.isStopping && sessionHandoff.pendingStartMatches {
                        it.claimAllAvailableOverride == true
                    })
            }
        }

        /** Returns whether a claimed controller is still active or initializing. */
        fun hasActiveControllers(): Boolean {
            return sessionLock.withLock {
                synchronized(controllersLock) { controllers.isNotEmpty() } ||
                    wirelessBridge?.state == DualSenseWirelessBridgeState.ACTIVE ||
                    (sessionHandoff.isStopping && sessionHandoff.pendingStartMatches {
                        it.claimAllAvailableOverride == true
                    })
            }
        }

        fun dualSenseWirelessBridgeState(): String =
            wirelessBridge?.state?.name ?: DualSenseWirelessBridgeState.DETACHED.name

        fun retryDualSenseWirelessDiscovery(): Boolean {
            return sessionLock.withLock {
                val bridge = wirelessBridge ?: return@withLock false
                if (bridge.state != DualSenseWirelessBridgeState.READY) return@withLock false
                wirelessDiscoveryStarted = true
                wirelessConnectAttempted = false
                bridge.startDiscovery()
            }
        }

        fun stop(onStopped: () -> Unit = {}) {
            sessionLock.withLock {
                if (sessionOwner.hasActiveSession()) {
                    LimeLog.warning("Ignoring legacy USB stop while a stream session owns the driver")
                    mainHandler.post { onStopped() }
                    return
                }
                sessionHandoff.cancelPendingStart()
                this@UsbDriverService.stop(onStopped)
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

        if (config.dualSenseWirelessBridge && handleWirelessBridgeAdapter(mgr, device)) {
            return
        }

        // The service is also bound when only the wireless bridge is enabled. Do not claim
        // ordinary gamepads unless the existing USB driver preference is independently enabled.
        if (!config.usbDriver) return

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
                        XboxOneController(device, connection, ControllerDriverIdAllocator.allocate(), this)
                    Xbox360Controller.canClaimDevice(device) ->
                        Xbox360Controller(device, connection, ControllerDriverIdAllocator.allocate(), this)
                    Xbox360WirelessDongle.canClaimDevice(device) ->
                        Xbox360WirelessDongle(device, connection, ControllerDriverIdAllocator.allocate(), this)
                    SwitchProController.canClaimDevice(device) ->
                        SwitchProController(device, connection, ControllerDriverIdAllocator.allocate(), this)
                    DualSenseUsbController.canClaimDevice(device) ->
                        DualSenseUsbController(device, connection, ControllerDriverIdAllocator.allocate(), this)
                    Dualshock4Controller.canClaimDevice(device) ->
                        Dualshock4Controller(device, connection, ControllerDriverIdAllocator.allocate(), this)
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

    private fun handleWirelessBridgeAdapter(mgr: UsbManager, device: UsbDevice): Boolean {
        val descriptor = HciUsbDeviceProbe.probe(device) ?: return false
        val currentDeviceId = wirelessBridgeDeviceId
        if (currentDeviceId != null) {
            if (currentDeviceId != device.deviceId) {
                LimeLog.warning("Ignoring additional USB HCI adapter while DualSense bridge is active")
            }
            return true
        }

        if (!mgr.hasPermission(device)) {
            requestUsbPermission(mgr, device, "DualSense wireless bridge")
            return true
        }

        val connection = runCatching { mgr.openDevice(device) }
            .onFailure {
                LimeLog.warning("Unable to open DualSense bridge adapter: ${it.message}")
            }
            .getOrNull() ?: return true

        val keyStore: HciLinkKeyStore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AndroidKeystoreHciLinkKeyStore(this)
        } else {
            EphemeralHciLinkKeyStore()
        }
        val manager = DualSenseWirelessBridgeManager(
            controllerListener = this,
            linkKeyStore = keyStore,
            hostFactory = { bootstrapListener ->
                HciDualSenseWirelessBridgeHost(
                    HciAdapterBootstrap(
                        HciUsbTransportFactory.create(connection, descriptor),
                        bootstrapListener
                    )
                )
            },
            listener = wirelessBridgeListener
        )
        wirelessBridgeDeviceId = device.deviceId
        wirelessBridge = manager
        wirelessDiscoveryStarted = false
        wirelessConnectAttempted = false
        LimeLog.info(
            "DualSense bridge adapter claimed: " +
                "%04X:%04X profile=%s".format(
                    descriptor.vendorId,
                    descriptor.productId,
                    descriptor.profile
                )
        )
        if (!manager.start()) {
            stopWirelessBridge(adapterPresent = true)
        }
        return true
    }

    private fun requestUsbPermission(mgr: UsbManager, device: UsbDevice, purpose: String) {
        try {
            notifyPermissionPromptStarting()
            var intentFlags = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                intentFlags = intentFlags or PendingIntent.FLAG_MUTABLE
            }
            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
            mgr.requestPermission(
                device,
                PendingIntent.getBroadcast(this, device.deviceId, intent, intentFlags)
            )
        } catch (e: RuntimeException) {
            LimeLog.warning("Unable to request USB permission for $purpose: ${e.message}")
            notifyPermissionPromptCompleted()
        }
    }

    private val wirelessBridgeListener = object : DualSenseWirelessBridgeListener {
        override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
            val version = capabilities.localVersion
            LimeLog.info(
                "DualSense bridge adapter ready: acl_mtu=${capabilities.aclDataPacketLength} " +
                    "acl_slots=${capabilities.aclPacketCredits} " +
                    "page_scan=${capabilities.pageScanEnabled} " +
                    "manufacturer=${version?.manufacturerId ?: "unknown"} " +
                    "hci_revision=${version?.hciRevision ?: "unknown"}"
            )
        }

        override fun onStateChanged(state: DualSenseWirelessBridgeState) {
            LimeLog.info("DualSense wireless bridge state: $state")
            runCatching { stateListener?.onDualSenseWirelessBridgeStateChanged(state.name) }
            if (state == DualSenseWirelessBridgeState.READY) {
                Handler(Looper.getMainLooper()).post(::driveWirelessBridge)
            }
        }

        override fun onDeviceFound(device: HciDiscoveredDevice) {
            LimeLog.info(
                "DualSense wireless candidate: ${device.name ?: "unnamed"} " +
                    "address=**:**:**:${device.address.toString().takeLast(8)}"
            )
        }

        override fun onFailure(failure: DualSenseWirelessBridgeFailure) {
            LimeLog.warning("DualSense wireless bridge ${failure.stage} failure: $failure")
        }
    }

    private fun driveWirelessBridge() {
        sessionLock.withLock {
            if (!started || prefConfig?.dualSenseWirelessBridge != true) return
            val bridge = wirelessBridge ?: return
            if (bridge.state != DualSenseWirelessBridgeState.READY) return
            val candidates = bridge.discoveredDevices()
            if (candidates.isEmpty()) {
                if (!wirelessDiscoveryStarted) {
                    wirelessDiscoveryStarted = true
                    bridge.startDiscovery()
                }
                return
            }
            if (!wirelessConnectAttempted) {
                wirelessConnectAttempted = true
                bridge.connect(candidates.first().address.value)
            }
        }
    }

    private fun stopWirelessBridge(adapterPresent: Boolean) {
        sessionLock.withLock { stopWirelessBridgeLocked(adapterPresent) }
    }

    private fun stopWirelessBridgeLocked(adapterPresent: Boolean) {
        val bridge = wirelessBridge
        wirelessBridge = null
        wirelessBridgeDeviceId = null
        wirelessDiscoveryStarted = false
        wirelessConnectAttempted = false
        runCatching { bridge?.close(adapterPresent) }.onFailure {
            LimeLog.warning("Unable to stop DualSense wireless bridge: ${it.message}")
        }
        runCatching {
            stateListener?.onDualSenseWirelessBridgeStateChanged(
                DualSenseWirelessBridgeState.DETACHED.name
            )
        }
    }

    private fun handleUsbDeviceStateSafely(device: UsbDevice) {
        if (!sessionLock.tryLock()) {
            Handler(Looper.getMainLooper()).postDelayed(
                { handleUsbDeviceStateSafely(device) },
                USB_SESSION_RETRY_DELAY_MS
            )
            return
        }

        try {
            if (!started) {
                return
            }
            runCatching { handleUsbDeviceState(device) }.onFailure {
                LimeLog.warning("Unable to process USB controller: ${it.message}")
            }
        } finally {
            sessionLock.unlock()
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

    private fun notifyDriverStartCompleted() {
        runCatching { stateListener?.onUsbDriverStartCompleted() }.onFailure {
            LimeLog.warning("Unable to notify USB driver start completion: ${it.message}")
        }
    }

    private fun start(claimAllAvailableOverride: Boolean?) {
        if (usbManager == null) {
            notifyDriverStartCompleted()
            return
        }

        val request = StartRequest(claimAllAvailableOverride)
        if (sessionHandoff.queueStart(request)) {
            return
        }

        if (started) {
            if (this.claimAllAvailableOverride == claimAllAvailableOverride) {
                notifyDriverStartCompleted()
                return
            }
            sessionHandoff.setPendingStart(request)
            stop()
            return
        }

        startNow(request)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun startNow(request: StartRequest) {
        this.claimAllAvailableOverride = request.claimAllAvailableOverride
        started = true

        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
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
            notifyDriverStartCompleted()
            return
        }

        val mgr = usbManager!!
        for (dev in mgr.deviceList.values) {
            // Inspect every device here. handleUsbDeviceState() performs the HCI probe before
            // applying the ordinary gamepad claim policy, so an adapter that was already attached
            // when streaming started must not be filtered out by shouldClaimDevice().
            handleUsbDeviceStateSafely(dev)
        }
        notifyDriverStartCompleted()
    }

    private fun stop(onStopped: () -> Unit = {}) {
        if (sessionHandoff.isStopping) {
            stopCallbacks += onStopped
            return
        }

        if (!started) {
            mainHandler.post { onStopped() }
            return
        }

        stopCallbacks += onStopped

        started = false

        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }.onFailure {
                LimeLog.warning("Unable to unregister USB controller receiver: ${it.message}")
            }
            receiverRegistered = false
        }

        stopWirelessBridge(adapterPresent = true)

        val controllersToStop = synchronized(controllersLock) {
            controllers.toList().also { controllers.clear() }
        }
        claimAllAvailableOverride = null

        if (controllersToStop.isEmpty()) {
            finishStopLocked(sessionHandoff.takePendingStart())
            return
        }

        val generation = sessionHandoff.beginStop(
            controllersToStop.map(AbstractController::getControllerId)
        )
        for (controller in controllersToStop) {
            val controllerId = controller.getControllerId()
            runCatching {
                controller.stopAndThen {
                    onControllerStopCompleted(generation, controllerId)
                }
            }.onFailure {
                LimeLog.warning("Unable to stop USB controller: ${it.message}")
                onControllerStopCompleted(generation, controllerId)
            }
        }
    }

    private fun onControllerStopCompleted(generation: Long, controllerId: Int) {
        sessionLock.withLock {
            val completion = sessionHandoff.completeController(generation, controllerId)
            if (completion.finished) finishStopLocked(completion.pendingStart)
        }
    }

    private fun finishStopLocked(restart: StartRequest?) {
        if (restart != null) startNow(restart)

        val callbacks = stopCallbacks.toList()
        stopCallbacks.clear()
        callbacks.forEach { callback ->
            mainHandler.post {
                runCatching(callback).onFailure {
                    LimeLog.warning("USB driver stop callback failed: ${it.message}")
                }
            }
        }
    }

    override fun onCreate() {
        this.usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        this.prefConfig = PreferenceConfiguration.readPreferences(this)
    }

    override fun onDestroy() {
        sessionLock.withLock {
            sessionOwner.reset()
            sessionHandoff.cancelPendingStart()
            listener = null
            stateListener = null
            stop()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    interface UsbDriverStateListener {
        fun onUsbPermissionPromptStarting()
        fun onUsbPermissionPromptCompleted()
        fun onDualSenseWirelessBridgeStateChanged(state: String) = Unit
        fun onUsbDriverStartCompleted() = Unit
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.limelight.USB_PERMISSION"
        private const val USB_SESSION_RETRY_DELAY_MS = 50L

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
                    ((!isRecognizedInputDevice(device) || claimAllAvailable) && DualSenseUsbController.canClaimDevice(device)) ||
                    ((!isRecognizedInputDevice(device) || claimAllAvailable) && Dualshock4Controller.canClaimDevice(device))
        }
    }
}

// Service callers take sessionLock first. Intrinsic synchronization keeps this helper safe in isolation.
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
