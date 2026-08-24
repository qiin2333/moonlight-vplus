package com.limelight.binding.input.driver.wireless.hci

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal enum class HciDiscoveryState {
    IDLE,
    STARTING_INQUIRY,
    INQUIRING,
    RESOLVING_NAMES,
    CANCELLING,
    COMPLETE,
    CANCELLED,
    FAILED
}

internal enum class HciDiscoveryErrorCode {
    COMMAND_BUSY_OR_SEND_FAILED,
    COMMAND_FAILED,
    INQUIRY_FAILED,
    MALFORMED_EVENT,
    TIMEOUT,
    CANCEL_FAILED
}

internal data class HciDiscoveryFailure(
    val code: HciDiscoveryErrorCode,
    val opcode: Int? = null,
    val controllerStatus: Int? = null
)

internal data class HciDiscoveredDevice(
    val address: HciBluetoothAddress,
    val pageScanRepetitionMode: Int,
    val classOfDevice: Int,
    val clockOffset: Int,
    val rssi: Int? = null,
    val name: String? = null
) {
    val isPeripheral: Boolean
        get() = ((classOfDevice ushr 8) and 0x1f) == MAJOR_DEVICE_CLASS_PERIPHERAL

    val isPotentialDualSense: Boolean
        get() = name == DUALSENSE_NAME || name == DUALSENSE_EDGE_NAME

    companion object {
        private const val MAJOR_DEVICE_CLASS_PERIPHERAL = 0x05
        private const val DUALSENSE_NAME = "DualSense Wireless Controller"
        private const val DUALSENSE_EDGE_NAME = "DualSense Edge Wireless Controller"
    }
}

internal interface HciDiscoveryListener {
    fun onDeviceFound(device: HciDiscoveredDevice)
    fun onDiscoveryComplete(devices: List<HciDiscoveredDevice>)
    fun onDiscoveryCancelled()
    fun onDiscoveryFailure(failure: HciDiscoveryFailure)
}

/** BR/EDR Inquiry and bounded remote-name resolution for controller candidates. */
internal class HciDiscoveryController(
    private val submitCommand: (HciCommandPacket, (HciCommandResult) -> Unit) -> Boolean,
    private val listener: HciDiscoveryListener,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val inquiryLength: Int = DEFAULT_INQUIRY_LENGTH,
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    private val maxNameRequests: Int = DEFAULT_MAX_NAME_REQUESTS
) {
    @Volatile
    var state = HciDiscoveryState.IDLE
        private set

    @Volatile
    var failure: HciDiscoveryFailure? = null
        private set

    private val candidates = LinkedHashMap<Long, HciDiscoveredDevice>()
    private val nameQueue = ArrayDeque<Long>()
    private var currentNameAddress: Long? = null
    private var deadlineMs = 0L
    private var cancelRequested = false

    init {
        require(inquiryLength in 0x01..0x30)
        require(maxCandidates > 0)
        require(maxNameRequests >= 0)
    }

    @Synchronized
    fun start(): Boolean {
        if (state != HciDiscoveryState.IDLE) {
            return state == HciDiscoveryState.STARTING_INQUIRY ||
                state == HciDiscoveryState.INQUIRING ||
                state == HciDiscoveryState.RESOLVING_NAMES
        }

        state = HciDiscoveryState.STARTING_INQUIRY
        val parameters = byteArrayOf(
            GIAC_LAP.toByte(),
            (GIAC_LAP ushr 8).toByte(),
            (GIAC_LAP ushr 16).toByte(),
            inquiryLength.toByte(),
            0x00
        )
        if (submitCommand(HciCommandPacket(HciOpcodes.INQUIRY, parameters), ::onInquiryStatus)) {
            return true
        }
        fail(HciDiscoveryErrorCode.COMMAND_BUSY_OR_SEND_FAILED, HciOpcodes.INQUIRY)
        return false
    }

    @Synchronized
    fun onEvent(event: HciEventPacket): Boolean {
        return when (event.eventCode) {
            INQUIRY_COMPLETE_EVENT_CODE -> handleInquiryComplete(event)
            INQUIRY_RESULT_EVENT_CODE -> handleInquiryResult(event)
            REMOTE_NAME_REQUEST_COMPLETE_EVENT_CODE -> handleRemoteNameComplete(event)
            else -> false
        }
    }

    @Synchronized
    fun checkTimeout(nowMs: Long = monotonicTimeMs()): Boolean {
        if (deadlineMs == 0L || state.isTerminal() || nowMs < deadlineMs) {
            return false
        }
        fail(HciDiscoveryErrorCode.TIMEOUT)
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        when (state) {
            HciDiscoveryState.STARTING_INQUIRY -> {
                cancelRequested = true
                return true
            }
            HciDiscoveryState.INQUIRING -> return submitCancel(HciOpcodes.INQUIRY_CANCEL, ByteArray(0))
            HciDiscoveryState.RESOLVING_NAMES -> {
                val address = currentNameAddress ?: return finishCancelled()
                return submitCancel(
                    HciOpcodes.REMOTE_NAME_REQUEST_CANCEL,
                    HciBluetoothAddress(address).toLittleEndianByteArray()
                )
            }
            HciDiscoveryState.IDLE -> return finishCancelled()
            HciDiscoveryState.CANCELLING -> return true
            else -> return false
        }
    }

    @Synchronized
    fun snapshot(): List<HciDiscoveredDevice> = candidates.values.toList()

    private fun onInquiryStatus(result: HciCommandResult) {
        synchronized(this) {
            if (state != HciDiscoveryState.STARTING_INQUIRY) return
            if (result is HciCommandResult.Failed) {
                fail(HciDiscoveryErrorCode.TIMEOUT, HciOpcodes.INQUIRY)
                return
            }
            val status = result.successfulCommandStatus(HciOpcodes.INQUIRY)
            if (status != SUCCESS_STATUS) {
                fail(HciDiscoveryErrorCode.COMMAND_FAILED, HciOpcodes.INQUIRY, status)
                return
            }
            state = HciDiscoveryState.INQUIRING
            deadlineMs = monotonicTimeMs() + inquiryLength * INQUIRY_UNIT_MS + INQUIRY_GRACE_MS
            if (cancelRequested) {
                submitCancel(HciOpcodes.INQUIRY_CANCEL, ByteArray(0))
            }
        }
    }

    private fun handleInquiryResult(event: HciEventPacket): Boolean {
        if (state != HciDiscoveryState.INQUIRING) return false
        val parsed = HciInquiryEventCodec.decodeStandardInquiryResult(event.parameters)
        if (parsed == null) {
            fail(HciDiscoveryErrorCode.MALFORMED_EVENT)
            return true
        }
        for (device in parsed) {
            val previous = candidates[device.address.value]
            val merged = if (previous == null) device else previous.copy(
                pageScanRepetitionMode = device.pageScanRepetitionMode,
                classOfDevice = device.classOfDevice,
                clockOffset = device.clockOffset
            )
            if (previous != null || candidates.size < maxCandidates) {
                candidates[device.address.value] = merged
                runCatching { listener.onDeviceFound(merged) }
            }
        }
        return true
    }

    private fun handleInquiryComplete(event: HciEventPacket): Boolean {
        if (state != HciDiscoveryState.INQUIRING) return false
        if (event.parameters.size != 1) {
            fail(HciDiscoveryErrorCode.MALFORMED_EVENT)
            return true
        }
        val status = event.parameters[0].toInt() and 0xff
        if (status != SUCCESS_STATUS) {
            fail(HciDiscoveryErrorCode.INQUIRY_FAILED, controllerStatus = status)
            return true
        }

        deadlineMs = 0L
        candidates.values.asSequence()
            .filter(HciDiscoveredDevice::isPeripheral)
            .take(maxNameRequests)
            .map { it.address.value }
            .forEach(nameQueue::addLast)
        requestNextName()
        return true
    }

    private fun requestNextName() {
        val addressValue = nameQueue.removeFirstOrNull()
        if (addressValue == null) {
            finishComplete()
            return
        }
        val device = candidates[addressValue] ?: run {
            requestNextName()
            return
        }

        currentNameAddress = addressValue
        state = HciDiscoveryState.RESOLVING_NAMES
        val clockOffsetWithValidFlag = device.clockOffset or CLOCK_OFFSET_VALID_FLAG
        val parameters = device.address.toLittleEndianByteArray() + byteArrayOf(
            device.pageScanRepetitionMode.toByte(),
            0x00,
            clockOffsetWithValidFlag.toByte(),
            (clockOffsetWithValidFlag ushr 8).toByte()
        )
        if (!submitCommand(
                HciCommandPacket(HciOpcodes.REMOTE_NAME_REQUEST, parameters),
                ::onRemoteNameStatus
            )
        ) {
            fail(
                HciDiscoveryErrorCode.COMMAND_BUSY_OR_SEND_FAILED,
                HciOpcodes.REMOTE_NAME_REQUEST
            )
        }
    }

    private fun onRemoteNameStatus(result: HciCommandResult) {
        synchronized(this) {
            if (state != HciDiscoveryState.RESOLVING_NAMES) return
            if (result is HciCommandResult.Failed) {
                fail(HciDiscoveryErrorCode.TIMEOUT, HciOpcodes.REMOTE_NAME_REQUEST)
                return
            }
            val status = result.successfulCommandStatus(HciOpcodes.REMOTE_NAME_REQUEST)
            if (status != SUCCESS_STATUS) {
                currentNameAddress = null
                requestNextName()
                return
            }
            deadlineMs = monotonicTimeMs() + REMOTE_NAME_TIMEOUT_MS
        }
    }

    private fun handleRemoteNameComplete(event: HciEventPacket): Boolean {
        if (state != HciDiscoveryState.RESOLVING_NAMES) return false
        val currentAddress = currentNameAddress ?: return false
        val result = HciInquiryEventCodec.decodeRemoteNameComplete(event.parameters) ?: run {
            fail(HciDiscoveryErrorCode.MALFORMED_EVENT)
            return true
        }
        if (result.address.value != currentAddress) return false

        deadlineMs = 0L
        val previous = candidates[currentAddress]
        if (result.status == SUCCESS_STATUS && previous != null && result.name != null) {
            val named = previous.copy(name = result.name)
            candidates[currentAddress] = named
            runCatching { listener.onDeviceFound(named) }
        }
        currentNameAddress = null
        requestNextName()
        return true
    }

    private fun submitCancel(opcode: Int, parameters: ByteArray): Boolean {
        state = HciDiscoveryState.CANCELLING
        deadlineMs = monotonicTimeMs() + CANCEL_TIMEOUT_MS
        if (submitCommand(HciCommandPacket(opcode, parameters), ::onCancelComplete)) {
            return true
        }
        fail(HciDiscoveryErrorCode.CANCEL_FAILED, opcode)
        return false
    }

    private fun onCancelComplete(result: HciCommandResult) {
        synchronized(this) {
            if (state != HciDiscoveryState.CANCELLING) return
            val completed = result as? HciCommandResult.Completed
            val status = completed?.controllerStatus
            if (completed?.type != HciCommandCompletionType.COMMAND_COMPLETE ||
                status != SUCCESS_STATUS
            ) {
                fail(HciDiscoveryErrorCode.CANCEL_FAILED, completed?.opcode, status)
                return
            }
            finishCancelled()
        }
    }

    private fun finishComplete() {
        state = HciDiscoveryState.COMPLETE
        deadlineMs = 0L
        runCatching { listener.onDiscoveryComplete(candidates.values.toList()) }
    }

    private fun finishCancelled(): Boolean {
        state = HciDiscoveryState.CANCELLED
        deadlineMs = 0L
        runCatching { listener.onDiscoveryCancelled() }
        return true
    }

    private fun fail(
        code: HciDiscoveryErrorCode,
        opcode: Int? = null,
        controllerStatus: Int? = null
    ) {
        failure = HciDiscoveryFailure(code, opcode, controllerStatus)
        state = HciDiscoveryState.FAILED
        deadlineMs = 0L
        runCatching { listener.onDiscoveryFailure(failure!!) }
    }

    private fun HciCommandResult.successfulCommandStatus(expectedOpcode: Int): Int? {
        val completed = this as? HciCommandResult.Completed ?: return null
        if (completed.opcode != expectedOpcode ||
            completed.type != HciCommandCompletionType.COMMAND_STATUS
        ) {
            return null
        }
        return completed.controllerStatus
    }

    private fun HciDiscoveryState.isTerminal(): Boolean {
        return this == HciDiscoveryState.COMPLETE ||
            this == HciDiscoveryState.CANCELLED ||
            this == HciDiscoveryState.FAILED
    }

    companion object {
        private const val GIAC_LAP = 0x9e8b33
        private const val DEFAULT_INQUIRY_LENGTH = 0x08
        private const val DEFAULT_MAX_CANDIDATES = 64
        private const val DEFAULT_MAX_NAME_REQUESTS = 16
        private const val INQUIRY_UNIT_MS = 1280L
        private const val INQUIRY_GRACE_MS = 2000L
        private const val REMOTE_NAME_TIMEOUT_MS = 7000L
        private const val CANCEL_TIMEOUT_MS = 3000L
        private const val CLOCK_OFFSET_VALID_FLAG = 0x8000
        private const val SUCCESS_STATUS = 0x00
        private const val INQUIRY_COMPLETE_EVENT_CODE = 0x01
        private const val INQUIRY_RESULT_EVENT_CODE = 0x02
        private const val REMOTE_NAME_REQUEST_COMPLETE_EVENT_CODE = 0x07
    }
}

internal data class HciRemoteNameResult(
    val status: Int,
    val address: HciBluetoothAddress,
    val name: String?
)

internal object HciInquiryEventCodec {
    private const val STANDARD_RESULT_LENGTH = 14
    private const val REMOTE_NAME_HEADER_LENGTH = 7
    private const val MAX_REMOTE_NAME_LENGTH = 248

    fun decodeStandardInquiryResult(parameters: ByteArray): List<HciDiscoveredDevice>? {
        if (parameters.isEmpty()) return null
        val count = parameters[0].toInt() and 0xff
        if (count == 0 || parameters.size != 1 + count * STANDARD_RESULT_LENGTH) return null

        val addressBase = 1
        val pageScanBase = addressBase + count * 6
        val reservedBase = pageScanBase + count
        val classBase = reservedBase + count * 2
        val clockBase = classBase + count * 3
        val results = ArrayList<HciDiscoveredDevice>(count)
        repeat(count) { index ->
            val address = HciBluetoothAddress.fromLittleEndian(parameters, addressBase + index * 6)
                ?: return null
            val pageScanMode = parameters[pageScanBase + index].toInt() and 0xff
            if (pageScanMode !in 0..2) return null
            val classOffset = classBase + index * 3
            val classOfDevice = unsignedByte(parameters[classOffset]) or
                (unsignedByte(parameters[classOffset + 1]) shl 8) or
                (unsignedByte(parameters[classOffset + 2]) shl 16)
            val clockOffset = littleEndianUnsignedShort(parameters, clockBase + index * 2) and 0x7fff
            results.add(
                HciDiscoveredDevice(
                    address = address,
                    pageScanRepetitionMode = pageScanMode,
                    classOfDevice = classOfDevice,
                    clockOffset = clockOffset
                )
            )
        }
        return results
    }

    fun decodeRemoteNameComplete(parameters: ByteArray): HciRemoteNameResult? {
        if (parameters.size !in REMOTE_NAME_HEADER_LENGTH..(REMOTE_NAME_HEADER_LENGTH + MAX_REMOTE_NAME_LENGTH)) {
            return null
        }
        val address = HciBluetoothAddress.fromLittleEndian(parameters, 1) ?: return null
        val status = unsignedByte(parameters[0])
        val nameEnd = (REMOTE_NAME_HEADER_LENGTH until parameters.size)
            .firstOrNull { parameters[it] == 0.toByte() } ?: parameters.size
        val name = if (status == 0 && nameEnd > REMOTE_NAME_HEADER_LENGTH) {
            decodeUtf8(parameters, REMOTE_NAME_HEADER_LENGTH, nameEnd - REMOTE_NAME_HEADER_LENGTH)
                ?: return null
        } else {
            null
        }
        return HciRemoteNameResult(status, address, name)
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int, length: Int): String? {
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        }.getOrNull()
    }

    private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return unsignedByte(bytes[offset]) or (unsignedByte(bytes[offset + 1]) shl 8)
    }

    private fun unsignedByte(value: Byte): Int = value.toInt() and 0xff
}
