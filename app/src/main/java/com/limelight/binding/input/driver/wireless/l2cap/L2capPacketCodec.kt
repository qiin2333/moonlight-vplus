package com.limelight.binding.input.driver.wireless.l2cap

import com.limelight.binding.input.driver.wireless.hci.HciAclPacket

internal data class L2capPacket(
    val connectionHandle: Int,
    val channelId: Int,
    val payload: ByteArray
)

internal data class L2capAclDecodeResult(
    val handled: Boolean,
    val packet: L2capPacket? = null,
    val protocolError: Boolean = false
)

internal object L2capPacketCodec {
    const val SIGNALING_CID = 0x0001
    const val FIRST_NON_FLUSHABLE_PACKET_BOUNDARY_FLAG = 0x02

    fun encode(packet: L2capPacket): HciAclPacket {
        require(packet.connectionHandle in 0x0000..0x0eff)
        require(packet.channelId in 0x0001..0xffff)
        require(packet.payload.size <= 0xffff)
        val basicPdu = byteArrayOf(
            packet.payload.size.toByte(),
            (packet.payload.size ushr 8).toByte(),
            packet.channelId.toByte(),
            (packet.channelId ushr 8).toByte()
        ) + packet.payload
        return HciAclPacket(
            connectionHandle = packet.connectionHandle,
            packetBoundaryFlag = FIRST_NON_FLUSHABLE_PACKET_BOUNDARY_FLAG,
            broadcastFlag = 0,
            payload = basicPdu
        )
    }
}

/** Reassembles one Basic L2CAP PDU from HCI ACL start/continuation fragments. */
internal class L2capAclReassembler(
    private val connectionHandle: Int,
    private val maxPayloadLength: Int = DEFAULT_MAX_PAYLOAD_LENGTH
) {
    private var pending = ByteArray(0)
    private var expectedLength = 0

    init {
        require(connectionHandle in 0x0000..0x0eff)
        require(maxPayloadLength in 1..0xffff)
    }

    @Synchronized
    fun onAcl(packet: HciAclPacket): L2capAclDecodeResult {
        if (packet.connectionHandle != connectionHandle) {
            return L2capAclDecodeResult(handled = false)
        }
        if (packet.broadcastFlag != 0) return protocolError()

        return when (packet.packetBoundaryFlag) {
            CONTINUATION_PACKET_BOUNDARY_FLAG -> appendContinuation(packet.payload)
            FIRST_AUTOMATICALLY_FLUSHABLE_PACKET_BOUNDARY_FLAG,
            L2capPacketCodec.FIRST_NON_FLUSHABLE_PACKET_BOUNDARY_FLAG ->
                begin(packet.payload, mustBeComplete = false)
            COMPLETE_PACKET_BOUNDARY_FLAG -> begin(packet.payload, mustBeComplete = true)
            else -> protocolError()
        }
    }

    @Synchronized
    fun reset() {
        pending = ByteArray(0)
        expectedLength = 0
    }

    private fun begin(bytes: ByteArray, mustBeComplete: Boolean): L2capAclDecodeResult {
        if (pending.isNotEmpty() || bytes.size < BASIC_HEADER_LENGTH) return protocolError()
        val payloadLength = littleEndianUnsignedShort(bytes, 0)
        val channelId = littleEndianUnsignedShort(bytes, 2)
        if (payloadLength > maxPayloadLength || channelId == 0) return protocolError()
        expectedLength = BASIC_HEADER_LENGTH + payloadLength
        if (bytes.size > expectedLength || mustBeComplete && bytes.size != expectedLength) {
            return protocolError()
        }
        if (bytes.size == expectedLength) {
            expectedLength = 0
            return L2capAclDecodeResult(
                handled = true,
                packet = L2capPacket(
                    connectionHandle,
                    channelId,
                    bytes.copyOfRange(BASIC_HEADER_LENGTH, bytes.size)
                )
            )
        }
        pending = bytes.copyOf()
        return L2capAclDecodeResult(handled = true)
    }

    private fun appendContinuation(bytes: ByteArray): L2capAclDecodeResult {
        if (pending.isEmpty() || bytes.isEmpty() || pending.size + bytes.size > expectedLength) {
            return protocolError()
        }
        pending += bytes
        if (pending.size != expectedLength) return L2capAclDecodeResult(handled = true)

        val completed = pending
        val channelId = littleEndianUnsignedShort(completed, 2)
        reset()
        return L2capAclDecodeResult(
            handled = true,
            packet = L2capPacket(
                connectionHandle,
                channelId,
                completed.copyOfRange(BASIC_HEADER_LENGTH, completed.size)
            )
        )
    }

    private fun protocolError(): L2capAclDecodeResult {
        reset()
        return L2capAclDecodeResult(handled = true, protocolError = true)
    }

    private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    companion object {
        private const val BASIC_HEADER_LENGTH = 4
        private const val FIRST_AUTOMATICALLY_FLUSHABLE_PACKET_BOUNDARY_FLAG = 0x00
        private const val CONTINUATION_PACKET_BOUNDARY_FLAG = 0x01
        private const val COMPLETE_PACKET_BOUNDARY_FLAG = 0x03
        private const val DEFAULT_MAX_PAYLOAD_LENGTH = 4096
    }
}
