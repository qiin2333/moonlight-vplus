package com.limelight.binding.input.driver.wireless.hci

internal enum class HciConnectionState {
    IDLE,
    STARTING,
    CONNECTING,
    CANCELLING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
    CANCELLED,
    FAILED
}

internal enum class HciConnectionErrorCode {
    COMMAND_BUSY_OR_SEND_FAILED,
    COMMAND_FAILED,
    CONNECTION_FAILED,
    MALFORMED_EVENT,
    TIMEOUT,
    CANCEL_FAILED,
    DISCONNECT_FAILED
}

internal data class HciConnectionFailure(
    val code: HciConnectionErrorCode,
    val opcode: Int? = null,
    val controllerStatus: Int? = null
)

internal data class HciAclLink(
    val address: HciBluetoothAddress,
    val connectionHandle: Int,
    val encrypted: Boolean,
    val initiatedByRemote: Boolean = false
)

internal data class HciConnectionRequest(
    val address: HciBluetoothAddress,
    val classOfDevice: Int,
    val linkType: Int
) {
    val isAcl: Boolean
        get() = linkType == 0x01
}

internal interface HciConnectionListener {
    fun onConnected(link: HciAclLink)
    fun onDisconnected(link: HciAclLink, reason: Int)
    fun onConnectionCancelled()
    fun onConnectionFailure(failure: HciConnectionFailure)
}

/** Owns one outgoing BR/EDR ACL connection attempt and its eventual disconnect event. */
internal class HciConnectionController(
    private val device: HciDiscoveredDevice,
    private val submitCommand: (HciCommandPacket, (HciCommandResult) -> Unit) -> Boolean,
    private val listener: HciConnectionListener,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS,
    private val incomingRequest: HciConnectionRequest? = null
) {
    @Volatile
    var state = HciConnectionState.IDLE
        private set

    @Volatile
    var link: HciAclLink? = null
        private set

    @Volatile
    var failure: HciConnectionFailure? = null
        private set

    private var deadlineMs = 0L
    private var completionIntent = CompletionIntent.CONNECT

    init {
        require(connectionTimeoutMs > 0)
    }

    @Synchronized
    fun start(): Boolean {
        if (state != HciConnectionState.IDLE) {
            return state == HciConnectionState.STARTING || state == HciConnectionState.CONNECTING
        }
        state = HciConnectionState.STARTING
        if (incomingRequest != null) {
            if (!incomingRequest.isAcl || incomingRequest.address != device.address) {
                fail(HciConnectionErrorCode.MALFORMED_EVENT)
                return false
            }
            val parameters = device.address.toLittleEndianByteArray() +
                byteArrayOf(REMAIN_PERIPHERAL_ROLE)
            if (submitCommand(
                    HciCommandPacket(HciOpcodes.ACCEPT_CONNECTION_REQUEST, parameters),
                    ::onStartConnectionStatus
                )
            ) {
                return true
            }
            fail(
                HciConnectionErrorCode.COMMAND_BUSY_OR_SEND_FAILED,
                HciOpcodes.ACCEPT_CONNECTION_REQUEST
            )
            return false
        }
        val clockOffset = device.clockOffset or CLOCK_OFFSET_VALID_FLAG
        val parameters = device.address.toLittleEndianByteArray() + byteArrayOf(
            ACL_PACKET_TYPES.toByte(),
            (ACL_PACKET_TYPES ushr 8).toByte(),
            device.pageScanRepetitionMode.toByte(),
            0x00,
            clockOffset.toByte(),
            (clockOffset ushr 8).toByte(),
            ALLOW_ROLE_SWITCH.toByte()
        )
        if (submitCommand(
                HciCommandPacket(HciOpcodes.CREATE_CONNECTION, parameters),
                ::onStartConnectionStatus
            )
        ) {
            return true
        }
        fail(HciConnectionErrorCode.COMMAND_BUSY_OR_SEND_FAILED, HciOpcodes.CREATE_CONNECTION)
        return false
    }

    @Synchronized
    fun onEvent(event: HciEventPacket): Boolean {
        return when (event.eventCode) {
            CONNECTION_COMPLETE_EVENT_CODE -> handleConnectionComplete(event)
            DISCONNECTION_COMPLETE_EVENT_CODE -> handleDisconnectionComplete(event)
            else -> false
        }
    }

    @Synchronized
    fun cancel(): Boolean {
        return when (state) {
            HciConnectionState.STARTING -> {
                completionIntent = CompletionIntent.CANCEL
                true
            }
            HciConnectionState.CONNECTING -> submitCreateConnectionCancel(CompletionIntent.CANCEL)
            HciConnectionState.CONNECTED -> submitDisconnect(CompletionIntent.DISCONNECT)
            HciConnectionState.CANCELLING,
            HciConnectionState.DISCONNECTING -> true
            HciConnectionState.IDLE -> finishCancelled()
            else -> false
        }
    }

    @Synchronized
    fun checkTimeout(nowMs: Long = monotonicTimeMs()): Boolean {
        if (deadlineMs == 0L || state.isTerminal() || nowMs < deadlineMs) return false
        when (state) {
            HciConnectionState.CONNECTING -> {
                if (incomingRequest != null) {
                    fail(HciConnectionErrorCode.TIMEOUT, HciOpcodes.ACCEPT_CONNECTION_REQUEST)
                } else {
                    submitCreateConnectionCancel(CompletionIntent.TIMEOUT)
                }
            }
            HciConnectionState.CANCELLING -> fail(
                if (completionIntent == CompletionIntent.TIMEOUT) {
                    HciConnectionErrorCode.TIMEOUT
                } else {
                    HciConnectionErrorCode.CANCEL_FAILED
                },
                HciOpcodes.CREATE_CONNECTION_CANCEL
            )
            HciConnectionState.DISCONNECTING -> fail(
                HciConnectionErrorCode.DISCONNECT_FAILED,
                HciOpcodes.DISCONNECT
            )
            else -> return false
        }
        return true
    }

    fun blocksCommandSubmission(): Boolean {
        return state == HciConnectionState.STARTING ||
            state == HciConnectionState.CONNECTING ||
            state == HciConnectionState.CANCELLING ||
            state == HciConnectionState.DISCONNECTING
    }

    @Synchronized
    fun markEncrypted(connectionHandle: Int): Boolean {
        val current = link ?: return false
        if (state != HciConnectionState.CONNECTED ||
            current.connectionHandle != connectionHandle
        ) {
            return false
        }
        link = current.copy(encrypted = true)
        return true
    }

    private fun onStartConnectionStatus(result: HciCommandResult) {
        synchronized(this) {
            if (state != HciConnectionState.STARTING) return
            val opcode = if (incomingRequest != null) {
                HciOpcodes.ACCEPT_CONNECTION_REQUEST
            } else {
                HciOpcodes.CREATE_CONNECTION
            }
            if (result is HciCommandResult.Failed) {
                fail(HciConnectionErrorCode.TIMEOUT, opcode)
                return
            }
            val status = result.commandStatus(opcode)
            if (status == null) {
                fail(HciConnectionErrorCode.MALFORMED_EVENT, opcode)
                return
            }
            if (status != SUCCESS_STATUS) {
                fail(HciConnectionErrorCode.COMMAND_FAILED, opcode, status)
                return
            }
            state = HciConnectionState.CONNECTING
            deadlineMs = monotonicTimeMs() + connectionTimeoutMs
            if (completionIntent == CompletionIntent.CANCEL) {
                if (incomingRequest == null) {
                    submitCreateConnectionCancel(CompletionIntent.CANCEL)
                }
            }
        }
    }

    private fun handleConnectionComplete(event: HciEventPacket): Boolean {
        if (state != HciConnectionState.CONNECTING && state != HciConnectionState.CANCELLING) {
            return false
        }
        val result = HciConnectionEventCodec.decodeConnectionComplete(event.parameters) ?: run {
            fail(HciConnectionErrorCode.MALFORMED_EVENT)
            return true
        }
        if (result.address != device.address) return false

        deadlineMs = 0L
        if (result.status != SUCCESS_STATUS) {
            if (state == HciConnectionState.CANCELLING &&
                result.status == UNKNOWN_CONNECTION_IDENTIFIER
            ) {
                finishPendingCancellation()
            } else {
                fail(HciConnectionErrorCode.CONNECTION_FAILED, controllerStatus = result.status)
            }
            return true
        }
        if (result.linkType != ACL_LINK_TYPE || result.encryptionEnabled !in 0..1) {
            fail(HciConnectionErrorCode.MALFORMED_EVENT)
            return true
        }

        link = HciAclLink(
            result.address,
            result.connectionHandle,
            result.encryptionEnabled == 1,
            initiatedByRemote = incomingRequest != null
        )
        if (state == HciConnectionState.CANCELLING || completionIntent != CompletionIntent.CONNECT) {
            submitDisconnect(completionIntent)
            return true
        }
        state = HciConnectionState.CONNECTED
        runCatching { listener.onConnected(link!!) }
        return true
    }

    private fun handleDisconnectionComplete(event: HciEventPacket): Boolean {
        val currentLink = link ?: return false
        if (state != HciConnectionState.CONNECTED && state != HciConnectionState.DISCONNECTING) {
            return false
        }
        val result = HciConnectionEventCodec.decodeDisconnectionComplete(event.parameters) ?: run {
            fail(HciConnectionErrorCode.MALFORMED_EVENT)
            return true
        }
        if (result.connectionHandle != currentLink.connectionHandle) return false
        if (result.status != SUCCESS_STATUS) {
            fail(HciConnectionErrorCode.DISCONNECT_FAILED, controllerStatus = result.status)
            return true
        }

        deadlineMs = 0L
        when (completionIntent) {
            CompletionIntent.CANCEL -> finishCancelled()
            CompletionIntent.TIMEOUT -> fail(HciConnectionErrorCode.TIMEOUT)
            else -> {
                state = HciConnectionState.DISCONNECTED
                runCatching { listener.onDisconnected(currentLink, result.reason) }
            }
        }
        return true
    }

    private fun submitCreateConnectionCancel(intent: CompletionIntent): Boolean {
        if (incomingRequest != null) {
            completionIntent = intent
            return true
        }
        completionIntent = intent
        state = HciConnectionState.CANCELLING
        deadlineMs = monotonicTimeMs() + CANCEL_TIMEOUT_MS
        if (submitCommand(
                HciCommandPacket(
                    HciOpcodes.CREATE_CONNECTION_CANCEL,
                    device.address.toLittleEndianByteArray()
                ),
                ::onCreateConnectionCancelComplete
            )
        ) {
            return true
        }
        fail(HciConnectionErrorCode.CANCEL_FAILED, HciOpcodes.CREATE_CONNECTION_CANCEL)
        return false
    }

    private fun onCreateConnectionCancelComplete(result: HciCommandResult) {
        synchronized(this) {
            if (state != HciConnectionState.CANCELLING) return
            if (result is HciCommandResult.Failed) {
                fail(HciConnectionErrorCode.TIMEOUT, HciOpcodes.CREATE_CONNECTION_CANCEL)
                return
            }
            result as HciCommandResult.Completed
            val address = if (result.returnParameters.size == CREATE_CONNECTION_CANCEL_RESPONSE_LENGTH) {
                HciBluetoothAddress.fromLittleEndian(result.returnParameters, 1)
            } else {
                null
            }
            if (result.opcode != HciOpcodes.CREATE_CONNECTION_CANCEL ||
                result.type != HciCommandCompletionType.COMMAND_COMPLETE ||
                result.controllerStatus == null ||
                address != device.address
            ) {
                fail(HciConnectionErrorCode.MALFORMED_EVENT, HciOpcodes.CREATE_CONNECTION_CANCEL)
                return
            }
            if (result.controllerStatus != SUCCESS_STATUS) {
                fail(
                    HciConnectionErrorCode.CANCEL_FAILED,
                    HciOpcodes.CREATE_CONNECTION_CANCEL,
                    result.controllerStatus
                )
                return
            }
            // The matching Connection Complete is mandatory and follows this Command Complete.
            deadlineMs = monotonicTimeMs() + CANCEL_COMPLETION_TIMEOUT_MS
        }
    }

    private fun submitDisconnect(intent: CompletionIntent): Boolean {
        val currentLink = link ?: return false
        completionIntent = intent
        state = HciConnectionState.DISCONNECTING
        deadlineMs = monotonicTimeMs() + DISCONNECT_TIMEOUT_MS
        val parameters = byteArrayOf(
            currentLink.connectionHandle.toByte(),
            (currentLink.connectionHandle ushr 8).toByte(),
            REMOTE_USER_TERMINATED_CONNECTION.toByte()
        )
        if (submitCommand(
                HciCommandPacket(HciOpcodes.DISCONNECT, parameters),
                ::onDisconnectStatus
            )
        ) {
            return true
        }
        fail(HciConnectionErrorCode.DISCONNECT_FAILED, HciOpcodes.DISCONNECT)
        return false
    }

    private fun onDisconnectStatus(result: HciCommandResult) {
        synchronized(this) {
            if (state != HciConnectionState.DISCONNECTING) return
            if (result is HciCommandResult.Failed) {
                fail(HciConnectionErrorCode.TIMEOUT, HciOpcodes.DISCONNECT)
                return
            }
            val status = result.commandStatus(HciOpcodes.DISCONNECT)
            if (status == null) {
                fail(HciConnectionErrorCode.MALFORMED_EVENT, HciOpcodes.DISCONNECT)
            } else if (status != SUCCESS_STATUS) {
                fail(HciConnectionErrorCode.DISCONNECT_FAILED, HciOpcodes.DISCONNECT, status)
            } else {
                deadlineMs = monotonicTimeMs() + DISCONNECT_TIMEOUT_MS
            }
        }
    }

    private fun finishPendingCancellation() {
        if (completionIntent == CompletionIntent.TIMEOUT) {
            fail(HciConnectionErrorCode.TIMEOUT)
        } else {
            finishCancelled()
        }
    }

    private fun finishCancelled(): Boolean {
        state = HciConnectionState.CANCELLED
        deadlineMs = 0L
        runCatching { listener.onConnectionCancelled() }
        return true
    }

    private fun fail(
        code: HciConnectionErrorCode,
        opcode: Int? = null,
        controllerStatus: Int? = null
    ) {
        failure = HciConnectionFailure(code, opcode, controllerStatus)
        state = HciConnectionState.FAILED
        deadlineMs = 0L
        runCatching { listener.onConnectionFailure(failure!!) }
    }

    private fun HciCommandResult.commandStatus(expectedOpcode: Int): Int? {
        val completed = this as? HciCommandResult.Completed ?: return null
        if (completed.opcode != expectedOpcode ||
            completed.type != HciCommandCompletionType.COMMAND_STATUS
        ) {
            return null
        }
        return completed.controllerStatus
    }

    private fun HciConnectionState.isTerminal(): Boolean {
        return this == HciConnectionState.DISCONNECTED ||
            this == HciConnectionState.CANCELLED ||
            this == HciConnectionState.FAILED
    }

    private enum class CompletionIntent {
        CONNECT,
        CANCEL,
        TIMEOUT,
        DISCONNECT
    }

    companion object {
        // Match the proven DualSense bridge path and common desktop Bluetooth hosts: allow
        // BR/EDR 1-, 3-, and 5-slot DM/DH packets. Restricting this to DM1/DH1 needlessly
        // throttles the controller's 78-byte extended reports and outbound feedback.
        private const val ACL_PACKET_TYPES = 0xcc18
        private const val CLOCK_OFFSET_VALID_FLAG = 0x8000
        private const val ALLOW_ROLE_SWITCH = 0x01
        // Keep the controller as BR/EDR master on controller-initiated reconnects. This matches
        // the proven DualSense bridge path and avoids changing the controller's link schedule.
        private const val REMAIN_PERIPHERAL_ROLE: Byte = 0x01
        private const val REMOTE_USER_TERMINATED_CONNECTION = 0x13
        private const val ACL_LINK_TYPE = 0x01
        private const val SUCCESS_STATUS = 0x00
        private const val UNKNOWN_CONNECTION_IDENTIFIER = 0x02
        private const val CONNECTION_COMPLETE_EVENT_CODE = 0x03
        private const val DISCONNECTION_COMPLETE_EVENT_CODE = 0x05
        private const val CREATE_CONNECTION_CANCEL_RESPONSE_LENGTH = 7
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 15_000L
        private const val CANCEL_TIMEOUT_MS = 3_000L
        private const val CANCEL_COMPLETION_TIMEOUT_MS = 5_000L
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
    }
}

internal data class HciConnectionCompleteResult(
    val status: Int,
    val connectionHandle: Int,
    val address: HciBluetoothAddress,
    val linkType: Int,
    val encryptionEnabled: Int
)

internal data class HciDisconnectionCompleteResult(
    val status: Int,
    val connectionHandle: Int,
    val reason: Int
)

internal object HciConnectionEventCodec {
    fun decodeConnectionRequest(parameters: ByteArray): HciConnectionRequest? {
        if (parameters.size != CONNECTION_REQUEST_PARAMETER_LENGTH) return null
        val address = HciBluetoothAddress.fromLittleEndian(parameters, 0) ?: return null
        val classOfDevice = (parameters[6].toInt() and 0xff) or
            ((parameters[7].toInt() and 0xff) shl 8) or
            ((parameters[8].toInt() and 0xff) shl 16)
        return HciConnectionRequest(
            address = address,
            classOfDevice = classOfDevice,
            linkType = parameters[9].toInt() and 0xff
        )
    }

    fun decodeConnectionComplete(parameters: ByteArray): HciConnectionCompleteResult? {
        if (parameters.size != CONNECTION_COMPLETE_PARAMETER_LENGTH) return null
        val address = HciBluetoothAddress.fromLittleEndian(parameters, 3) ?: return null
        val handle = HciPacketCodec.littleEndianUnsignedShort(parameters, 1)
        if (handle !in 0x0000..MAX_CONNECTION_HANDLE) return null
        return HciConnectionCompleteResult(
            status = parameters[0].toInt() and 0xff,
            connectionHandle = handle,
            address = address,
            linkType = parameters[9].toInt() and 0xff,
            encryptionEnabled = parameters[10].toInt() and 0xff
        )
    }

    fun decodeDisconnectionComplete(parameters: ByteArray): HciDisconnectionCompleteResult? {
        if (parameters.size != DISCONNECTION_COMPLETE_PARAMETER_LENGTH) return null
        val handle = HciPacketCodec.littleEndianUnsignedShort(parameters, 1)
        if (handle !in 0x0000..MAX_CONNECTION_HANDLE) return null
        return HciDisconnectionCompleteResult(
            status = parameters[0].toInt() and 0xff,
            connectionHandle = handle,
            reason = parameters[3].toInt() and 0xff
        )
    }

    private const val CONNECTION_COMPLETE_PARAMETER_LENGTH = 11
    private const val CONNECTION_REQUEST_PARAMETER_LENGTH = 10
    private const val DISCONNECTION_COMPLETE_PARAMETER_LENGTH = 4
    private const val MAX_CONNECTION_HANDLE = 0x0eff
}
