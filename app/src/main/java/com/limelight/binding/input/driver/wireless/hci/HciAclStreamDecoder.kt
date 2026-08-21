package com.limelight.binding.input.driver.wireless.hci

internal data class HciAclDecodeResult(
    val packets: List<HciAclPacket>,
    val protocolError: Boolean
)

/**
 * Reassembles ACL packets across arbitrary USB bulk-transfer boundaries.
 *
 * Once an impossible payload length is observed there is no safe framing marker to resynchronize
 * against, so pending bytes are dropped and the caller is told to recover the transport.
 */
internal class HciAclStreamDecoder(
    maxPayloadLength: Int = DEFAULT_MAX_PAYLOAD_LENGTH
) {
    private var pending = ByteArray(0)
    private var maxPayloadLength = maxPayloadLength

    init {
        require(maxPayloadLength in 1..HciPacketCodec.MAX_ACL_PAYLOAD)
    }

    @Synchronized
    fun append(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset
    ): HciAclDecodeResult {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "ACL stream slice is outside the source array"
        }
        if (length == 0) {
            return HciAclDecodeResult(emptyList(), protocolError = false)
        }

        val combined = ByteArray(pending.size + length)
        pending.copyInto(combined)
        bytes.copyInto(combined, destinationOffset = pending.size, startIndex = offset, endIndex = offset + length)

        val packets = ArrayList<HciAclPacket>()
        var cursor = 0
        while (combined.size - cursor >= ACL_HEADER_LENGTH) {
            val payloadLength = HciPacketCodec.littleEndianUnsignedShort(combined, cursor + 2)
            if (payloadLength > maxPayloadLength) {
                pending = ByteArray(0)
                return HciAclDecodeResult(packets, protocolError = true)
            }

            val packetLength = ACL_HEADER_LENGTH + payloadLength
            if (combined.size - cursor < packetLength) {
                break
            }

            HciPacketCodec.decodeAcl(combined, cursor, packetLength)?.let(packets::add)
            cursor += packetLength
        }

        pending = combined.copyOfRange(cursor, combined.size)
        return HciAclDecodeResult(packets, protocolError = false)
    }

    @Synchronized
    fun configureMaxPayloadLength(maxPayloadLength: Int): Boolean {
        if (maxPayloadLength !in 1..HciPacketCodec.MAX_ACL_PAYLOAD || pending.isNotEmpty()) {
            return false
        }
        this.maxPayloadLength = maxPayloadLength
        return true
    }

    @Synchronized
    fun reset() {
        pending = ByteArray(0)
    }

    @Synchronized
    internal fun pendingByteCount(): Int = pending.size

    companion object {
        const val DEFAULT_MAX_PAYLOAD_LENGTH = HciPacketCodec.MAX_ACL_PAYLOAD
        private const val ACL_HEADER_LENGTH = 4
    }
}
