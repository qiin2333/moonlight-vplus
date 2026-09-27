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
import android.os.SystemClock
import android.view.InputDevice
import android.widget.Toast
import com.limelight.utils.CompletionSignal
import kotlin.concurrent.withLock

import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.input.haptics.*
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
    private val sessionLock = forwardingLock
    private val controllerDevices = mutableMapOf<AbstractController, String>()
    private val sessionOwner = UsbDriverSessionOwner()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionHandoff = UsbDriverSessionHandoff<StartRequest>()
    private val stopCallbacks = mutableListOf<() -> Unit>()
    private var stopResult = UsbDriverStopResult().apply { finish() }

    @Volatile private var listener: ControllerDriverListener? = null
    @Volatile private var stateListener: UsbDriverStateListener? = null

    private val waveformRegistry = HapticBackendRegistry()
    private val waveformRoutes = HapticRouteCatalog(ControllerDriverIdAllocator::allocate)
    private val waveformPermissionRequests = mutableSetOf<Int>()

    /** Rates waveform-channel rebuilds per device so failures cannot loop the USB lifecycle. */
    private class WaveformPacing(val attempts: Int, val blockedUntilMs: Long)
    private val waveformPacing = java.util.concurrent.ConcurrentHashMap<String, WaveformPacing>()
    private val waveformRetryRunnables = java.util.concurrent.ConcurrentHashMap<String, Runnable>()

    override fun onSystemWaveformSinkAvailable(route: HapticRouteSnapshot, sink: WaveformHapticsSink,
                                              onAssociationLost: () -> Unit) {
        listener?.onSystemWaveformSinkAvailable(route, sink, onAssociationLost)
    }

    override fun onSystemWaveformSinkGone(routeId: Int) {
        waveformRoutes.snapshots().firstOrNull { it.id == routeId }?.let { route ->
            val state = when {
                forwardingReservations.contains(route.device.instance) -> HapticAvailability.BUSY
                route.capability.availability == HapticAvailability.FAILED -> HapticAvailability.FAILED
                route.capability.availability == HapticAvailability.NEEDS_ASSOCIATION -> HapticAvailability.NEEDS_ASSOCIATION
                else -> HapticAvailability.DISCONNECTED
            }
            publishWaveform(route, state)
        }
        listener?.onSystemWaveformSinkGone(routeId)
    }

    private fun publishWaveform(route: HapticRouteSnapshot, state: HapticAvailability) {
        waveformRoutes.update(route.id, route.capability.copy(availability = state))?.let {
            listener?.onWaveformRouteChanged(it)
        }
    }

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
        val route = waveformRoutes.snapshots().firstOrNull { it.id == controllerId }
        if (route == null) { sink.stop(); return }
        val candidate = waveformRegistry.discover(route.device)
            .singleOrNull { it.capability.backendId == route.capability.backendId }
        if (candidate == null) { sink.stop(); return }
        val availability = HapticActivationPolicy.evaluate(candidate, Build.VERSION.SDK_INT,
            prefConfig?.allowExperimentalHaptics == true, hasPermission = true, uniqueDevice = true,
            reserved = forwardingReservations.contains(route.device.instance))
        if (availability != HapticAvailability.INITIALIZING) {
            publishWaveform(route, availability)
            sink.stop()
            return
        }
        listener?.onDualSenseNativeHapticsSinkAvailable(controllerId, sink)
    }

    override fun onDualSenseNativeHapticsSinkGone(controllerId: Int) {
        waveformRoutes.snapshots().firstOrNull { it.id == controllerId }?.let {
            publishWaveform(it, HapticAvailability.DISCONNECTED)
        }
        listener?.onDualSenseNativeHapticsSinkGone(controllerId)
    }

    override fun deviceRemoved(controller: AbstractController) {
        val suppressCallback = sessionLock.withLock {
            synchronized(controllersLock) {
                controllers.remove(controller)
                controllerDevices.remove(controller)
            }
            sessionHandoff.isStoppingController(controller.getControllerId())
        }
        if (!suppressCallback) listener?.deviceRemoved(controller)
        if (controller is UsbWaveformController && started) mainHandler.post {
            sessionLock.withLock {
                if (started) usbManager?.let { manager ->
                    manager.deviceList[controller.route.device.instance]?.let { discoverWaveformRoutes(manager, it) }
                }
            }
        }
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
                    device?.let(::forwardingDeviceDetached)
                    val removedRouteIds = ArrayList<Int>()
                    val companions = sessionLock.withLock {
                        val companions = synchronized(controllersLock) {
                            controllers.filter { it is UsbWaveformController && controllerDevices[it] == device?.deviceName }
                        }
                        device?.let { detached ->
                            waveformRoutes.remove(detached.deviceName).forEach { route ->
                                waveformPermissionRequests.remove(route.id)
                                removedRouteIds.add(route.id)
                            }
                            // A replug is a fresh attempt; forget any failure backoff.
                            waveformPacing.remove(detached.deviceName)
                        }
                        // Removing one of two identical devices can make the remaining route unambiguous.
                        mainHandler.post {
                            usbManager?.deviceList?.values?.forEach { remaining ->
                                sessionLock.withLock { if (started) discoverWaveformRoutes(usbManager!!, remaining) }
                            }
                        }
                        if (device?.deviceId == wirelessBridgeDeviceId) {
                            stopWirelessBridgeLocked(adapterPresent = false)
                        }
                        companions
                    }
                    // Both notifications and stop may synchronously re-enter the service.
                    // Release sessionLock before invoking any waveform teardown callbacks.
                    removedRouteIds.forEach { listener?.onWaveformRouteGone(it) }
                    companions.forEach { it.stop() }
                } else if (action == ACTION_USB_PERMISSION) {
                    try {
                        @Suppress("DEPRECATION")
                        val device: UsbDevice? =
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        // The prompt has settled; forget the in-flight request so a later
                        // discovery may prompt again instead of being wedged by the dedupe set.
                        device?.deviceName?.let { instance ->
                            waveformRoutes.snapshots()
                                .filter { it.device.instance == instance }
                                .forEach { waveformPermissionRequests.remove(it.id) }
                        }
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
            waveformRoutes.snapshots().forEach(listener::onWaveformRouteChanged)
            val controllerSnapshot = synchronized(controllersLock) {
                controllers.toList()
            }
            for (controller in controllerSnapshot) {
                if (controller is UsbWaveformController) controller.announce()
                else listener.deviceAdded(controller)
            }
        }
    }

    private fun handleUsbDeviceState(device: UsbDevice) {
        val mgr = usbManager ?: return
        val config = prefConfig ?: return
        val companionOwnsDevice = discoverWaveformRoutes(mgr, device)
        if (forwardingReservations.contains(device.deviceName) || companionOwnsDevice) return
        if (synchronized(controllersLock) { controllerDevices.values.contains(device.deviceName) }) return

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
                        DualSenseUsbController(device, connection,
                            waveformRoutes.snapshots().firstOrNull { it.device.instance == device.deviceName }
                                ?.let(::renewWaveformRoute)?.id
                                ?: ControllerDriverIdAllocator.allocate(), this)
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
                    controllerDevices[controller] = device.deviceName
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

    /** Runs for every attached USB device, independently of input-driver preferences. */
    private fun discoverWaveformRoutes(mgr: UsbManager, device: UsbDevice): Boolean {
        val identity = UsbWaveformBackends.identity(device)
        if (waveformRoutes.snapshots().any { it.device.instance == identity.instance && it.device != identity }) {
            waveformRoutes.remove(identity.instance).forEach {
                waveformPermissionRequests.remove(it.id)
                listener?.onWaveformRouteGone(it.id)
            }
            waveformPacing.remove(identity.instance)
            val oldOwners = synchronized(controllersLock) {
                controllers.filter { controllerDevices[it] == identity.instance }
            }
            if (oldOwners.isNotEmpty()) {
                oldOwners.forEach { owner ->
                    owner.stopWithResult { result ->
                        if (result.isSuccess) mainHandler.post { handleUsbDeviceStateSafely(device) }
                    }
                }
                return true
            }
        }
        val candidates = waveformRegistry.discover(identity)
        // One output companion per device: an ambiguous claim opens nothing rather than
        // guessing between competing protocols.
        val selected = candidates.singleOrNull {
            it.ownership == HapticBackendOwnership.OUTPUT_COMPANION &&
                it.capability.output == HapticOutput.WAVEFORM_STREAM && it.layoutMatches
        }
        val unique = mgr.deviceList.values.count {
            it.vendorId == device.vendorId && it.productId == device.productId
        } == 1
        var companionOwnsDevice = false
        for (candidate in candidates) {
            val route = waveformRoutes.discover(identity, candidate.capability)
            val state = if (candidate.layoutMatches && selected == null) HapticAvailability.NEEDS_ASSOCIATION
                else HapticActivationPolicy.evaluate(candidate, Build.VERSION.SDK_INT,
                prefConfig?.allowExperimentalHaptics == true, mgr.hasPermission(device), unique,
                forwardingReservations.contains(device.deviceName))
            if (!unique) {
                val ambiguous = synchronized(controllersLock) {
                    controllers.filter { it is UsbWaveformController &&
                        it.getVendorId() == device.vendorId && it.getProductId() == device.productId }
                }
                ambiguous.forEach { controller ->
                    val existing = (controller as UsbWaveformController).route
                    publishWaveform(existing, HapticAvailability.NEEDS_ASSOCIATION)
                    controller.stop()
                }
            }
            val alreadyOwned = synchronized(controllersLock) { controllers.any { it.getControllerId() == route.id } }
            if (alreadyOwned) {
                companionOwnsDevice = companionOwnsDevice || candidate.ownership == HapticBackendOwnership.OUTPUT_COMPANION
                continue
            }
            val reportedState = if (candidate.ownership == HapticBackendOwnership.INPUT_DRIVER &&
                state == HapticAvailability.INITIALIZING) HapticAvailability.UNSUPPORTED_PATH else state
            publishWaveform(route, reportedState)
            // The existing driver owns combined input/UAC devices. Discovery never steals its interfaces.
            if (candidate.ownership != HapticBackendOwnership.OUTPUT_COMPANION) continue
            if (candidate != selected) continue
            if (state == HapticAvailability.NEEDS_PERMISSION) {
                if (waveformPermissionRequests.add(route.id)) requestUsbPermission(mgr, device, "Controller waveform haptics")
            }
            if (state != HapticAvailability.INITIALIZING) continue
            val pacing = waveformPacing[identity.instance]
            if (pacing != null) {
                if (pacing.attempts >= MAX_WAVEFORM_BUILDS_PER_DEVICE) {
                    // Failure budget exhausted for this session. Stick at FAILED until the
                    // device is re-enumerated (replug/identity change) or the USB session
                    // restarts — both clear pacing — instead of cycling the channel forever.
                    publishWaveform(route, HapticAvailability.FAILED)
                    continue
                }
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs < pacing.blockedUntilMs) {
                    scheduleWaveformRetry(identity.instance, pacing.blockedUntilMs - nowMs)
                    publishWaveform(route, HapticAvailability.FAILED)
                    continue
                }
            }
            val activeRoute = renewWaveformRoute(route) ?: continue
            val sink = UsbWaveformBackends.create(mgr, device, candidate) { capability ->
                // Worker callbacks are connection-scoped. Ignore completions after detach/replacement.
                mainHandler.post {
                    var failedCompanion: UsbWaveformController? = null
                    sessionLock.withLock {
                        if (!started) return@post
                        val companion = synchronized(controllersLock) {
                            controllers.firstOrNull {
                                it.getControllerId() == activeRoute.id && it is UsbWaveformController && !it.isStopping
                            } as? UsbWaveformController
                        } ?: return@post
                        if (capability.availability == HapticAvailability.READY) waveformPacing.remove(device.deviceName)
                        waveformRoutes.update(activeRoute.id, capability)?.let { listener?.onWaveformRouteChanged(it) }
                        // A finished sink cannot recover in place. Release its companion so the
                        // removal path re-runs discovery; creation pacing bounds repeat attempts.
                        if (capability.availability == HapticAvailability.FAILED) failedCompanion = companion
                    }
                    // Stop outside sessionLock: teardown callbacks re-enter the service.
                    failedCompanion?.stop()
                }
            }
            if (sink == null) { publishWaveform(activeRoute, HapticAvailability.UNSUPPORTED_PATH); continue }
            val companion = UsbWaveformController(activeRoute, sink, this) { reopenWaveformRoute(activeRoute.id) }
            synchronized(controllersLock) {
                controllers.add(companion)
                controllerDevices[companion] = device.deviceName
            }
            publishWaveform(activeRoute, HapticAvailability.NEEDS_ASSOCIATION)
            armWaveformPacing(device.deviceName)
            companion.start()
            companionOwnsDevice = true
        }
        return companionOwnsDevice
    }

    private fun renewWaveformRoute(route: HapticRouteSnapshot): HapticRouteSnapshot? =
        waveformRoutes.renew(route.id)?.also {
            waveformPermissionRequests.remove(route.id)
            listener?.onWaveformRouteGone(route.id)
            listener?.onWaveformRouteChanged(it)
        }

    private fun reopenWaveformRoute(routeId: Int) {
        mainHandler.post {
            sessionLock.withLock {
                if (!started) return@withLock
                val route = waveformRoutes.snapshots().firstOrNull { it.id == routeId } ?: return@withLock
                val owner = synchronized(controllersLock) { controllers.firstOrNull { it.getControllerId() == routeId } }
                    ?: return@withLock
                waveformRoutes.remove(route.device.instance).forEach {
                    waveformPermissionRequests.remove(it.id)
                    listener?.onWaveformRouteGone(it.id)
                }
                owner.stopWithResult { result ->
                    if (result.isSuccess) mainHandler.post {
                        usbManager?.deviceList?.get(route.device.instance)?.let(::handleUsbDeviceStateSafely)
                    }
                }
            }
        }
    }

    /**
     * Backoff between waveform-channel rebuilds of one device, counted per creation since the
     * last READY report or physical re-enumeration: the first build is immediate, and each
     * consecutive build without a working channel waits longer (5s, 10s, 20s, 40s, then 60s).
     * A single transient failure still retries on the next discovery; a persistently broken
     * device converges to one attempt per minute and, once [MAX_WAVEFORM_BUILDS_PER_DEVICE]
     * builds have failed, the route parks at FAILED until re-enumeration or a USB session
     * restart clears the budget.
     */
    private fun armWaveformPacing(instance: String) {
        val delays = longArrayOf(0L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
        val attempts = waveformPacing[instance]?.attempts ?: 0
        val delay = delays[attempts.coerceAtMost(delays.lastIndex)]
        waveformPacing[instance] = WaveformPacing(attempts + 1, SystemClock.elapsedRealtime() + delay)
    }

    private fun scheduleWaveformRetry(instance: String, delayMs: Long) {
        waveformRetryRunnables.remove(instance)?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { retryWaveformDiscovery(instance) }
        waveformRetryRunnables[instance] = runnable
        mainHandler.postDelayed(runnable, delayMs.coerceIn(1L, 60_000L))
    }

    private fun retryWaveformDiscovery(instance: String) {
        waveformRetryRunnables.remove(instance)
        sessionLock.withLock {
            if (!started) return
            usbManager?.let { manager ->
                manager.deviceList[instance]?.let { discoverWaveformRoutes(manager, it) }
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
        prefConfig = PreferenceConfiguration.readPreferences(this)
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
        stopResult = UsbDriverStopResult()

        started = false
        waveformRoutes.clear().forEach { listener?.onWaveformRouteGone(it.id) }
        waveformPermissionRequests.clear()
        waveformPacing.clear()
        waveformRetryRunnables.values.forEach(mainHandler::removeCallbacks)
        waveformRetryRunnables.clear()

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
                controller.stopWithResult { result ->
                    onControllerStopCompleted(generation, controllerId, result.exceptionOrNull())
                }
            }.onFailure {
                LimeLog.warning("Unable to stop USB controller: ${it.message}")
                onControllerStopCompleted(generation, controllerId, it)
            }
        }
    }

    private fun onControllerStopCompleted(generation: Long, controllerId: Int, error: Throwable? = null) {
        sessionLock.withLock {
            if (error != null) stopResult.failed(error)
            val completion = sessionHandoff.completeController(generation, controllerId)
            if (completion.finished) finishStopLocked(completion.pendingStart)
        }
    }

    private fun finishStopLocked(restart: StartRequest?) {
        stopResult.finish()
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
        sessionLock.withLock { forwardingServices.add(this) }
        this.usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        this.prefConfig = PreferenceConfiguration.readPreferences(this)
    }

    override fun onDestroy() {
        sessionLock.withLock {
            sessionOwner.reset()
            sessionHandoff.cancelPendingStart()
            listener = null
            stateListener = null
            // Remain discoverable until old connections are closed, even if a new
            // service instance has already been created for the next Activity.
            stop { forwardingLock.withLock { forwardingServices.remove(this) } }
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
        // Shared with session startup so a late permission callback or a replacement
        // service cannot reclaim a device while its exporter owns it.
        private val forwardingReservations = UsbForwardingReservations()
        private val forwardingLock = forwardingReservations.lock
        private val forwardingServices = mutableSetOf<UsbDriverService>()

        fun forwardingDeviceDetached(device: UsbDevice) {
            forwardingReservations.deviceDetached(device.deviceName)
        }

        class ForwardingReservation internal constructor(
            private val lease: UsbForwardingReservations.Lease
        ) {
            val ready get() = lease.ready

            fun restoreWhenReady(onFailure: (Throwable) -> Unit) {
                lease.restoreWhenReady(::restoreDrivers, onFailure)
            }

            fun restore() = lease.restore(::restoreDrivers)

            private fun restoreDrivers(path: String) {
                forwardingServices.forEach { service ->
                    service.mainHandler.post {
                        service.usbManager?.deviceList?.get(path)?.let {
                            service.handleUsbDeviceStateSafely(it)
                        }
                    }
                }
            }
        }

        /** Called on the export worker; ready completes only after local USB release. */
        fun reserveForForwarding(device: UsbDevice): ForwardingReservation {
            val lease: UsbForwardingReservations.Lease
            val stops = mutableListOf<CompletionSignal>()
            val controllersToStop = mutableListOf<AbstractController>()
            forwardingLock.withLock {
                lease = forwardingReservations.reserve(device.deviceName)
                forwardingServices.forEach { service ->
                    if (service.sessionHandoff.isStopping) {
                        stops.add(service.stopResult.completion)
                    } else {
                        synchronized(service.controllersLock) {
                            val matches = service.controllers.filter {
                                service.controllerDevices[it] == device.deviceName
                            }
                            controllersToStop.addAll(matches)
                            service.controllers.removeAll(matches.toSet())
                        }
                    }
                }
            }
            // Drivers may join threads which call back into the service. Never stop
            // them under the session lock.
            controllersToStop.forEach { controller ->
                val stopped = CompletionSignal()
                stops.add(stopped)
                try {
                    controller.stopWithResult { result ->
                        result.fold({ stopped.complete() }, { stopped.completeExceptionally(it) })
                    }
                } catch (error: Exception) {
                    stopped.completeExceptionally(error)
                }
            }
            lease.awaitStops(stops)
            return ForwardingReservation(lease)
        }

        private const val ACTION_USB_PERMISSION = "com.limelight.USB_PERMISSION"

        /** Channel builds allowed per device between successes; the route parks at FAILED beyond this. */
        private const val MAX_WAVEFORM_BUILDS_PER_DEVICE = 5
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
