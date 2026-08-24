package com.limelight.binding.input.driver.wireless.hci

/** A command submitted to the Bluetooth controller over the USB class control pipe. */
internal class HciCommandPacket(
    val opcode: Int,
    val parameters: ByteArray = ByteArray(0)
)

/** A raw HCI event received from the interrupt endpoint. */
internal class HciEventPacket(
    val eventCode: Int,
    val parameters: ByteArray
)

/** A raw HCI ACL packet received from, or submitted to, the bulk endpoints. */
internal class HciAclPacket(
    val connectionHandle: Int,
    val packetBoundaryFlag: Int,
    val broadcastFlag: Int,
    val payload: ByteArray
)

/**
 * Strict codecs for the packet envelopes used by the USB Bluetooth HCI transport.
 *
 * USB packet type bytes are deliberately absent: commands use a class control transfer, events
 * use Interrupt IN, and ACL packets use Bulk IN/OUT. The endpoint already identifies the type.
 */
internal object HciPacketCodec {
    const val MAX_COMMAND_PARAMETERS = 0xff
    const val MAX_ACL_PAYLOAD = 0xffff

    fun encodeCommand(packet: HciCommandPacket): ByteArray {
        require(packet.opcode in 0..0xffff) { "HCI opcode is out of range" }
        require(packet.parameters.size <= MAX_COMMAND_PARAMETERS) {
            "HCI command parameters exceed the one-byte length field"
        }

        return ByteArray(3 + packet.parameters.size).also { encoded ->
            encoded[0] = packet.opcode.toByte()
            encoded[1] = (packet.opcode ushr 8).toByte()
            encoded[2] = packet.parameters.size.toByte()
            packet.parameters.copyInto(encoded, destinationOffset = 3)
        }
    }

    fun decodeEvent(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset
    ): HciEventPacket? {
        requireValidSlice(bytes, offset, length)
        if (length < 2) {
            return null
        }

        val parameterLength = bytes[offset + 1].toUnsignedInt()
        if (parameterLength != length - 2) {
            return null
        }

        return HciEventPacket(
            eventCode = bytes[offset].toUnsignedInt(),
            parameters = bytes.copyOfRange(offset + 2, offset + length)
        )
    }

    fun encodeAcl(packet: HciAclPacket): ByteArray {
        require(packet.connectionHandle in 0..0x0fff) { "ACL connection handle is out of range" }
        require(packet.packetBoundaryFlag in 0..0x03) { "ACL packet-boundary flag is out of range" }
        require(packet.broadcastFlag in 0..0x03) { "ACL broadcast flag is out of range" }
        require(packet.payload.size <= MAX_ACL_PAYLOAD) {
            "ACL payload exceeds the two-byte length field"
        }

        val handleAndFlags = packet.connectionHandle or
            (packet.packetBoundaryFlag shl 12) or
            (packet.broadcastFlag shl 14)
        return ByteArray(4 + packet.payload.size).also { encoded ->
            encoded[0] = handleAndFlags.toByte()
            encoded[1] = (handleAndFlags ushr 8).toByte()
            encoded[2] = packet.payload.size.toByte()
            encoded[3] = (packet.payload.size ushr 8).toByte()
            packet.payload.copyInto(encoded, destinationOffset = 4)
        }
    }

    fun decodeAcl(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset
    ): HciAclPacket? {
        requireValidSlice(bytes, offset, length)
        if (length < 4) {
            return null
        }

        val payloadLength = littleEndianUnsignedShort(bytes, offset + 2)
        if (payloadLength != length - 4) {
            return null
        }

        val handleAndFlags = littleEndianUnsignedShort(bytes, offset)
        return HciAclPacket(
            connectionHandle = handleAndFlags and 0x0fff,
            packetBoundaryFlag = (handleAndFlags ushr 12) and 0x03,
            broadcastFlag = (handleAndFlags ushr 14) and 0x03,
            payload = bytes.copyOfRange(offset + 4, offset + length)
        )
    }

    internal fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return bytes[offset].toUnsignedInt() or (bytes[offset + 1].toUnsignedInt() shl 8)
    }

    private fun requireValidSlice(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Packet slice is outside the source array"
        }
    }

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xff
}
