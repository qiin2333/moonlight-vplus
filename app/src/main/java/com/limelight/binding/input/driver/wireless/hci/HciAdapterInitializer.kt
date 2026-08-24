package com.limelight.binding.input.driver.wireless.hci

internal object HciOpcodes {
    const val INQUIRY = 0x0401
    const val INQUIRY_CANCEL = 0x0402
    const val CREATE_CONNECTION = 0x0405
    const val DISCONNECT = 0x0406
    const val CREATE_CONNECTION_CANCEL = 0x0408
    const val ACCEPT_CONNECTION_REQUEST = 0x0409
    const val LINK_KEY_REQUEST_REPLY = 0x040b
    const val LINK_KEY_REQUEST_NEGATIVE_REPLY = 0x040c
    const val PIN_CODE_REQUEST_REPLY = 0x040d
    const val PIN_CODE_REQUEST_NEGATIVE_REPLY = 0x040e
    const val AUTHENTICATION_REQUESTED = 0x0411
    const val SET_CONNECTION_ENCRYPTION = 0x0413
    const val EXIT_SNIFF_MODE = 0x0804
    const val WRITE_LINK_POLICY_SETTINGS = 0x080d
    const val REMOTE_NAME_REQUEST = 0x0419
    const val REMOTE_NAME_REQUEST_CANCEL = 0x041a
    const val IO_CAPABILITY_REQUEST_REPLY = 0x042b
    const val USER_CONFIRMATION_REQUEST_REPLY = 0x042c
    const val USER_PASSKEY_REQUEST_NEGATIVE_REPLY = 0x042f
    const val REMOTE_OOB_DATA_REQUEST_NEGATIVE_REPLY = 0x0433
    const val SET_EVENT_MASK = 0x0c01
    const val RESET = 0x0c03
    const val WRITE_SCAN_ENABLE = 0x0c1a
    const val WRITE_LINK_SUPERVISION_TIMEOUT = 0x0c37
    const val READ_LOCAL_VERSION = 0x1001
    const val READ_BUFFER_SIZE = 0x1005
    const val READ_BD_ADDR = 0x1009
    const val WRITE_SIMPLE_PAIRING_MODE = 0x0c56
}

internal data class HciLocalVersionInfo(
    val hciVersion: Int,
    val hciRevision: Int,
    val lmpVersion: Int,
    val manufacturerId: Int,
    val lmpSubversion: Int
)

internal data class HciBluetoothAddress(val value: Long) {
    init {
        require(value in 0..MAX_VALUE)
    }

    override fun toString(): String {
        return (5 downTo 0).joinToString(":") { byteIndex ->
            ((value ushr (byteIndex * 8)) and 0xff).toString(16).uppercase().padStart(2, '0')
        }
    }

    fun toLittleEndianByteArray(): ByteArray {
        return ByteArray(6) { index -> (value ushr (index * 8)).toByte() }
    }

    companion object {
        private const val MAX_VALUE = 0x0000ffffffffffffL

        fun fromLittleEndian(bytes: ByteArray, offset: Int): HciBluetoothAddress? {
            if (offset < 0 || offset > bytes.size - 6) {
                return null
            }
            var value = 0L
            for (index in 0 until 6) {
                value = value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
            }
            return if (value == 0L) null else HciBluetoothAddress(value)
        }
    }
}

internal data class HciAdapterCapabilities(
    val aclDataPacketLength: Int,
    val aclPacketCredits: Int,
    val address: HciBluetoothAddress,
    val localVersion: HciLocalVersionInfo?,
    val pageScanEnabled: Boolean = true
)

internal enum class HciAdapterInitializationState {
    IDLE,
    RESETTING,
    CONFIGURING_EVENT_MASK,
    READING_LOCAL_VERSION,
    READING_BUFFER_SIZE,
    ENABLING_SIMPLE_PAIRING,
    ENABLING_PAGE_SCAN,
    READING_ADDRESS,
    READY,
    FAILED
}

internal enum class HciAdapterInitializationErrorCode {
    SEND_FAILED,
    COMMAND_FAILED,
    MALFORMED_RESPONSE,
    TIMEOUT
}

internal data class HciAdapterInitializationFailure(
    val code: HciAdapterInitializationErrorCode,
    val opcode: Int,
    val controllerStatus: Int? = null
)

/**
 * Minimal, transport-independent bootstrap for a Bluetooth HCI controller.
 *
 * Command serialization and event correlation are owned by the session-level [HciCommandExecutor].
 */
internal class HciAdapterInitializer(
    private val commandExecutor: HciCommandExecutor,
    resetTimeoutRetries: Int = 0,
    private val beforeResetRetry: () -> Unit = {},
    private val allowPageScanFailure: Boolean = false
) {
    private var resetTimeoutRetriesRemaining = resetTimeoutRetries

    init {
        require(resetTimeoutRetries >= 0)
    }

    @Volatile
    var state = HciAdapterInitializationState.IDLE
        private set

    @Volatile
    var capabilities: HciAdapterCapabilities? = null
        private set

    @Volatile
    var failure: HciAdapterInitializationFailure? = null
        private set

    private var aclDataPacketLength = 0
    private var aclPacketCredits = 0
    private var localVersion: HciLocalVersionInfo? = null
    private var pageScanEnabled = false

    @Synchronized
    fun start(): Boolean {
        if (state != HciAdapterInitializationState.IDLE) {
            return state == HciAdapterInitializationState.READY
        }
        return submitNext(HciAdapterInitializationState.RESETTING, HciOpcodes.RESET)
    }

    @Synchronized
    private fun onCommandResult(result: HciCommandResult) {
        if (state.isTerminal()) return

        if (result is HciCommandResult.Failed) {
            if (state == HciAdapterInitializationState.RESETTING &&
                resetTimeoutRetriesRemaining > 0
            ) {
                resetTimeoutRetriesRemaining--
                runCatching(beforeResetRetry)
                submitNext(HciAdapterInitializationState.RESETTING, HciOpcodes.RESET)
                return
            }
            if (skipUnavailablePageScan()) return
            fail(HciAdapterInitializationErrorCode.TIMEOUT, result.opcode)
            return
        }
        result as HciCommandResult.Completed
        if (result.opcode != expectedOpcode() ||
            result.type != HciCommandCompletionType.COMMAND_COMPLETE
        ) {
            fail(HciAdapterInitializationErrorCode.MALFORMED_RESPONSE, expectedOpcode())
            return
        }
        val status = result.controllerStatus
        if (status == null) {
            if (skipUnsupportedLocalVersion()) return
            if (skipUnavailablePageScan()) return
            fail(HciAdapterInitializationErrorCode.MALFORMED_RESPONSE, result.opcode)
            return
        }
        if (status != SUCCESS_STATUS) {
            if (skipUnsupportedLocalVersion()) return
            if (skipUnavailablePageScan()) return
            fail(HciAdapterInitializationErrorCode.COMMAND_FAILED, result.opcode, status)
            return
        }

        when (state) {
            HciAdapterInitializationState.RESETTING -> {
                submitNext(
                    HciAdapterInitializationState.CONFIGURING_EVENT_MASK,
                    HciOpcodes.SET_EVENT_MASK,
                    DEFAULT_CLASSIC_EVENT_MASK.copyOf()
                )
            }
            HciAdapterInitializationState.CONFIGURING_EVENT_MASK -> {
                submitNext(
                    HciAdapterInitializationState.READING_LOCAL_VERSION,
                    HciOpcodes.READ_LOCAL_VERSION
                )
            }
            HciAdapterInitializationState.READING_LOCAL_VERSION -> {
                localVersion = parseLocalVersion(result.returnParameters)
                submitNext(
                    HciAdapterInitializationState.READING_BUFFER_SIZE,
                    HciOpcodes.READ_BUFFER_SIZE
                )
            }
            HciAdapterInitializationState.READING_BUFFER_SIZE -> {
                if (!acceptBufferSize(result.returnParameters)) {
                    fail(HciAdapterInitializationErrorCode.MALFORMED_RESPONSE, result.opcode)
                } else {
                    submitNext(
                        HciAdapterInitializationState.ENABLING_SIMPLE_PAIRING,
                        HciOpcodes.WRITE_SIMPLE_PAIRING_MODE,
                        byteArrayOf(SIMPLE_PAIRING_ENABLED)
                    )
                }
            }
            HciAdapterInitializationState.ENABLING_SIMPLE_PAIRING -> {
                submitNext(
                    HciAdapterInitializationState.ENABLING_PAGE_SCAN,
                    HciOpcodes.WRITE_SCAN_ENABLE,
                    byteArrayOf(PAGE_SCAN_ENABLED)
                )
            }
            HciAdapterInitializationState.ENABLING_PAGE_SCAN -> {
                pageScanEnabled = true
                submitNext(HciAdapterInitializationState.READING_ADDRESS, HciOpcodes.READ_BD_ADDR)
            }
            HciAdapterInitializationState.READING_ADDRESS -> {
                val address = if (result.returnParameters.size >= READ_ADDRESS_RETURN_PARAMETER_LENGTH) {
                    HciBluetoothAddress.fromLittleEndian(result.returnParameters, STATUS_PARAMETER_LENGTH)
                } else {
                    null
                }
                if (address == null) {
                    fail(HciAdapterInitializationErrorCode.MALFORMED_RESPONSE, result.opcode)
                } else {
                    capabilities = HciAdapterCapabilities(
                        aclDataPacketLength = aclDataPacketLength,
                        aclPacketCredits = aclPacketCredits,
                        address = address,
                        localVersion = localVersion,
                        pageScanEnabled = pageScanEnabled
                    )
                    state = HciAdapterInitializationState.READY
                }
            }
            else -> Unit
        }
    }

    private fun parseLocalVersion(parameters: ByteArray): HciLocalVersionInfo? {
        if (parameters.size < READ_LOCAL_VERSION_RETURN_PARAMETER_LENGTH) {
            return null
        }
        return HciLocalVersionInfo(
            hciVersion = parameters[1].toUnsignedInt(),
            hciRevision = littleEndianUnsignedShort(parameters, 2),
            lmpVersion = parameters[4].toUnsignedInt(),
            manufacturerId = littleEndianUnsignedShort(parameters, 5),
            lmpSubversion = littleEndianUnsignedShort(parameters, 7)
        )
    }

    private fun skipUnsupportedLocalVersion(): Boolean {
        if (state != HciAdapterInitializationState.READING_LOCAL_VERSION) {
            return false
        }
        localVersion = null
        submitNext(
            HciAdapterInitializationState.READING_BUFFER_SIZE,
            HciOpcodes.READ_BUFFER_SIZE
        )
        return true
    }

    /**
     * Fake CSR8510 controllers may advertise but never complete Write Scan Enable.
     * Active Inquiry/Create Connection remains usable; only controller-initiated
     * reconnect is unavailable for that adapter session.
     */
    private fun skipUnavailablePageScan(): Boolean {
        if (state != HciAdapterInitializationState.ENABLING_PAGE_SCAN ||
            !allowPageScanFailure
        ) {
            return false
        }
        pageScanEnabled = false
        submitNext(HciAdapterInitializationState.READING_ADDRESS, HciOpcodes.READ_BD_ADDR)
        return true
    }

    private fun acceptBufferSize(parameters: ByteArray): Boolean {
        if (parameters.size < READ_BUFFER_SIZE_RETURN_PARAMETER_LENGTH) {
            return false
        }
        val packetLength = littleEndianUnsignedShort(parameters, 1)
        val packetCredits = littleEndianUnsignedShort(parameters, 4)
        if (packetLength == 0 || packetCredits == 0) {
            return false
        }
        aclDataPacketLength = packetLength
        aclPacketCredits = packetCredits
        return true
    }

    private fun submitNext(
        nextState: HciAdapterInitializationState,
        opcode: Int,
        parameters: ByteArray = ByteArray(0)
    ): Boolean {
        state = nextState
        if (commandExecutor.submit(HciCommandPacket(opcode, parameters), ::onCommandResult)) {
            return true
        }
        fail(HciAdapterInitializationErrorCode.SEND_FAILED, opcode)
        return false
    }

    private fun expectedOpcode(): Int {
        return when (state) {
            HciAdapterInitializationState.RESETTING -> HciOpcodes.RESET
            HciAdapterInitializationState.CONFIGURING_EVENT_MASK -> HciOpcodes.SET_EVENT_MASK
            HciAdapterInitializationState.READING_LOCAL_VERSION -> HciOpcodes.READ_LOCAL_VERSION
            HciAdapterInitializationState.READING_BUFFER_SIZE -> HciOpcodes.READ_BUFFER_SIZE
            HciAdapterInitializationState.ENABLING_SIMPLE_PAIRING ->
                HciOpcodes.WRITE_SIMPLE_PAIRING_MODE
            HciAdapterInitializationState.ENABLING_PAGE_SCAN -> HciOpcodes.WRITE_SCAN_ENABLE
            HciAdapterInitializationState.READING_ADDRESS -> HciOpcodes.READ_BD_ADDR
            else -> 0
        }
    }

    private fun fail(
        code: HciAdapterInitializationErrorCode,
        opcode: Int,
        controllerStatus: Int? = null
    ) {
        failure = HciAdapterInitializationFailure(code, opcode, controllerStatus)
        state = HciAdapterInitializationState.FAILED
    }

    private fun HciAdapterInitializationState.isTerminal(): Boolean {
        return this == HciAdapterInitializationState.READY || this == HciAdapterInitializationState.FAILED
    }

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xff

    private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return bytes[offset].toUnsignedInt() or (bytes[offset + 1].toUnsignedInt() shl 8)
    }

    companion object {
        private const val SIMPLE_PAIRING_ENABLED: Byte = 0x01
        private const val PAGE_SCAN_ENABLED: Byte = 0x02
        // Android's Classic HCI default mask. Reserved event bits must remain zero.
        private val DEFAULT_CLASSIC_EVENT_MASK = byteArrayOf(
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xbf.toByte(), 0x3d
        )
        private const val STATUS_PARAMETER_LENGTH = 1
        private const val READ_LOCAL_VERSION_RETURN_PARAMETER_LENGTH = 9
        private const val READ_BUFFER_SIZE_RETURN_PARAMETER_LENGTH = 8
        private const val READ_ADDRESS_RETURN_PARAMETER_LENGTH = 7
        private const val SUCCESS_STATUS = 0
    }
}
