package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HciPacketCodecTest {
    @Test
    fun encodesUsbCommandWithoutPacketTypePrefix() {
        assertArrayEquals(
            byteArrayOf(0x03, 0x0c, 0x00),
            HciPacketCodec.encodeCommand(HciCommandPacket(opcode = 0x0c03))
        )
        assertArrayEquals(
            byteArrayOf(0x13, 0x0c, 0x01, 0x01),
            HciPacketCodec.encodeCommand(
                HciCommandPacket(opcode = 0x0c13, parameters = byteArrayOf(0x01))
            )
        )
    }

    @Test
    fun decodesStrictEventEnvelope() {
        val event = HciPacketCodec.decodeEvent(
            byteArrayOf(0x0e, 0x04, 0x01, 0x03, 0x0c, 0x00)
        )!!

        assertEquals(0x0e, event.eventCode)
        assertArrayEquals(byteArrayOf(0x01, 0x03, 0x0c, 0x00), event.parameters)
        assertNull(HciPacketCodec.decodeEvent(byteArrayOf(0x0e)))
        assertNull(HciPacketCodec.decodeEvent(byteArrayOf(0x0e, 0x02, 0x00)))
        assertNull(HciPacketCodec.decodeEvent(byteArrayOf(0x0e, 0x00, 0x00)))
    }

    @Test
    fun aclRoundTripPreservesHandleFlagsAndPayload() {
        val source = HciAclPacket(
            connectionHandle = 0x0abc,
            packetBoundaryFlag = 0x02,
            broadcastFlag = 0x01,
            payload = byteArrayOf(0x05, 0x00, 0x11, 0x00, 0x41)
        )

        val encoded = HciPacketCodec.encodeAcl(source)
        assertArrayEquals(
            byteArrayOf(0xbc.toByte(), 0x6a, 0x05, 0x00, 0x05, 0x00, 0x11, 0x00, 0x41),
            encoded
        )

        val decoded = HciPacketCodec.decodeAcl(encoded)!!
        assertEquals(source.connectionHandle, decoded.connectionHandle)
        assertEquals(source.packetBoundaryFlag, decoded.packetBoundaryFlag)
        assertEquals(source.broadcastFlag, decoded.broadcastFlag)
        assertArrayEquals(source.payload, decoded.payload)
    }

    @Test
    fun rejectsTruncatedAndOverlongAclEnvelopes() {
        assertNull(HciPacketCodec.decodeAcl(byteArrayOf(0x01, 0x20, 0x01)))
        assertNull(HciPacketCodec.decodeAcl(byteArrayOf(0x01, 0x20, 0x02, 0x00, 0x7f)))
        assertNull(HciPacketCodec.decodeAcl(byteArrayOf(0x01, 0x20, 0x00, 0x00, 0x7f)))
    }

    @Test
    fun streamDecoderHandlesSplitAndCoalescedBulkTransfers() {
        val first = HciPacketCodec.encodeAcl(
            HciAclPacket(0x001, 0x02, 0x00, byteArrayOf(0x11, 0x12, 0x13))
        )
        val second = HciPacketCodec.encodeAcl(
            HciAclPacket(0x002, 0x01, 0x00, byteArrayOf(0x21, 0x22))
        )
        val decoder = HciAclStreamDecoder()

        val prefix = decoder.append(first, length = 5)
        assertTrue(prefix.packets.isEmpty())
        assertFalse(prefix.protocolError)
        assertEquals(5, decoder.pendingByteCount())

        val remainderAndSecond = first.copyOfRange(5, first.size) + second
        val result = decoder.append(remainderAndSecond)
        assertFalse(result.protocolError)
        assertEquals(2, result.packets.size)
        assertEquals(0x001, result.packets[0].connectionHandle)
        assertArrayEquals(byteArrayOf(0x11, 0x12, 0x13), result.packets[0].payload)
        assertEquals(0x002, result.packets[1].connectionHandle)
        assertArrayEquals(byteArrayOf(0x21, 0x22), result.packets[1].payload)
        assertEquals(0, decoder.pendingByteCount())
    }

    @Test
    fun streamDecoderReportsImpossibleLengthAndClearsFraming() {
        val decoder = HciAclStreamDecoder(maxPayloadLength = 8)
        val malformedHeader = byteArrayOf(0x01, 0x20, 0x09, 0x00)

        val malformed = decoder.append(malformedHeader)
        assertTrue(malformed.protocolError)
        assertTrue(malformed.packets.isEmpty())
        assertEquals(0, decoder.pendingByteCount())

        val validPacket = HciPacketCodec.encodeAcl(
            HciAclPacket(0x003, 0x02, 0x00, byteArrayOf(0x31))
        )
        val recovered = decoder.append(validPacket)
        assertFalse(recovered.protocolError)
        assertEquals(1, recovered.packets.size)
    }

    @Test
    fun streamDecoderOnlyChangesNegotiatedLimitBetweenPackets() {
        val decoder = HciAclStreamDecoder()
        val packet = HciPacketCodec.encodeAcl(
            HciAclPacket(0x004, 0x02, 0x00, ByteArray(9))
        )

        decoder.append(packet, length = 5)
        assertFalse(decoder.configureMaxPayloadLength(8))
        decoder.reset()
        assertTrue(decoder.configureMaxPayloadLength(8))
        assertTrue(decoder.append(packet).protocolError)
    }
}
