package com.limelight.binding.input.driver.wireless.hci

internal class HciLinkKey(key: ByteArray, val type: Int) {
    private val keyBytes: ByteArray = key.copyOf()
    val value: ByteArray
        get() = keyBytes.copyOf()

    init {
        require(keyBytes.size == LINK_KEY_LENGTH)
        require(type in 0x00..0x08)
    }

    companion object {
        const val LINK_KEY_LENGTH = 16
    }
}

internal interface HciLinkKeyStore {
    fun load(address: HciBluetoothAddress): HciLinkKey?
    fun save(address: HciBluetoothAddress, key: HciLinkKey)
    fun remove(address: HciBluetoothAddress)
}

internal enum class HciSecurityState {
    IDLE,
    STARTING_AUTHENTICATION,
    AUTHENTICATING,
    ENABLING_ENCRYPTION,
    ENCRYPTED,
    FAILED
}

internal enum class HciSecurityErrorCode {
    COMMAND_BUSY_OR_SEND_FAILED,
    COMMAND_FAILED,
    AUTHENTICATION_FAILED,
    ENCRYPTION_FAILED,
    MALFORMED_EVENT,
    KEY_STORE_FAILED,
    TIMEOUT,
    LINK_DISCONNECTED
}

internal data class HciSecurityFailure(
    val code: HciSecurityErrorCode,
    val opcode: Int? = null,
    val controllerStatus: Int? = null
)

internal interface HciSecurityListener {
    fun onLinkEncrypted(link: HciAclLink)
    fun onSecurityFailure(failure: HciSecurityFailure)
}

/** Performs BR/EDR SSP authentication, host-side Link Key handling, and encryption enablement. */
internal class HciSecurityController(
    private val link: HciAclLink,
    private val keyStore: HciLinkKeyStore,
    private val submitCommand: (HciCommandPacket, (HciCommandResult) -> Unit) -> Boolean,
    private val listener: HciSecurityListener,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val authenticationTimeoutMs: Long = DEFAULT_AUTHENTICATION_TIMEOUT_MS
) {
    @Volatile
    var state = HciSecurityState.IDLE
        private set

    @Volatile
    var failure: HciSecurityFailure? = null
        private set

    private var deadlineMs = 0L
    private var suppliedStoredKey = false
    private var retriedWithoutStoredKey = false

    init {
        require(authenticationTimeoutMs > 0)
    }

    @Synchronized
    fun start(): Boolean {
        if (state != HciSecurityState.IDLE) return state != HciSecurityState.FAILED
        return submitAuthentication()
    }

    @Synchronized
    fun onEvent(event: HciEventPacket): Boolean {
        if (state != HciSecurityState.AUTHENTICATING &&
            state != HciSecurityState.ENABLING_ENCRYPTION
        ) {
            return false
        }
        return when (event.eventCode) {
            AUTHENTICATION_COMPLETE_EVENT_CODE -> handleAuthenticationComplete(event.parameters)
            ENCRYPTION_CHANGE_EVENT_CODE -> handleEncryptionChange(event.parameters)
            PIN_CODE_REQUEST_EVENT_CODE -> handlePinCodeRequest(event.parameters)
            LINK_KEY_REQUEST_EVENT_CODE -> handleLinkKeyRequest(event.parameters)
            LINK_KEY_NOTIFICATION_EVENT_CODE -> handleLinkKeyNotification(event.parameters)
            IO_CAPABILITY_REQUEST_EVENT_CODE -> handleIoCapabilityRequest(event.parameters)
            USER_CONFIRMATION_REQUEST_EVENT_CODE -> handleUserConfirmationRequest(event.parameters)
            USER_PASSKEY_REQUEST_EVENT_CODE -> handleAddressRequest(
                event.parameters,
                HciOpcodes.USER_PASSKEY_REQUEST_NEGATIVE_REPLY
            )
            REMOTE_OOB_DATA_REQUEST_EVENT_CODE -> handleAddressRequest(
                event.parameters,
                HciOpcodes.REMOTE_OOB_DATA_REQUEST_NEGATIVE_REPLY
            )
            SIMPLE_PAIRING_COMPLETE_EVENT_CODE -> handleSimplePairingComplete(event.parameters)
            else -> false
        }
    }

    @Synchronized
    fun checkTimeout(nowMs: Long = monotonicTimeMs()): Boolean {
        if (deadlineMs == 0L || state.isTerminal() || nowMs < deadlineMs) return false
        fail(HciSecurityErrorCode.TIMEOUT)
        return true
    }

    @Synchronized
    fun onLinkDisconnected(connectionHandle: Int): Boolean {
        if (connectionHandle != link.connectionHandle || state.isTerminal()) return false
        fail(HciSecurityErrorCode.LINK_DISCONNECTED)
        return true
    }

    private fun submitAuthentication(): Boolean {
        state = HciSecurityState.STARTING_AUTHENTICATION
        val parameters = connectionHandleParameters()
        if (submitCommand(
                HciCommandPacket(HciOpcodes.AUTHENTICATION_REQUESTED, parameters),
                ::onAuthenticationStatus
            )
        ) {
            return true
        }
        fail(
            HciSecurityErrorCode.COMMAND_BUSY_OR_SEND_FAILED,
            HciOpcodes.AUTHENTICATION_REQUESTED
        )
        return false
    }

    private fun onAuthenticationStatus(result: HciCommandResult) {
        synchronized(this) {
            if (state != HciSecurityState.STARTING_AUTHENTICATION) return
            val status = commandStatus(result, HciOpcodes.AUTHENTICATION_REQUESTED) ?: run {
                fail(
                    if (result is HciCommandResult.Failed) {
                        HciSecurityErrorCode.TIMEOUT
                    } else {
                        HciSecurityErrorCode.MALFORMED_EVENT
                    },
                    HciOpcodes.AUTHENTICATION_REQUESTED
                )
                return
            }
            if (status != SUCCESS_STATUS) {
                fail(
                    HciSecurityErrorCode.COMMAND_FAILED,
                    HciOpcodes.AUTHENTICATION_REQUESTED,
                    status
                )
                return
            }
            state = HciSecurityState.AUTHENTICATING
            deadlineMs = monotonicTimeMs() + authenticationTimeoutMs
        }
    }

    private fun handleLinkKeyRequest(parameters: ByteArray): Boolean {
        val address = decodeAddressOnly(parameters) ?: return malformedAddressEvent(parameters)
        if (address != link.address) return false

        val stored = if (retriedWithoutStoredKey) {
            null
        } else {
            runCatching { keyStore.load(address) }.getOrElse {
                fail(HciSecurityErrorCode.KEY_STORE_FAILED)
                return true
            }
        }
        suppliedStoredKey = stored != null
        val opcode: Int
        val commandParameters: ByteArray
        if (stored != null) {
            opcode = HciOpcodes.LINK_KEY_REQUEST_REPLY
            commandParameters = address.toLittleEndianByteArray() + stored.value
        } else {
            opcode = HciOpcodes.LINK_KEY_REQUEST_NEGATIVE_REPLY
            commandParameters = address.toLittleEndianByteArray()
        }
        submitAddressReply(opcode, commandParameters)
        return true
    }

    private fun handleLinkKeyNotification(parameters: ByteArray): Boolean {
        if (parameters.size != LINK_KEY_NOTIFICATION_LENGTH) {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        val address = HciBluetoothAddress.fromLittleEndian(parameters, 0) ?: run {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        if (address != link.address) return false
        val keyBytes = parameters.copyOfRange(6, 22)
        val keyType = parameters[22].toInt() and 0xff
        if (keyType !in 0x00..0x08 || keyBytes.all { it == 0.toByte() }) {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        val key = HciLinkKey(keyBytes, keyType)
        if (runCatching { keyStore.save(address, key) }.isFailure) {
            fail(HciSecurityErrorCode.KEY_STORE_FAILED)
        }
        return true
    }

    private fun handleIoCapabilityRequest(parameters: ByteArray): Boolean {
        val address = decodeAddressOnly(parameters) ?: return malformedAddressEvent(parameters)
        if (address != link.address) return false
        submitAddressReply(
            HciOpcodes.IO_CAPABILITY_REQUEST_REPLY,
            address.toLittleEndianByteArray() + byteArrayOf(
                IO_CAPABILITY_NO_INPUT_NO_OUTPUT,
                OOB_DATA_NOT_PRESENT,
                GENERAL_BONDING_NO_MITM
            )
        )
        return true
    }

    private fun handleUserConfirmationRequest(parameters: ByteArray): Boolean {
        if (parameters.size != USER_CONFIRMATION_REQUEST_LENGTH) {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        val address = HciBluetoothAddress.fromLittleEndian(parameters, 0) ?: run {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        if (address != link.address) return false
        submitAddressReply(
            HciOpcodes.USER_CONFIRMATION_REQUEST_REPLY,
            address.toLittleEndianByteArray()
        )
        return true
    }

    private fun handlePinCodeRequest(parameters: ByteArray): Boolean {
        val address = decodeAddressOnly(parameters) ?: return malformedAddressEvent(parameters)
        if (address != link.address) return false
        val pin = byteArrayOf(
            '0'.code.toByte(),
            '0'.code.toByte(),
            '0'.code.toByte(),
            '0'.code.toByte()
        )
        submitAddressReply(
            HciOpcodes.PIN_CODE_REQUEST_REPLY,
            address.toLittleEndianByteArray() + byteArrayOf(pin.size.toByte()) +
                pin.copyOf(PIN_CODE_FIELD_LENGTH)
        )
        return true
    }

    private fun handleSimplePairingComplete(parameters: ByteArray): Boolean {
        if (parameters.size != STATUS_AND_ADDRESS_LENGTH) {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        val address = HciBluetoothAddress.fromLittleEndian(parameters, 1) ?: run {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        if (address != link.address) return false
        val status = parameters[0].toInt() and 0xff
        if (status != SUCCESS_STATUS) {
            fail(HciSecurityErrorCode.AUTHENTICATION_FAILED, controllerStatus = status)
        }
        return true
    }

    private fun handleAuthenticationComplete(parameters: ByteArray): Boolean {
        val result = decodeStatusAndHandle(parameters) ?: run {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        if (result.second != link.connectionHandle) return false
        if (result.first != SUCCESS_STATUS) {
            if (suppliedStoredKey && !retriedWithoutStoredKey) {
                if (runCatching { keyStore.remove(link.address) }.isFailure) {
                    fail(HciSecurityErrorCode.KEY_STORE_FAILED)
                    return true
                }
                suppliedStoredKey = false
                retriedWithoutStoredKey = true
                deadlineMs = 0L
                submitAuthentication()
            } else {
                fail(
                    HciSecurityErrorCode.AUTHENTICATION_FAILED,
                    controllerStatus = result.first
                )
            }
            return true
        }

        state = HciSecurityState.ENABLING_ENCRYPTION
        deadlineMs = monotonicTimeMs() + ENCRYPTION_TIMEOUT_MS
        val commandParameters = connectionHandleParameters() + byteArrayOf(ENCRYPTION_ENABLED)
        if (!submitCommand(
                HciCommandPacket(HciOpcodes.SET_CONNECTION_ENCRYPTION, commandParameters),
                ::onEncryptionStatus
            )
        ) {
            fail(
                HciSecurityErrorCode.COMMAND_BUSY_OR_SEND_FAILED,
                HciOpcodes.SET_CONNECTION_ENCRYPTION
            )
        }
        return true
    }

    private fun onEncryptionStatus(result: HciCommandResult) {
        synchronized(this) {
            if (state != HciSecurityState.ENABLING_ENCRYPTION) return
            val status = commandStatus(result, HciOpcodes.SET_CONNECTION_ENCRYPTION) ?: run {
                fail(
                    if (result is HciCommandResult.Failed) {
                        HciSecurityErrorCode.TIMEOUT
                    } else {
                        HciSecurityErrorCode.MALFORMED_EVENT
                    },
                    HciOpcodes.SET_CONNECTION_ENCRYPTION
                )
                return
            }
            if (status != SUCCESS_STATUS) {
                fail(
                    HciSecurityErrorCode.ENCRYPTION_FAILED,
                    HciOpcodes.SET_CONNECTION_ENCRYPTION,
                    status
                )
            } else {
                deadlineMs = monotonicTimeMs() + ENCRYPTION_TIMEOUT_MS
            }
        }
    }

    private fun handleEncryptionChange(parameters: ByteArray): Boolean {
        if (state != HciSecurityState.ENABLING_ENCRYPTION) return false
        if (parameters.size != ENCRYPTION_CHANGE_LENGTH) {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        val handle = HciPacketCodec.littleEndianUnsignedShort(parameters, 1)
        if (handle != link.connectionHandle) return false
        val status = parameters[0].toInt() and 0xff
        val enabled = parameters[3].toInt() and 0xff
        if (status != SUCCESS_STATUS || enabled !in VALID_ENCRYPTION_MODES) {
            fail(HciSecurityErrorCode.ENCRYPTION_FAILED, controllerStatus = status)
            return true
        }
        state = HciSecurityState.ENCRYPTED
        deadlineMs = 0L
        runCatching { listener.onLinkEncrypted(link.copy(encrypted = true)) }
        return true
    }

    private fun handleAddressRequest(parameters: ByteArray, opcode: Int): Boolean {
        val address = decodeAddressOnly(parameters) ?: return malformedAddressEvent(parameters)
        if (address != link.address) return false
        submitAddressReply(opcode, address.toLittleEndianByteArray())
        return true
    }

    private fun submitAddressReply(opcode: Int, parameters: ByteArray) {
        if (!submitCommand(HciCommandPacket(opcode, parameters)) { result ->
                onAddressReplyComplete(opcode, result)
            }
        ) {
            fail(HciSecurityErrorCode.COMMAND_BUSY_OR_SEND_FAILED, opcode)
        }
    }

    private fun onAddressReplyComplete(opcode: Int, result: HciCommandResult) {
        synchronized(this) {
            if (state != HciSecurityState.AUTHENTICATING) return
            if (result is HciCommandResult.Failed) {
                fail(HciSecurityErrorCode.TIMEOUT, opcode)
                return
            }
            result as HciCommandResult.Completed
            val address = if (result.returnParameters.size == STATUS_AND_ADDRESS_LENGTH) {
                HciBluetoothAddress.fromLittleEndian(result.returnParameters, 1)
            } else {
                null
            }
            if (result.opcode != opcode ||
                result.type != HciCommandCompletionType.COMMAND_COMPLETE ||
                result.controllerStatus == null ||
                address != link.address
            ) {
                fail(HciSecurityErrorCode.MALFORMED_EVENT, opcode)
            } else if (result.controllerStatus != SUCCESS_STATUS) {
                fail(HciSecurityErrorCode.COMMAND_FAILED, opcode, result.controllerStatus)
            }
        }
    }

    private fun decodeAddressOnly(parameters: ByteArray): HciBluetoothAddress? {
        if (parameters.size != ADDRESS_LENGTH) return null
        return HciBluetoothAddress.fromLittleEndian(parameters, 0)
    }

    private fun malformedAddressEvent(parameters: ByteArray): Boolean {
        if (parameters.size != ADDRESS_LENGTH ||
            HciBluetoothAddress.fromLittleEndian(parameters, 0) == null
        ) {
            fail(HciSecurityErrorCode.MALFORMED_EVENT)
            return true
        }
        return false
    }

    private fun decodeStatusAndHandle(parameters: ByteArray): Pair<Int, Int>? {
        if (parameters.size != STATUS_AND_HANDLE_LENGTH) return null
        val handle = HciPacketCodec.littleEndianUnsignedShort(parameters, 1)
        if (handle !in 0x0000..MAX_CONNECTION_HANDLE) return null
        return Pair(parameters[0].toInt() and 0xff, handle)
    }

    private fun commandStatus(result: HciCommandResult, expectedOpcode: Int): Int? {
        val completed = result as? HciCommandResult.Completed ?: return null
        if (completed.opcode != expectedOpcode ||
            completed.type != HciCommandCompletionType.COMMAND_STATUS
        ) {
            return null
        }
        return completed.controllerStatus
    }

    private fun connectionHandleParameters(): ByteArray {
        return byteArrayOf(
            link.connectionHandle.toByte(),
            (link.connectionHandle ushr 8).toByte()
        )
    }

    private fun fail(
        code: HciSecurityErrorCode,
        opcode: Int? = null,
        controllerStatus: Int? = null
    ) {
        failure = HciSecurityFailure(code, opcode, controllerStatus)
        state = HciSecurityState.FAILED
        deadlineMs = 0L
        runCatching { listener.onSecurityFailure(failure!!) }
    }

    private fun HciSecurityState.isTerminal(): Boolean {
        return this == HciSecurityState.ENCRYPTED || this == HciSecurityState.FAILED
    }

    companion object {
        private const val SUCCESS_STATUS = 0x00
        private const val ENCRYPTION_ENABLED: Byte = 0x01
        private val VALID_ENCRYPTION_MODES = setOf(0x01, 0x02)
        private const val IO_CAPABILITY_NO_INPUT_NO_OUTPUT: Byte = 0x03
        private const val OOB_DATA_NOT_PRESENT: Byte = 0x00
        private const val GENERAL_BONDING_NO_MITM: Byte = 0x04
        private const val ADDRESS_LENGTH = 6
        private const val STATUS_AND_ADDRESS_LENGTH = 7
        private const val STATUS_AND_HANDLE_LENGTH = 3
        private const val LINK_KEY_NOTIFICATION_LENGTH = 23
        private const val USER_CONFIRMATION_REQUEST_LENGTH = 10
        private const val PIN_CODE_FIELD_LENGTH = 16
        private const val ENCRYPTION_CHANGE_LENGTH = 4
        private const val MAX_CONNECTION_HANDLE = 0x0eff
        private const val DEFAULT_AUTHENTICATION_TIMEOUT_MS = 30_000L
        private const val ENCRYPTION_TIMEOUT_MS = 10_000L

        private const val AUTHENTICATION_COMPLETE_EVENT_CODE = 0x06
        private const val ENCRYPTION_CHANGE_EVENT_CODE = 0x08
        private const val PIN_CODE_REQUEST_EVENT_CODE = 0x16
        private const val LINK_KEY_REQUEST_EVENT_CODE = 0x17
        private const val LINK_KEY_NOTIFICATION_EVENT_CODE = 0x18
        private const val IO_CAPABILITY_REQUEST_EVENT_CODE = 0x31
        private const val USER_CONFIRMATION_REQUEST_EVENT_CODE = 0x33
        private const val USER_PASSKEY_REQUEST_EVENT_CODE = 0x34
        private const val REMOTE_OOB_DATA_REQUEST_EVENT_CODE = 0x35
        private const val SIMPLE_PAIRING_COMPLETE_EVENT_CODE = 0x36
    }
}
