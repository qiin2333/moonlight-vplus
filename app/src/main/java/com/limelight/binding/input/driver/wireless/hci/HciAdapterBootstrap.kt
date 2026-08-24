package com.limelight.binding.input.driver.wireless.hci

import com.limelight.binding.input.driver.DualSenseInputState
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseBluetoothInputDisposition
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseBluetoothInputResult
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseHidpListener
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseHidpSession
import com.limelight.binding.input.driver.wireless.dualsense.DualSenseHidpState
import com.limelight.binding.input.driver.wireless.hidp.HidpFailure
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidChannels
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidFailure
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidListener
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidSession
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidState
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal enum class HciAdapterBootstrapState {
    CLOSED,
    STARTING,
    INITIALIZING,
    READY,
    FAILED,
    CLOSING
}

internal enum class HciAdapterBootstrapErrorCode {
    TRANSPORT_OPEN_FAILED,
    TRANSPORT_FAILED,
    INITIALIZATION_FAILED,
    ACL_OUTPUT_CONFIGURATION_FAILED
}

internal data class HciAdapterBootstrapFailure(
    val code: HciAdapterBootstrapErrorCode,
    val transportFailure: HciTransportFailure? = null,
    val initializationFailure: HciAdapterInitializationFailure? = null
)

internal interface HciAdapterBootstrapListener {
    fun onAdapterReady(capabilities: HciAdapterCapabilities)
    fun onUnhandledEvent(packet: HciEventPacket)
    fun onAcl(packet: HciAclPacket)
    fun onBootstrapFailure(failure: HciAdapterBootstrapFailure)
}

/** Owns transport bootstrap, initialization timeouts, and handoff to the future host state machine. */
internal class HciAdapterBootstrap(
    private val transport: HciTransport,
    private val listener: HciAdapterBootstrapListener,
    commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS
) : HciPacketListener, Closeable {
    private val lifecycleLock = Any()
    private val commandExecutor = HciCommandExecutor(
        sendCommand = transport::sendCommand,
        commandTimeoutMs = commandTimeoutMs
    )
    private val initializer = HciAdapterInitializer(
        commandExecutor,
        resetTimeoutRetries = if (transport.profile == HciUsbAdapterProfile.CSR) 1 else 0,
        beforeResetRetry = {
            try {
                Thread.sleep(CSR_RESET_SETTLE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        },
        allowPageScanFailure = transport.profile == HciUsbAdapterProfile.CSR
    )
    private val liveLinkTuner = HciLiveLinkTuner(commandExecutor::submit)

    @Volatile
    var state = HciAdapterBootstrapState.CLOSED
        private set

    private var timeoutExecutor: ScheduledExecutorService? = null
    private var timeoutTask: ScheduledFuture<*>? = null

    @Volatile
    private var activeDiscovery: HciDiscoveryController? = null

    @Volatile
    private var activeConnection: HciConnectionController? = null

    @Volatile
    private var activeSecurity: HciSecurityController? = null

    @Volatile
    private var activeHidChannels: L2capHidSession? = null

    @Volatile
    private var activeDualSenseSession: DualSenseHidpSession? = null

    @Volatile
    private var disconnectAfterHidClose = false

    fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (state != HciAdapterBootstrapState.CLOSED) {
                return state == HciAdapterBootstrapState.READY ||
                    state == HciAdapterBootstrapState.INITIALIZING
            }
            state = HciAdapterBootstrapState.STARTING
            transport.setListener(this)
        }

        if (!transport.open()) {
            failOnce(HciAdapterBootstrapFailure(HciAdapterBootstrapErrorCode.TRANSPORT_OPEN_FAILED))
            return false
        }

        synchronized(lifecycleLock) {
            if (state == HciAdapterBootstrapState.FAILED || state == HciAdapterBootstrapState.CLOSING) {
                return false
            }
            state = HciAdapterBootstrapState.INITIALIZING
            timeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "hci-bootstrap-timeout").apply { isDaemon = true }
            }
            timeoutTask = timeoutExecutor!!.scheduleAtFixedRate(
                ::checkSessionTimeouts,
                TIMEOUT_POLL_INTERVAL_MS,
                TIMEOUT_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }

        if (!initializer.start()) {
            handleInitializationFailure()
            return false
        }
        return true
    }

    override fun onEvent(packet: HciEventPacket) {
        if (commandExecutor.onEvent(packet)) {
            handleInitializerState()
            activeDiscovery?.let(::clearFinishedDiscovery)
            activeConnection?.let(::clearFinishedConnection)
            activeSecurity?.let(::clearFinishedSecurity)
            return
        }
        val discovery = activeDiscovery
        if (discovery != null && discovery.onEvent(packet)) {
            clearFinishedDiscovery(discovery)
            return
        }
        val security = activeSecurity
        if (security != null && security.onEvent(packet)) {
            clearFinishedSecurity(security)
            return
        }
        val connection = activeConnection
        if (connection != null && connection.onEvent(packet)) {
            val currentLink = connection.link
            if (currentLink != null &&
                (connection.state == HciConnectionState.DISCONNECTED ||
                    connection.state == HciConnectionState.FAILED)
            ) {
                activeSecurity?.let { active ->
                    active.onLinkDisconnected(currentLink.connectionHandle)
                    clearFinishedSecurity(active)
                }
                activeHidChannels?.let { active ->
                    active.onLinkDisconnected(currentLink.connectionHandle)
                    clearFinishedHidChannels(active)
                }
                liveLinkTuner.onLinkDisconnected(currentLink.connectionHandle)
            }
            clearFinishedConnection(connection)
            return
        }
        if (liveLinkTuner.onEvent(packet)) return
        if (state == HciAdapterBootstrapState.READY) {
            runCatching { listener.onUnhandledEvent(packet) }
        }
    }

    override fun onAcl(packet: HciAclPacket) {
        if (state == HciAdapterBootstrapState.READY) {
            val hidChannels = activeHidChannels
            if (hidChannels != null && hidChannels.onAcl(packet)) {
                clearFinishedHidChannels(hidChannels)
                clearFinishedDualSenseSession()
                return
            }
            runCatching { listener.onAcl(packet) }
        }
    }

    override fun onTransportFailure(failure: HciTransportFailure) {
        failOnce(
            HciAdapterBootstrapFailure(
                HciAdapterBootstrapErrorCode.TRANSPORT_FAILED,
                transportFailure = failure
            )
        )
    }

    /** Submits a command through the same one-outstanding-command gate used during bootstrap. */
    fun submitCommand(
        packet: HciCommandPacket,
        callback: (HciCommandResult) -> Unit
    ): Boolean {
        return state == HciAdapterBootstrapState.READY && activeDiscovery == null &&
            activeSecurity == null &&
            activeConnection?.blocksCommandSubmission() != true &&
            commandExecutor.submit(packet, callback)
    }

    fun startDiscovery(listener: HciDiscoveryListener): Boolean {
        val discovery: HciDiscoveryController
        synchronized(lifecycleLock) {
            if (state != HciAdapterBootstrapState.READY ||
                activeDiscovery != null || activeConnection != null
            ) {
                return false
            }
            discovery = HciDiscoveryController(commandExecutor::submit, listener)
            activeDiscovery = discovery
        }
        if (discovery.start()) {
            return true
        }
        clearFinishedDiscovery(discovery)
        return false
    }

    fun cancelDiscovery(): Boolean {
        val discovery = activeDiscovery ?: return false
        val accepted = discovery.cancel()
        clearFinishedDiscovery(discovery)
        return accepted
    }

    fun connect(device: HciDiscoveredDevice, listener: HciConnectionListener): Boolean {
        val connection: HciConnectionController
        synchronized(lifecycleLock) {
            if (state != HciAdapterBootstrapState.READY ||
                activeDiscovery != null || activeConnection != null
            ) {
                return false
            }
            connection = HciConnectionController(device, commandExecutor::submit, listener)
            activeConnection = connection
        }
        if (connection.start()) return true
        clearFinishedConnection(connection)
        return false
    }

    fun acceptIncomingConnection(
        request: HciConnectionRequest,
        listener: HciConnectionListener
    ): Boolean {
        if (!request.isAcl) return false
        // Discovery marks itself terminal before invoking its listener. Release that terminal
        // owner here as well as after onEvent() returns, so a cancellation callback can hand the
        // single HCI command gate directly to an incoming connection without a scheduling race.
        activeDiscovery?.let(::clearFinishedDiscovery)
        val connection: HciConnectionController
        synchronized(lifecycleLock) {
            if (state != HciAdapterBootstrapState.READY ||
                activeDiscovery != null || activeConnection != null
            ) {
                return false
            }
            val device = HciDiscoveredDevice(
                address = request.address,
                pageScanRepetitionMode = 0,
                classOfDevice = request.classOfDevice,
                clockOffset = 0,
                name = null
            )
            connection = HciConnectionController(
                device,
                commandExecutor::submit,
                listener,
                incomingRequest = request
            )
            activeConnection = connection
        }
        if (connection.start()) return true
        clearFinishedConnection(connection)
        return false
    }

    fun disconnect(): Boolean {
        if (activeSecurity != null) return false
        val hidChannels = activeHidChannels
        if (hidChannels != null) {
            return when (hidChannels.state) {
                L2capHidState.OPEN -> {
                    disconnectAfterHidClose = true
                    activeDualSenseSession?.beginClose()
                    hidChannels.close()
                }
                L2capHidState.CLOSING -> {
                    disconnectAfterHidClose = true
                    true
                }
                else -> false
            }
        }
        val connection = activeConnection ?: return false
        val accepted = connection.cancel()
        clearFinishedConnection(connection)
        return accepted
    }

    fun connectedLink(): HciAclLink? = activeConnection?.link

    fun secureConnection(keyStore: HciLinkKeyStore, listener: HciSecurityListener): Boolean {
        lateinit var security: HciSecurityController
        val connection: HciConnectionController
        synchronized(lifecycleLock) {
            connection = activeConnection ?: return false
            val link = connection.link ?: return false
            if (state != HciAdapterBootstrapState.READY ||
                connection.state != HciConnectionState.CONNECTED ||
                activeDiscovery != null || activeSecurity != null
            ) {
                return false
            }
            security = HciSecurityController(
                link,
                keyStore,
                commandExecutor::submit,
                object : HciSecurityListener {
                    override fun onLinkEncrypted(link: HciAclLink) {
                        connection.markEncrypted(link.connectionHandle)
                        // Hand off ownership before notifying the next stage. The listener is
                        // allowed to open HID synchronously from this callback.
                        clearFinishedSecurity(security)
                        runCatching { listener.onLinkEncrypted(connection.link ?: link) }
                    }

                    override fun onSecurityFailure(failure: HciSecurityFailure) {
                        clearFinishedSecurity(security)
                        runCatching { listener.onSecurityFailure(failure) }
                    }
                }
            )
            activeSecurity = security
        }
        if (security.start()) {
            clearFinishedSecurity(security)
            return true
        }
        clearFinishedSecurity(security)
        return false
    }

    fun openHidChannels(listener: L2capHidListener): Boolean {
        val session: L2capHidSession
        val link: HciAclLink
        synchronized(lifecycleLock) {
            link = activeConnection?.link ?: return false
            if (state != HciAdapterBootstrapState.READY || !link.encrypted ||
                activeSecurity != null || activeHidChannels != null
            ) {
                return false
            }
            disconnectAfterHidClose = false
            session = L2capHidSession(
                link.connectionHandle,
                transport::sendAcl,
                object : L2capHidListener {
                    override fun onChannelsOpen(
                        channels: L2capHidChannels
                    ) {
                        runCatching { listener.onChannelsOpen(channels) }
                    }

                    override fun onControlData(payload: ByteArray) {
                        runCatching { listener.onControlData(payload) }
                    }

                    override fun onInterruptData(payload: ByteArray) {
                        runCatching { listener.onInterruptData(payload) }
                    }

                    override fun onChannelsClosed() {
                        runCatching { listener.onChannelsClosed() }
                        disconnectAclAfterHidCloseIfRequested()
                    }

                    override fun onL2capFailure(failure: L2capHidFailure) {
                        runCatching { listener.onL2capFailure(failure) }
                        disconnectAclAfterHidCloseIfRequested()
                    }
                },
                preferRemoteInitiated = link.initiatedByRemote
            )
            activeHidChannels = session
        }
        if (session.start()) return true
        clearFinishedHidChannels(session)
        return false
    }

    fun openDualSenseHidp(listener: DualSenseHidpListener): Boolean {
        val session: DualSenseHidpSession
        synchronized(lifecycleLock) {
            if (activeDualSenseSession != null || activeHidChannels != null) return false
            session = DualSenseHidpSession(
                sendControl = ::sendHidControl,
                sendInterrupt = ::sendHidInterrupt,
                listener = object : DualSenseHidpListener {
                    override fun onReportProtocolReady() {
                        runCatching { listener.onReportProtocolReady() }
                    }

                    override fun onInput(
                        state: DualSenseInputState,
                        metadata: DualSenseBluetoothInputResult
                    ) {
                        activeConnection?.link?.let { link ->
                            liveLinkTuner.onFirstValidInput(link.connectionHandle)
                        }
                        runCatching { listener.onInput(state, metadata) }
                    }

                    override fun onInputRejected(
                        disposition: DualSenseBluetoothInputDisposition
                    ) {
                        runCatching { listener.onInputRejected(disposition) }
                    }

                    override fun onInvalidHidpFrame(header: Int?) {
                        runCatching { listener.onInvalidHidpFrame(header) }
                    }

                    override fun onVirtualCableUnplug() {
                        runCatching { listener.onVirtualCableUnplug() }
                        closeHidChannels()
                    }

                    override fun onClosed() {
                        runCatching { listener.onClosed() }
                    }

                    override fun onFailure(failure: HidpFailure) {
                        runCatching { listener.onFailure(failure) }
                        if (activeHidChannels?.state == L2capHidState.OPEN) {
                            closeHidChannels()
                        }
                    }
                }
            )
            activeDualSenseSession = session
        }
        if (openHidChannels(session.l2capListener)) return true
        synchronized(lifecycleLock) {
            if (activeDualSenseSession === session) activeDualSenseSession = null
        }
        return false
    }

    fun sendDualSenseOutputReport(report: ByteArray): Boolean =
        activeDualSenseSession?.sendOutputReport(report) ?: false

    fun flushAcl(timeoutMs: Long): Boolean = transport.flushAcl(timeoutMs)

    fun closeDualSenseHidp(): Boolean {
        val session = activeDualSenseSession ?: return false
        if (!session.beginClose()) return false
        return closeHidChannels()
    }

    fun sendHidControl(payload: ByteArray): Boolean =
        activeHidChannels?.sendControl(payload) ?: false

    fun sendHidInterrupt(payload: ByteArray): Boolean =
        activeHidChannels?.sendInterrupt(payload) ?: false

    fun closeHidChannels(): Boolean {
        disconnectAfterHidClose = false
        val session = activeHidChannels ?: return false
        val accepted = session.close()
        clearFinishedHidChannels(session)
        return accepted
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (state == HciAdapterBootstrapState.CLOSED || state == HciAdapterBootstrapState.CLOSING) {
                return
            }
            state = HciAdapterBootstrapState.CLOSING
            cancelTimeoutLocked()
        }

        transport.setListener(null)
        activeDiscovery = null
        activeConnection = null
        activeSecurity = null
        activeHidChannels = null
        activeDualSenseSession = null
        disconnectAfterHidClose = false
        commandExecutor.close()
        liveLinkTuner.close()
        runCatching { transport.close() }
        timeoutExecutor?.shutdownNow()
        timeoutExecutor = null

        synchronized(lifecycleLock) {
            state = HciAdapterBootstrapState.CLOSED
        }
    }

    private fun handleReady() {
        val capabilities = initializer.capabilities ?: run {
            handleInitializationFailure()
            return
        }
        if (!transport.configureAclOutput(
                capabilities.aclDataPacketLength,
                capabilities.aclPacketCredits
            )
        ) {
            failOnce(
                HciAdapterBootstrapFailure(
                    HciAdapterBootstrapErrorCode.ACL_OUTPUT_CONFIGURATION_FAILED
                )
            )
            return
        }

        synchronized(lifecycleLock) {
            if (state != HciAdapterBootstrapState.INITIALIZING) {
                return
            }
            state = HciAdapterBootstrapState.READY
        }
        runCatching { listener.onAdapterReady(capabilities) }
    }

    private fun checkSessionTimeouts() {
        if (state == HciAdapterBootstrapState.INITIALIZING ||
            state == HciAdapterBootstrapState.READY
        ) {
            if (commandExecutor.checkTimeout()) {
                handleInitializerState()
            }
            liveLinkTuner.checkProgress()
            val discovery = activeDiscovery
            if (discovery != null) {
                discovery.checkTimeout()
                clearFinishedDiscovery(discovery)
            }
            val connection = activeConnection
            if (connection != null) {
                connection.checkTimeout()
                clearFinishedConnection(connection)
            }
            val security = activeSecurity
            if (security != null) {
                security.checkTimeout()
                clearFinishedSecurity(security)
            }
            val hidChannels = activeHidChannels
            val dualSenseSession = activeDualSenseSession
            if (dualSenseSession != null) {
                dualSenseSession.checkTimeout()
            }
            if (hidChannels != null) {
                hidChannels.checkTimeout()
                clearFinishedHidChannels(hidChannels)
            }
            clearFinishedDualSenseSession()
        }
    }

    private fun clearFinishedDiscovery(discovery: HciDiscoveryController) {
        if (discovery.state != HciDiscoveryState.COMPLETE &&
            discovery.state != HciDiscoveryState.CANCELLED &&
            discovery.state != HciDiscoveryState.FAILED
        ) {
            return
        }
        synchronized(lifecycleLock) {
            if (activeDiscovery === discovery) {
                activeDiscovery = null
            }
        }
    }

    private fun clearFinishedConnection(connection: HciConnectionController) {
        if (connection.state != HciConnectionState.DISCONNECTED &&
            connection.state != HciConnectionState.CANCELLED &&
            connection.state != HciConnectionState.FAILED
        ) {
            return
        }
        synchronized(lifecycleLock) {
            if (activeConnection === connection) {
                activeConnection = null
            }
        }
    }

    private fun clearFinishedSecurity(security: HciSecurityController) {
        if (security.state != HciSecurityState.ENCRYPTED &&
            security.state != HciSecurityState.FAILED
        ) {
            return
        }
        synchronized(lifecycleLock) {
            if (activeSecurity === security) {
                activeSecurity = null
            }
        }
    }

    private fun clearFinishedHidChannels(session: L2capHidSession) {
        if (session.state != L2capHidState.CLOSED && session.state != L2capHidState.FAILED) {
            return
        }
        synchronized(lifecycleLock) {
            if (activeHidChannels === session) {
                activeHidChannels = null
            }
        }
    }

    private fun clearFinishedDualSenseSession() {
        val session = activeDualSenseSession ?: return
        if (activeHidChannels != null ||
            (session.state != DualSenseHidpState.CLOSED &&
                session.state != DualSenseHidpState.FAILED)
        ) {
            return
        }
        synchronized(lifecycleLock) {
            if (activeDualSenseSession === session) activeDualSenseSession = null
        }
    }

    private fun disconnectAclAfterHidCloseIfRequested() {
        if (!disconnectAfterHidClose) return
        disconnectAfterHidClose = false
        val connection = activeConnection ?: return
        connection.cancel()
    }

    private fun handleInitializerState() {
        when (initializer.state) {
            HciAdapterInitializationState.READY -> {
                if (state == HciAdapterBootstrapState.INITIALIZING) {
                    handleReady()
                }
            }
            HciAdapterInitializationState.FAILED -> handleInitializationFailure()
            else -> Unit
        }
    }

    private fun handleInitializationFailure() {
        failOnce(
            HciAdapterBootstrapFailure(
                HciAdapterBootstrapErrorCode.INITIALIZATION_FAILED,
                initializationFailure = initializer.failure
            )
        )
    }

    private fun failOnce(failure: HciAdapterBootstrapFailure) {
        synchronized(lifecycleLock) {
            if (state == HciAdapterBootstrapState.FAILED ||
                state == HciAdapterBootstrapState.CLOSING ||
                state == HciAdapterBootstrapState.CLOSED
            ) {
                return
            }
            state = HciAdapterBootstrapState.FAILED
            cancelTimeoutLocked()
        }

        runCatching { transport.close() }
        activeDiscovery = null
        activeConnection = null
        activeSecurity = null
        activeHidChannels = null
        activeDualSenseSession = null
        disconnectAfterHidClose = false
        commandExecutor.close()
        liveLinkTuner.close()
        timeoutExecutor?.shutdownNow()
        timeoutExecutor = null
        runCatching { listener.onBootstrapFailure(failure) }
    }

    private fun cancelTimeoutLocked() {
        timeoutTask?.cancel(false)
        timeoutTask = null
    }

    companion object {
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 3000L
        private const val TIMEOUT_POLL_INTERVAL_MS = 100L
        private const val CSR_RESET_SETTLE_MS = 220L
    }
}
