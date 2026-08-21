package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.binding.input.driver.ControllerDriverIdAllocator
import com.limelight.binding.input.driver.ControllerDriverListener
import com.limelight.binding.input.driver.wireless.hci.HciAclLink
import com.limelight.binding.input.driver.wireless.hci.HciAclPacket
import com.limelight.binding.input.driver.wireless.hci.HciAdapterBootstrap
import com.limelight.binding.input.driver.wireless.hci.HciAdapterBootstrapFailure
import com.limelight.binding.input.driver.wireless.hci.HciAdapterBootstrapListener
import com.limelight.binding.input.driver.wireless.hci.HciAdapterCapabilities
import com.limelight.binding.input.driver.wireless.hci.HciConnectionFailure
import com.limelight.binding.input.driver.wireless.hci.HciConnectionListener
import com.limelight.binding.input.driver.wireless.hci.HciConnectionEventCodec
import com.limelight.binding.input.driver.wireless.hci.HciConnectionRequest
import com.limelight.binding.input.driver.wireless.hci.HciDiscoveredDevice
import com.limelight.binding.input.driver.wireless.hci.HciDiscoveryFailure
import com.limelight.binding.input.driver.wireless.hci.HciDiscoveryListener
import com.limelight.binding.input.driver.wireless.hci.HciEventPacket
import com.limelight.binding.input.driver.wireless.hci.HciLinkKeyStore
import com.limelight.binding.input.driver.wireless.hci.HciSecurityFailure
import com.limelight.binding.input.driver.wireless.hci.HciSecurityListener
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal interface DualSenseWirelessBridgeHost : Closeable {
    fun start(): Boolean
    fun startDiscovery(listener: HciDiscoveryListener): Boolean
    fun cancelDiscovery(): Boolean
    fun connect(device: HciDiscoveredDevice, listener: HciConnectionListener): Boolean
    fun acceptIncomingConnection(
        request: HciConnectionRequest,
        listener: HciConnectionListener
    ): Boolean
    fun disconnect(): Boolean
    fun secureConnection(keyStore: HciLinkKeyStore, listener: HciSecurityListener): Boolean
    fun openDualSenseHidp(listener: DualSenseHidpListener): Boolean
    fun closeDualSenseHidp(): Boolean
    fun sendDualSenseOutputReport(report: ByteArray): Boolean
    fun flushAcl(timeoutMs: Long): Boolean
}

/** Keeps the production HCI implementation behind the bridge state machine's test seam. */
internal class HciDualSenseWirelessBridgeHost(
    private val bootstrap: HciAdapterBootstrap
) : DualSenseWirelessBridgeHost {
    override fun start(): Boolean = bootstrap.start()
    override fun startDiscovery(listener: HciDiscoveryListener): Boolean =
        bootstrap.startDiscovery(listener)
    override fun cancelDiscovery(): Boolean = bootstrap.cancelDiscovery()
    override fun connect(device: HciDiscoveredDevice, listener: HciConnectionListener): Boolean =
        bootstrap.connect(device, listener)
    override fun acceptIncomingConnection(
        request: HciConnectionRequest,
        listener: HciConnectionListener
    ): Boolean = bootstrap.acceptIncomingConnection(request, listener)
    override fun disconnect(): Boolean = bootstrap.disconnect()
    override fun secureConnection(
        keyStore: HciLinkKeyStore,
        listener: HciSecurityListener
    ): Boolean = bootstrap.secureConnection(keyStore, listener)
    override fun openDualSenseHidp(listener: DualSenseHidpListener): Boolean =
        bootstrap.openDualSenseHidp(listener)
    override fun closeDualSenseHidp(): Boolean = bootstrap.closeDualSenseHidp()
    override fun sendDualSenseOutputReport(report: ByteArray): Boolean =
        bootstrap.sendDualSenseOutputReport(report)
    override fun flushAcl(timeoutMs: Long): Boolean = bootstrap.flushAcl(timeoutMs)
    override fun close() = bootstrap.close()
}

internal enum class DualSenseWirelessBridgeState {
    DETACHED,
    STARTING_ADAPTER,
    READY,
    DISCOVERING,
    CONNECTING,
    SECURING,
    OPENING_HID,
    ACTIVE,
    STOPPING,
    FAILED
}

internal enum class DualSenseWirelessBridgeFailureStage {
    ADAPTER,
    DISCOVERY,
    CONNECTION,
    SECURITY,
    HID
}

internal data class DualSenseWirelessBridgeFailure(
    val stage: DualSenseWirelessBridgeFailureStage,
    val adapterFailure: HciAdapterBootstrapFailure? = null,
    val discoveryFailure: HciDiscoveryFailure? = null,
    val connectionFailure: HciConnectionFailure? = null,
    val securityFailure: HciSecurityFailure? = null
)

internal interface DualSenseWirelessBridgeListener {
    fun onStateChanged(state: DualSenseWirelessBridgeState)
    fun onAdapterReady(capabilities: HciAdapterCapabilities) = Unit
    fun onDeviceFound(device: HciDiscoveredDevice) = Unit
    fun onFailure(failure: DualSenseWirelessBridgeFailure) = Unit
}

/**
 * Owns one external HCI adapter and one DualSense connection.
 *
 * Protocol details remain in [HciAdapterBootstrap]. This class only sequences user-visible
 * discovery, ACL security, HIDP, and the normal application controller lifecycle.
 */
internal class DualSenseWirelessBridgeManager(
    private val controllerListener: ControllerDriverListener,
    private val linkKeyStore: HciLinkKeyStore,
    private val hostFactory: (HciAdapterBootstrapListener) -> DualSenseWirelessBridgeHost,
    private val listener: DualSenseWirelessBridgeListener,
    private val hidOpenDelayMs: Long = DEFAULT_HID_OPEN_DELAY_MS
) : Closeable {
    private val lock = Any()
    private val discoveredDevices = LinkedHashMap<Long, HciDiscoveredDevice>()
    private val hidOpenExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DualSenseHidOpen").apply { isDaemon = true }
    }

    @Volatile
    var state = DualSenseWirelessBridgeState.DETACHED
        private set

    @Volatile
    var failure: DualSenseWirelessBridgeFailure? = null
        private set

    private var host: DualSenseWirelessBridgeHost? = null
    private var controller: DualSenseWirelessController? = null
    private var selectedDevice: HciDiscoveredDevice? = null
    private var pendingIncomingRequest: HciConnectionRequest? = null
    private var pendingHidOpen: ScheduledFuture<*>? = null

    init {
        require(hidOpenDelayMs >= 0)
    }

    fun start(): Boolean {
        synchronized(lock) {
            if (state != DualSenseWirelessBridgeState.DETACHED) {
                return state != DualSenseWirelessBridgeState.FAILED &&
                    state != DualSenseWirelessBridgeState.STOPPING
            }
            failure = null
            setStateLocked(DualSenseWirelessBridgeState.STARTING_ADAPTER)
            host = hostFactory(adapterListener)
        }

        val accepted = runCatching { host?.start() == true }.getOrDefault(false)
        if (!accepted && state != DualSenseWirelessBridgeState.FAILED) {
            fail(
                DualSenseWirelessBridgeFailure(
                    DualSenseWirelessBridgeFailureStage.ADAPTER
                )
            )
        }
        return accepted
    }

    fun startDiscovery(): Boolean {
        val currentHost: DualSenseWirelessBridgeHost
        synchronized(lock) {
            if (state != DualSenseWirelessBridgeState.READY) return false
            currentHost = host ?: return false
            discoveredDevices.clear()
            failure = null
            setStateLocked(DualSenseWirelessBridgeState.DISCOVERING)
        }
        if (currentHost.startDiscovery(discoveryListener)) return true
        operationFailed(
            DualSenseWirelessBridgeFailure(DualSenseWirelessBridgeFailureStage.DISCOVERY)
        )
        return false
    }

    fun cancelDiscovery(): Boolean {
        val host = synchronized(lock) {
            if (state != DualSenseWirelessBridgeState.DISCOVERING) return false
            host
        } ?: return false
        return host.cancelDiscovery()
    }

    fun discoveredDevices(): List<HciDiscoveredDevice> = synchronized(lock) {
        discoveredDevices.values.toList()
    }

    fun connect(address: Long): Boolean {
        val currentHost: DualSenseWirelessBridgeHost
        val device: HciDiscoveredDevice
        synchronized(lock) {
            if (state != DualSenseWirelessBridgeState.READY) return false
            currentHost = host ?: return false
            device = discoveredDevices[address] ?: return false
            if (!device.isPotentialDualSense) return false
            selectedDevice = device
            failure = null
            setStateLocked(DualSenseWirelessBridgeState.CONNECTING)
        }
        if (currentHost.connect(device, connectionListener)) return true
        operationFailed(
            DualSenseWirelessBridgeFailure(DualSenseWirelessBridgeFailureStage.CONNECTION)
        )
        return false
    }

    fun disconnect(): Boolean {
        val currentHost: DualSenseWirelessBridgeHost
        val currentController: DualSenseWirelessController?
        synchronized(lock) {
            if (state != DualSenseWirelessBridgeState.CONNECTING &&
                state != DualSenseWirelessBridgeState.SECURING &&
                state != DualSenseWirelessBridgeState.OPENING_HID &&
                state != DualSenseWirelessBridgeState.ACTIVE
            ) {
                return false
            }
            currentHost = host ?: return false
            currentController = controller
            controller = null
            cancelPendingHidOpenLocked()
            setStateLocked(DualSenseWirelessBridgeState.STOPPING)
        }
        currentController?.stop(sendNeutral = true)
        val accepted = currentHost.disconnect()
        if (!accepted) {
            synchronized(lock) {
                if (state == DualSenseWirelessBridgeState.STOPPING) {
                    setStateLocked(DualSenseWirelessBridgeState.READY)
                }
            }
        }
        return accepted
    }

    override fun close() {
        close(adapterPresent = true)
    }

    fun close(adapterPresent: Boolean) {
        val currentController: DualSenseWirelessController?
        val currentHost: DualSenseWirelessBridgeHost?
        synchronized(lock) {
            if (state == DualSenseWirelessBridgeState.DETACHED) return
            setStateLocked(DualSenseWirelessBridgeState.STOPPING)
            currentController = controller
            controller = null
            currentHost = host
            host = null
            cancelPendingHidOpenLocked()
            discoveredDevices.clear()
            selectedDevice = null
            pendingIncomingRequest = null
        }
        currentController?.stop(sendNeutral = adapterPresent)
        if (adapterPresent) {
            runCatching { currentHost?.flushAcl(TEARDOWN_FLUSH_TIMEOUT_MS) }
        }
        runCatching { currentHost?.close() }
        hidOpenExecutor.shutdownNow()
        synchronized(lock) {
            failure = null
            setStateLocked(DualSenseWirelessBridgeState.DETACHED)
        }
    }

    private val adapterListener = object : HciAdapterBootstrapListener {
        override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
            runCatching { listener.onAdapterReady(capabilities) }
            synchronized(lock) {
                if (state == DualSenseWirelessBridgeState.STARTING_ADAPTER) {
                    setStateLocked(DualSenseWirelessBridgeState.READY)
                }
            }
        }

        override fun onUnhandledEvent(packet: HciEventPacket) {
            if (packet.eventCode != CONNECTION_REQUEST_EVENT_CODE) return
            val request = HciConnectionEventCodec.decodeConnectionRequest(packet.parameters)
                ?: return
            if (!request.isAcl || runCatching { linkKeyStore.load(request.address) }.getOrNull() == null) {
                return
            }

            val shouldCancelDiscovery = synchronized(lock) {
                if (state != DualSenseWirelessBridgeState.READY &&
                    state != DualSenseWirelessBridgeState.DISCOVERING
                ) {
                    return
                }
                pendingIncomingRequest = request
                state == DualSenseWirelessBridgeState.DISCOVERING
            }
            if (shouldCancelDiscovery) {
                host?.cancelDiscovery()
            } else {
                acceptPendingIncomingConnection()
            }
        }
        override fun onAcl(packet: HciAclPacket) = Unit

        override fun onBootstrapFailure(failure: HciAdapterBootstrapFailure) {
            fail(
                DualSenseWirelessBridgeFailure(
                    DualSenseWirelessBridgeFailureStage.ADAPTER,
                    adapterFailure = failure
                )
            )
        }
    }

    private val discoveryListener = object : HciDiscoveryListener {
        override fun onDeviceFound(device: HciDiscoveredDevice) {
            if (!device.isPotentialDualSense) return
            synchronized(lock) {
                if (state != DualSenseWirelessBridgeState.DISCOVERING) return
                discoveredDevices[device.address.value] = device
            }
            runCatching { listener.onDeviceFound(device) }
        }

        override fun onDiscoveryComplete(devices: List<HciDiscoveredDevice>) {
            synchronized(lock) {
                if (state != DualSenseWirelessBridgeState.DISCOVERING) return
                devices.filter(HciDiscoveredDevice::isPotentialDualSense).forEach {
                    discoveredDevices[it.address.value] = it
                }
                setStateLocked(DualSenseWirelessBridgeState.READY)
            }
            acceptPendingIncomingConnection()
        }

        override fun onDiscoveryCancelled() {
            synchronized(lock) {
                if (state == DualSenseWirelessBridgeState.DISCOVERING) {
                    setStateLocked(DualSenseWirelessBridgeState.READY)
                }
            }
            acceptPendingIncomingConnection()
        }

        override fun onDiscoveryFailure(failure: HciDiscoveryFailure) {
            operationFailed(
                DualSenseWirelessBridgeFailure(
                    DualSenseWirelessBridgeFailureStage.DISCOVERY,
                    discoveryFailure = failure
                )
            )
            acceptPendingIncomingConnection()
        }
    }

    private val connectionListener = object : HciConnectionListener {
        override fun onConnected(link: HciAclLink) {
            if (link.encrypted) {
                scheduleOpenController(link.initiatedByRemote)
                return
            }
            val currentHost = synchronized(lock) {
                if (state != DualSenseWirelessBridgeState.CONNECTING) return
                setStateLocked(DualSenseWirelessBridgeState.SECURING)
                host
            } ?: return
            if (!currentHost.secureConnection(linkKeyStore, securityListener)) {
                operationFailed(
                    DualSenseWirelessBridgeFailure(DualSenseWirelessBridgeFailureStage.SECURITY)
                )
            }
        }

        override fun onDisconnected(link: HciAclLink, reason: Int) {
            clearController(adapterPresent = false)
            synchronized(lock) {
                cancelPendingHidOpenLocked()
                if (state != DualSenseWirelessBridgeState.DETACHED &&
                    state != DualSenseWirelessBridgeState.FAILED
                ) {
                    setStateLocked(DualSenseWirelessBridgeState.READY)
                }
            }
        }

        override fun onConnectionCancelled() {
            clearController(adapterPresent = false)
            synchronized(lock) {
                if (state == DualSenseWirelessBridgeState.CONNECTING ||
                    state == DualSenseWirelessBridgeState.STOPPING
                ) {
                    setStateLocked(DualSenseWirelessBridgeState.READY)
                }
            }
        }

        override fun onConnectionFailure(failure: HciConnectionFailure) {
            operationFailed(
                DualSenseWirelessBridgeFailure(
                    DualSenseWirelessBridgeFailureStage.CONNECTION,
                    connectionFailure = failure
                )
            )
        }
    }

    private val securityListener = object : HciSecurityListener {
        override fun onLinkEncrypted(link: HciAclLink) =
            scheduleOpenController(link.initiatedByRemote)

        override fun onSecurityFailure(failure: HciSecurityFailure) {
            operationFailed(
                DualSenseWirelessBridgeFailure(
                    DualSenseWirelessBridgeFailureStage.SECURITY,
                    securityFailure = failure
                )
            )
        }
    }

    private fun scheduleOpenController(openImmediately: Boolean = false) {
        synchronized(lock) {
            if (state != DualSenseWirelessBridgeState.CONNECTING &&
                state != DualSenseWirelessBridgeState.SECURING
            ) {
                return
            }
            if (pendingHidOpen != null) return
            if (openImmediately || hidOpenDelayMs == 0L) {
                openController()
                return
            }
            pendingHidOpen = hidOpenExecutor.schedule(
                ::openController,
                hidOpenDelayMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun openController() {
        val currentHost: DualSenseWirelessBridgeHost
        val wirelessController: DualSenseWirelessController
        synchronized(lock) {
            pendingHidOpen = null
            if (state != DualSenseWirelessBridgeState.CONNECTING &&
                state != DualSenseWirelessBridgeState.SECURING
            ) {
                return
            }
            currentHost = host ?: return
            val productId = if (selectedDevice?.name == DUALSENSE_EDGE_NAME) {
                DualSenseWirelessController.PRODUCT_DUALSENSE_EDGE
            } else {
                DualSenseWirelessController.PRODUCT_DUALSENSE
            }
            wirelessController = DualSenseWirelessController(
                deviceId = ControllerDriverIdAllocator.allocate(),
                listener = controllerListener,
                productId = productId,
                openSession = currentHost::openDualSenseHidp,
                closeSession = currentHost::closeDualSenseHidp,
                sendOutputReport = currentHost::sendDualSenseOutputReport,
                onReady = ::onControllerReady,
                onLinkTerminated = ::onControllerLinkTerminated
            )
            controller = wirelessController
            setStateLocked(DualSenseWirelessBridgeState.OPENING_HID)
        }
        if (!wirelessController.start()) {
            synchronized(lock) {
                if (controller === wirelessController) controller = null
            }
            operationFailed(
                DualSenseWirelessBridgeFailure(DualSenseWirelessBridgeFailureStage.HID)
            )
        }
    }

    private fun onControllerReady() {
        synchronized(lock) {
            if (state == DualSenseWirelessBridgeState.OPENING_HID) {
                setStateLocked(DualSenseWirelessBridgeState.ACTIVE)
            }
        }
    }

    private fun onControllerLinkTerminated() {
        val currentHost = synchronized(lock) {
            controller = null
            if (state == DualSenseWirelessBridgeState.STOPPING ||
                state == DualSenseWirelessBridgeState.DETACHED
            ) {
                return
            }
            host
        } ?: return
        if (!currentHost.disconnect()) {
            operationFailed(
                DualSenseWirelessBridgeFailure(DualSenseWirelessBridgeFailureStage.HID)
            )
        }
    }

    private fun clearController(adapterPresent: Boolean) {
        val current = synchronized(lock) {
            controller.also { controller = null }
        }
        current?.stop(sendNeutral = adapterPresent)
    }

    private fun acceptPendingIncomingConnection() {
        val currentHost: DualSenseWirelessBridgeHost
        val request: HciConnectionRequest
        synchronized(lock) {
            if (state != DualSenseWirelessBridgeState.READY) return
            currentHost = host ?: return
            request = pendingIncomingRequest ?: return
            pendingIncomingRequest = null
            selectedDevice = discoveredDevices[request.address.value] ?: HciDiscoveredDevice(
                address = request.address,
                pageScanRepetitionMode = 0,
                classOfDevice = request.classOfDevice,
                clockOffset = 0,
                name = DUALSENSE_NAME
            )
            failure = null
            setStateLocked(DualSenseWirelessBridgeState.CONNECTING)
        }
        if (!currentHost.acceptIncomingConnection(request, connectionListener)) {
            operationFailed(
                DualSenseWirelessBridgeFailure(DualSenseWirelessBridgeFailureStage.CONNECTION)
            )
        }
    }

    private fun operationFailed(value: DualSenseWirelessBridgeFailure) {
        if (value.stage == DualSenseWirelessBridgeFailureStage.SECURITY ||
            value.stage == DualSenseWirelessBridgeFailureStage.HID
        ) {
            // These failures leave an ACL link behind. Resetting the adapter is the only
            // deterministic recovery until the user retries; pretending to be READY would make
            // the next discovery fail because HciAdapterBootstrap still owns that connection.
            fail(value)
            return
        }
        clearController(adapterPresent = false)
        synchronized(lock) {
            failure = value
            if (state != DualSenseWirelessBridgeState.DETACHED &&
                state != DualSenseWirelessBridgeState.FAILED
            ) {
                setStateLocked(DualSenseWirelessBridgeState.READY)
            }
        }
        runCatching { listener.onFailure(value) }
    }

    private fun fail(value: DualSenseWirelessBridgeFailure) {
        clearController(adapterPresent = false)
        val currentHost = synchronized(lock) {
            if (state == DualSenseWirelessBridgeState.DETACHED ||
                state == DualSenseWirelessBridgeState.STOPPING
            ) {
                return
            }
            failure = value
            cancelPendingHidOpenLocked()
            setStateLocked(DualSenseWirelessBridgeState.FAILED)
            host.also { host = null }
        }
        runCatching { currentHost?.close() }
        hidOpenExecutor.shutdownNow()
        runCatching { listener.onFailure(value) }
    }

    private fun cancelPendingHidOpenLocked() {
        pendingHidOpen?.cancel(false)
        pendingHidOpen = null
    }

    private fun setStateLocked(value: DualSenseWirelessBridgeState) {
        if (state == value) return
        state = value
        runCatching { listener.onStateChanged(value) }
    }

    private companion object {
        const val DUALSENSE_EDGE_NAME = "DualSense Edge Wireless Controller"
        const val DUALSENSE_NAME = "DualSense Wireless Controller"
        const val CONNECTION_REQUEST_EVENT_CODE = 0x04
        const val TEARDOWN_FLUSH_TIMEOUT_MS = 250L
        const val DEFAULT_HID_OPEN_DELAY_MS = 750L
    }
}
