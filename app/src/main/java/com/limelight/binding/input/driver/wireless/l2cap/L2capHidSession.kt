package com.limelight.binding.input.driver.wireless.l2cap

import com.limelight.binding.input.driver.wireless.hci.HciAclPacket

internal enum class L2capHidState {
    IDLE,
    WAITING_REMOTE_CONTROL,
    OPENING_CONTROL,
    CONFIGURING_CONTROL,
    OPENING_INTERRUPT,
    CONFIGURING_INTERRUPT,
    OPEN,
    CLOSING,
    CLOSED,
    FAILED
}

internal enum class L2capHidErrorCode {
    ACL_PROTOCOL_ERROR,
    MALFORMED_SIGNALING,
    SIGNALING_REJECTED,
    CONNECTION_REFUSED,
    CONFIGURATION_REJECTED,
    INVALID_CHANNEL,
    SEND_FAILED,
    TIMEOUT,
    LINK_DISCONNECTED
}

internal data class L2capHidFailure(
    val code: L2capHidErrorCode,
    val signalingCode: Int? = null,
    val result: Int? = null
)

internal data class L2capHidChannels(
    val controlLocalCid: Int,
    val controlRemoteCid: Int,
    val interruptLocalCid: Int,
    val interruptRemoteCid: Int
)

internal interface L2capHidListener {
    fun onChannelsOpen(channels: L2capHidChannels)
    fun onControlData(payload: ByteArray)
    fun onInterruptData(payload: ByteArray)
    fun onChannelsClosed()
    fun onL2capFailure(failure: L2capHidFailure)
}

/** Establishes the HID Control and Interrupt channels over BR/EDR Basic L2CAP mode. */
internal class L2capHidSession(
    private val connectionHandle: Int,
    private val sendAcl: (HciAclPacket) -> Boolean,
    private val listener: L2capHidListener,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val signalingTimeoutMs: Long = DEFAULT_SIGNALING_TIMEOUT_MS,
    private val preferRemoteInitiated: Boolean = false,
    private val remoteInitiatedGraceMs: Long = DEFAULT_REMOTE_INITIATED_GRACE_MS
) {
    private data class Channel(
        val psm: Int,
        val localCid: Int,
        var remoteCid: Int = 0,
        var localConfigurationAccepted: Boolean = false,
        var remoteConfigurationAccepted: Boolean = false,
        var remoteMtu: Int = DEFAULT_MTU
    )

    private enum class PendingKind { CONNECTION, CONFIGURATION, DISCONNECTION }

    private data class PendingRequest(
        val identifier: Int,
        val code: Int,
        val kind: PendingKind,
        val channel: Channel,
        val deadlineMs: Long
    )

    @Volatile
    var state = L2capHidState.IDLE
        private set

    @Volatile
    var failure: L2capHidFailure? = null
        private set

    private val reassembler = L2capAclReassembler(connectionHandle)
    private val control = Channel(HID_CONTROL_PSM, CONTROL_LOCAL_CID)
    private val interrupt = Channel(HID_INTERRUPT_PSM, INTERRUPT_LOCAL_CID)
    private val closeQueue = ArrayDeque<Channel>()
    private var pending: PendingRequest? = null
    private var nextIdentifier = 1
    private var passiveDeadlineMs = 0L

    init {
        require(connectionHandle in 0x0000..0x0eff)
        require(signalingTimeoutMs > 0)
        require(remoteInitiatedGraceMs > 0)
    }

    @Synchronized
    fun start(): Boolean {
        if (state != L2capHidState.IDLE) return state != L2capHidState.FAILED
        if (preferRemoteInitiated) {
            state = L2capHidState.WAITING_REMOTE_CONTROL
            passiveDeadlineMs = monotonicTimeMs() + remoteInitiatedGraceMs
            return true
        }
        state = L2capHidState.OPENING_CONTROL
        return sendConnectionRequest(control)
    }

    @Synchronized
    fun onAcl(packet: HciAclPacket): Boolean {
        val decoded = reassembler.onAcl(packet)
        if (!decoded.handled) return false
        if (decoded.protocolError) {
            fail(L2capHidErrorCode.ACL_PROTOCOL_ERROR)
            return true
        }
        val l2cap = decoded.packet ?: return true
        when (l2cap.channelId) {
            L2capPacketCodec.SIGNALING_CID -> handleSignaling(l2cap.payload)
            control.localCid -> deliverChannelData(l2cap.payload, listener::onControlData)
            interrupt.localCid -> deliverChannelData(l2cap.payload, listener::onInterruptData)
            else -> return false
        }
        return true
    }

    @Synchronized
    fun sendControl(payload: ByteArray): Boolean = sendChannelData(control, payload)

    @Synchronized
    fun sendInterrupt(payload: ByteArray): Boolean = sendChannelData(interrupt, payload)

    @Synchronized
    fun close(): Boolean {
        if (state == L2capHidState.CLOSED) return true
        if (state != L2capHidState.OPEN || pending != null) return false
        state = L2capHidState.CLOSING
        closeQueue.addLast(interrupt)
        closeQueue.addLast(control)
        return sendNextDisconnection()
    }

    @Synchronized
    fun checkTimeout(nowMs: Long = monotonicTimeMs()): Boolean {
        val current = pending
        if (current != null) {
            if (state.isTerminal() || nowMs < current.deadlineMs) return false
            fail(L2capHidErrorCode.TIMEOUT, current.code)
            return true
        }
        if (passiveDeadlineMs == 0L || state.isTerminal() || nowMs < passiveDeadlineMs) {
            return false
        }
        passiveDeadlineMs = 0L
        return when (state) {
            L2capHidState.WAITING_REMOTE_CONTROL -> {
                state = L2capHidState.OPENING_CONTROL
                sendConnectionRequest(control)
            }
            L2capHidState.OPENING_INTERRUPT -> sendConnectionRequest(interrupt)
            else -> false
        }
    }

    @Synchronized
    fun onLinkDisconnected(handle: Int): Boolean {
        if (handle != connectionHandle || state.isTerminal()) return false
        fail(L2capHidErrorCode.LINK_DISCONNECTED)
        return true
    }

    private fun handleSignaling(payload: ByteArray) {
        val commands = L2capSignalingCodec.decode(payload) ?: run {
            fail(L2capHidErrorCode.MALFORMED_SIGNALING)
            return
        }
        for (command in commands) {
            if (state.isTerminal()) return
            when (command.code) {
                COMMAND_REJECT_RESPONSE -> handleCommandReject(command)
                CONNECTION_REQUEST -> handleConnectionRequest(command)
                CONNECTION_RESPONSE -> handleConnectionResponse(command)
                CONFIGURATION_REQUEST -> handleConfigurationRequest(command)
                CONFIGURATION_RESPONSE -> handleConfigurationResponse(command)
                DISCONNECTION_REQUEST -> handleDisconnectionRequest(command)
                DISCONNECTION_RESPONSE -> handleDisconnectionResponse(command)
                ECHO_REQUEST -> sendSignaling(ECHO_RESPONSE, command.identifier, command.data)
                else -> sendCommandReject(command.identifier)
            }
        }
    }

    private fun handleConnectionRequest(command: L2capSignalingCommand) {
        if (command.data.size != 4) {
            fail(L2capHidErrorCode.MALFORMED_SIGNALING, command.code)
            return
        }
        val psm = u16(command.data, 0)
        val remoteCid = u16(command.data, 2)
        val channel = when {
            psm == HID_CONTROL_PSM && state == L2capHidState.WAITING_REMOTE_CONTROL -> control
            psm == HID_INTERRUPT_PSM && state == L2capHidState.OPENING_INTERRUPT &&
                pending == null -> interrupt
            else -> null
        }
        if (channel == null || channel.remoteCid != 0 ||
            remoteCid !in DYNAMIC_CID_MIN..0xffff ||
            remoteCid == control.remoteCid || remoteCid == interrupt.remoteCid
        ) {
            val result = if (psm == HID_CONTROL_PSM || psm == HID_INTERRUPT_PSM) {
                CONNECTION_REFUSED_NO_RESOURCES
            } else {
                CONNECTION_REFUSED_PSM_NOT_SUPPORTED
            }
            sendSignaling(
                CONNECTION_RESPONSE,
                command.identifier,
                le16(0) + le16(remoteCid) + le16(result) + le16(0)
            )
            return
        }

        channel.remoteCid = remoteCid
        passiveDeadlineMs = 0L
        state = if (channel === control) {
            L2capHidState.CONFIGURING_CONTROL
        } else {
            L2capHidState.CONFIGURING_INTERRUPT
        }
        if (!sendSignaling(
                CONNECTION_RESPONSE,
                command.identifier,
                le16(channel.localCid) + le16(remoteCid) + le16(SUCCESS) + le16(0)
            )
        ) return
        sendConfigurationRequest(channel)
    }

    private fun handleConnectionResponse(command: L2capSignalingCommand) {
        val current = pending
        if (current?.kind != PendingKind.CONNECTION ||
            current.identifier != command.identifier || command.data.size != 8
        ) {
            return
        }
        val remoteCid = u16(command.data, 0)
        val localCid = u16(command.data, 2)
        val result = u16(command.data, 4)
        if (result == CONNECTION_PENDING) {
            pending = current.copy(deadlineMs = monotonicTimeMs() + signalingTimeoutMs)
            return
        }
        if (result != SUCCESS) {
            fail(L2capHidErrorCode.CONNECTION_REFUSED, command.code, result)
            return
        }
        if (localCid != current.channel.localCid) {
            fail(L2capHidErrorCode.MALFORMED_SIGNALING, command.code)
            return
        }
        if (remoteCid !in DYNAMIC_CID_MIN..0xffff ||
            remoteCid == control.remoteCid || remoteCid == interrupt.remoteCid
        ) {
            fail(L2capHidErrorCode.INVALID_CHANNEL, command.code)
            return
        }
        current.channel.remoteCid = remoteCid
        pending = null
        state = if (current.channel === control) {
            L2capHidState.CONFIGURING_CONTROL
        } else {
            L2capHidState.CONFIGURING_INTERRUPT
        }
        sendConfigurationRequest(current.channel)
    }

    private fun handleConfigurationRequest(command: L2capSignalingCommand) {
        if (command.data.size < 4) {
            fail(L2capHidErrorCode.MALFORMED_SIGNALING, command.code)
            return
        }
        val localCid = u16(command.data, 0)
        val flags = u16(command.data, 2)
        val channel = channelByLocalCid(localCid) ?: run {
            sendCommandReject(command.identifier, localCid, 0)
            return
        }
        if (channel.remoteCid == 0 || flags != 0) {
            fail(L2capHidErrorCode.CONFIGURATION_REJECTED, command.code)
            return
        }
        val options = L2capConfigurationOptions.decode(command.data.copyOfRange(4, command.data.size))
            ?: run {
                fail(L2capHidErrorCode.MALFORMED_SIGNALING, command.code)
                return
            }
        val responseResult: Int
        val responseOptions: ByteArray
        when {
            options.unknownMandatory.isNotEmpty() -> {
                responseResult = CONFIG_UNKNOWN_OPTIONS
                responseOptions = options.unknownMandatory
            }
            options.mtu != null && options.mtu < MINIMUM_MTU -> {
                responseResult = CONFIG_UNACCEPTABLE_PARAMETERS
                responseOptions = mtuOption(DEFAULT_MTU)
            }
            else -> {
                responseResult = SUCCESS
                responseOptions = ByteArray(0)
                channel.remoteMtu = options.mtu ?: DEFAULT_MTU
                channel.remoteConfigurationAccepted = true
            }
        }
        if (!sendSignaling(
            CONFIGURATION_RESPONSE,
            command.identifier,
            le16(channel.remoteCid) + le16(0) + le16(responseResult) + responseOptions
        )) return
        maybeAdvance(channel)
    }

    private fun handleConfigurationResponse(command: L2capSignalingCommand) {
        val current = pending
        if (current?.kind != PendingKind.CONFIGURATION ||
            current.identifier != command.identifier || command.data.size < 6
        ) {
            return
        }
        val localCid = u16(command.data, 0)
        val flags = u16(command.data, 2)
        val result = u16(command.data, 4)
        if (localCid != current.channel.localCid || flags != 0) {
            fail(L2capHidErrorCode.MALFORMED_SIGNALING, command.code)
            return
        }
        if (result == CONFIG_PENDING) {
            pending = current.copy(deadlineMs = monotonicTimeMs() + signalingTimeoutMs)
            return
        }
        if (result != SUCCESS) {
            fail(L2capHidErrorCode.CONFIGURATION_REJECTED, command.code, result)
            return
        }
        if (L2capConfigurationOptions.decode(command.data.copyOfRange(6, command.data.size)) == null) {
            fail(L2capHidErrorCode.MALFORMED_SIGNALING, command.code)
            return
        }
        pending = null
        current.channel.localConfigurationAccepted = true
        maybeAdvance(current.channel)
    }

    private fun handleDisconnectionRequest(command: L2capSignalingCommand) {
        if (command.data.size != 4) {
            fail(L2capHidErrorCode.MALFORMED_SIGNALING, command.code)
            return
        }
        val localCid = u16(command.data, 0)
        val remoteCid = u16(command.data, 2)
        val channel = channelByLocalCid(localCid) ?: return
        if (channel.remoteCid != remoteCid) return
        if (!sendSignaling(DISCONNECTION_RESPONSE, command.identifier, command.data)) return

        val current = pending
        if (current?.kind == PendingKind.DISCONNECTION && current.channel === channel) {
            pending = null
        }
        channel.remoteCid = 0
        closeQueue.clear()
        listOf(interrupt, control)
            .filter { it !== channel && it.remoteCid != 0 }
            .forEach(closeQueue::addLast)
        state = L2capHidState.CLOSING
        if (pending == null) sendNextDisconnection()
    }

    private fun handleDisconnectionResponse(command: L2capSignalingCommand) {
        val current = pending
        if (current?.kind != PendingKind.DISCONNECTION ||
            current.identifier != command.identifier || command.data.size != 4
        ) {
            return
        }
        if (u16(command.data, 0) != current.channel.remoteCid ||
            u16(command.data, 2) != current.channel.localCid
        ) {
            fail(L2capHidErrorCode.MALFORMED_SIGNALING, command.code)
            return
        }
        pending = null
        sendNextDisconnection()
    }

    private fun handleCommandReject(command: L2capSignalingCommand) {
        if (pending?.identifier == command.identifier) {
            fail(L2capHidErrorCode.SIGNALING_REJECTED, command.code)
        }
    }

    private fun maybeAdvance(channel: Channel) {
        if (!channel.localConfigurationAccepted || !channel.remoteConfigurationAccepted || pending != null) {
            return
        }
        if (channel === control) {
            state = L2capHidState.OPENING_INTERRUPT
            if (preferRemoteInitiated) {
                passiveDeadlineMs = monotonicTimeMs() + remoteInitiatedGraceMs
            } else {
                sendConnectionRequest(interrupt)
            }
        } else {
            state = L2capHidState.OPEN
            runCatching {
                listener.onChannelsOpen(
                    L2capHidChannels(
                        control.localCid,
                        control.remoteCid,
                        interrupt.localCid,
                        interrupt.remoteCid
                    )
                )
            }
        }
    }

    private fun sendConnectionRequest(channel: Channel): Boolean {
        val identifier = allocateIdentifier()
        pending = PendingRequest(
            identifier,
            CONNECTION_REQUEST,
            PendingKind.CONNECTION,
            channel,
            monotonicTimeMs() + signalingTimeoutMs
        )
        return sendSignaling(
            CONNECTION_REQUEST,
            identifier,
            le16(channel.psm) + le16(channel.localCid)
        )
    }

    private fun sendConfigurationRequest(channel: Channel): Boolean {
        val identifier = allocateIdentifier()
        pending = PendingRequest(
            identifier,
            CONFIGURATION_REQUEST,
            PendingKind.CONFIGURATION,
            channel,
            monotonicTimeMs() + signalingTimeoutMs
        )
        return sendSignaling(
            CONFIGURATION_REQUEST,
            identifier,
            le16(channel.remoteCid) + le16(0) + mtuOption(DEFAULT_MTU)
        )
    }

    private fun sendNextDisconnection(): Boolean {
        var channel = closeQueue.removeFirstOrNull()
        while (channel != null && channel.remoteCid == 0) {
            channel = closeQueue.removeFirstOrNull()
        }
        if (channel == null) {
            state = L2capHidState.CLOSED
            runCatching { listener.onChannelsClosed() }
            return true
        }
        val identifier = allocateIdentifier()
        pending = PendingRequest(
            identifier,
            DISCONNECTION_REQUEST,
            PendingKind.DISCONNECTION,
            channel,
            monotonicTimeMs() + signalingTimeoutMs
        )
        return sendSignaling(
            DISCONNECTION_REQUEST,
            identifier,
            le16(channel.remoteCid) + le16(channel.localCid)
        )
    }

    private fun sendChannelData(channel: Channel, payload: ByteArray): Boolean {
        if (state != L2capHidState.OPEN ||
            channel.remoteCid == 0 || payload.size > channel.remoteMtu
        ) {
            return false
        }
        return sendPacket(channel.remoteCid, payload)
    }

    private fun deliverChannelData(payload: ByteArray, callback: (ByteArray) -> Unit) {
        if (state != L2capHidState.OPEN || payload.size > DEFAULT_MTU) {
            fail(L2capHidErrorCode.INVALID_CHANNEL)
            return
        }
        runCatching { callback(payload.copyOf()) }
    }

    private fun sendSignaling(code: Int, identifier: Int, data: ByteArray): Boolean {
        return sendPacket(
            L2capPacketCodec.SIGNALING_CID,
            L2capSignalingCodec.encode(L2capSignalingCommand(code, identifier, data))
        )
    }

    private fun sendPacket(channelId: Int, payload: ByteArray): Boolean {
        if (runCatching {
                sendAcl(L2capPacketCodec.encode(L2capPacket(connectionHandle, channelId, payload)))
            }.getOrDefault(false)
        ) {
            return true
        }
        fail(L2capHidErrorCode.SEND_FAILED)
        return false
    }

    private fun sendCommandReject(identifier: Int, localCid: Int? = null, remoteCid: Int? = null) {
        val data = if (localCid != null && remoteCid != null) {
            le16(COMMAND_REJECT_INVALID_CID) + le16(localCid) + le16(remoteCid)
        } else {
            le16(COMMAND_REJECT_NOT_UNDERSTOOD)
        }
        sendSignaling(COMMAND_REJECT_RESPONSE, identifier, data)
    }

    private fun channelByLocalCid(cid: Int): Channel? {
        return when (cid) {
            control.localCid -> control
            interrupt.localCid -> interrupt
            else -> null
        }
    }

    private fun allocateIdentifier(): Int {
        val result = nextIdentifier
        nextIdentifier = if (nextIdentifier == 0xff) 1 else nextIdentifier + 1
        return result
    }

    private fun fail(code: L2capHidErrorCode, signalingCode: Int? = null, result: Int? = null) {
        failure = L2capHidFailure(code, signalingCode, result)
        state = L2capHidState.FAILED
        pending = null
        passiveDeadlineMs = 0L
        reassembler.reset()
        runCatching { listener.onL2capFailure(failure!!) }
    }

    private fun L2capHidState.isTerminal(): Boolean {
        return this == L2capHidState.CLOSED || this == L2capHidState.FAILED
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun mtuOption(mtu: Int): ByteArray {
        return byteArrayOf(MTU_OPTION_TYPE, 0x02, mtu.toByte(), (mtu ushr 8).toByte())
    }

    companion object {
        private const val HID_CONTROL_PSM = 0x0011
        private const val HID_INTERRUPT_PSM = 0x0013
        private const val CONTROL_LOCAL_CID = 0x0040
        private const val INTERRUPT_LOCAL_CID = 0x0041
        private const val DYNAMIC_CID_MIN = 0x0040
        private const val DEFAULT_MTU = 672
        private const val MINIMUM_MTU = 48
        private const val DEFAULT_SIGNALING_TIMEOUT_MS = 10_000L
        private const val DEFAULT_REMOTE_INITIATED_GRACE_MS = 750L
        private const val MTU_OPTION_TYPE: Byte = 0x01

        private const val COMMAND_REJECT_RESPONSE = 0x01
        private const val CONNECTION_REQUEST = 0x02
        private const val CONNECTION_RESPONSE = 0x03
        private const val CONFIGURATION_REQUEST = 0x04
        private const val CONFIGURATION_RESPONSE = 0x05
        private const val DISCONNECTION_REQUEST = 0x06
        private const val DISCONNECTION_RESPONSE = 0x07
        private const val ECHO_REQUEST = 0x08
        private const val ECHO_RESPONSE = 0x09
        private const val SUCCESS = 0x0000
        private const val CONNECTION_PENDING = 0x0001
        private const val CONNECTION_REFUSED_PSM_NOT_SUPPORTED = 0x0002
        private const val CONNECTION_REFUSED_NO_RESOURCES = 0x0004
        private const val CONFIG_UNACCEPTABLE_PARAMETERS = 0x0001
        private const val CONFIG_UNKNOWN_OPTIONS = 0x0003
        private const val CONFIG_PENDING = 0x0004
        private const val COMMAND_REJECT_NOT_UNDERSTOOD = 0x0000
        private const val COMMAND_REJECT_INVALID_CID = 0x0002
    }
}

internal data class L2capSignalingCommand(
    val code: Int,
    val identifier: Int,
    val data: ByteArray
)

internal object L2capSignalingCodec {
    fun encode(command: L2capSignalingCommand): ByteArray {
        require(command.code in 1..0xff)
        require(command.identifier in 1..0xff)
        require(command.data.size <= 0xffff)
        return byteArrayOf(
            command.code.toByte(),
            command.identifier.toByte(),
            command.data.size.toByte(),
            (command.data.size ushr 8).toByte()
        ) + command.data
    }

    fun decode(payload: ByteArray): List<L2capSignalingCommand>? {
        if (payload.isEmpty()) return null
        val commands = ArrayList<L2capSignalingCommand>()
        var offset = 0
        while (offset < payload.size) {
            if (payload.size - offset < COMMAND_HEADER_LENGTH) return null
            val code = payload[offset].toInt() and 0xff
            val identifier = payload[offset + 1].toInt() and 0xff
            val length = (payload[offset + 2].toInt() and 0xff) or
                ((payload[offset + 3].toInt() and 0xff) shl 8)
            if (code == 0 || identifier == 0 || length > payload.size - offset - COMMAND_HEADER_LENGTH) {
                return null
            }
            val dataStart = offset + COMMAND_HEADER_LENGTH
            commands.add(
                L2capSignalingCommand(
                    code,
                    identifier,
                    payload.copyOfRange(dataStart, dataStart + length)
                )
            )
            offset = dataStart + length
        }
        return commands
    }

    private const val COMMAND_HEADER_LENGTH = 4
}

internal data class L2capConfigurationOptions(
    val mtu: Int?,
    val unknownMandatory: ByteArray
) {
    companion object {
        fun decode(bytes: ByteArray): L2capConfigurationOptions? {
            var mtu: Int? = null
            val unknown = ArrayList<Byte>()
            var offset = 0
            while (offset < bytes.size) {
                if (bytes.size - offset < 2) return null
                val rawType = bytes[offset].toInt() and 0xff
                val length = bytes[offset + 1].toInt() and 0xff
                if (length > bytes.size - offset - 2) return null
                val optionEnd = offset + 2 + length
                when (rawType and 0x7f) {
                    0x01 -> {
                        if (length != 2 || mtu != null) return null
                        mtu = (bytes[offset + 2].toInt() and 0xff) or
                            ((bytes[offset + 3].toInt() and 0xff) shl 8)
                    }
                    0x02 -> if (length != 2) return null
                    else -> if (rawType and 0x80 == 0) {
                        unknown.addAll(bytes.copyOfRange(offset, optionEnd).toList())
                    }
                }
                offset = optionEnd
            }
            return L2capConfigurationOptions(mtu, unknown.toByteArray())
        }
    }
}
