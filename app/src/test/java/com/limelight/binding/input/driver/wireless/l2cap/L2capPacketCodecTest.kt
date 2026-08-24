package com.limelight.binding.input.driver.wireless.l2cap

import com.limelight.binding.input.driver.wireless.hci.HciAclPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L2capPacketCodecTest {
    @Test
    fun encodesAndReassemblesBasicPduAcrossAclFragments() {
        val encoded = L2capPacketCodec.encode(
            L2capPacket(0x0042, 0x0040, byteArrayOf(1, 2, 3, 4, 5))
        )
        assertEquals(0x02, encoded.packetBoundaryFlag)
        assertArrayEquals(
            byteArrayOf(0x05, 0x00, 0x40, 0x00, 1, 2, 3, 4, 5),
            encoded.payload
        )

        val reassembler = L2capAclReassembler(0x0042)
        val first = reassembler.onAcl(
            HciAclPacket(0x0042, 0x02, 0, encoded.payload.copyOfRange(0, 6))
        )
        assertTrue(first.handled)
        assertNull(first.packet)
        val completed = reassembler.onAcl(
            HciAclPacket(0x0042, 0x01, 0, encoded.payload.copyOfRange(6, encoded.payload.size))
        )
        assertEquals(0x0040, completed.packet!!.channelId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), completed.packet!!.payload)
    }

    @Test
    fun isolatesHandlesAndRejectsBrokenFragmentSequences() {
        val reassembler = L2capAclReassembler(0x0042)
        assertFalse(reassembler.onAcl(HciAclPacket(0x0043, 0x02, 0, ByteArray(4))).handled)
        assertTrue(
            reassembler.onAcl(HciAclPacket(0x0042, 0x01, 0, byteArrayOf(1))).protocolError
        )
        assertTrue(
            reassembler.onAcl(
                HciAclPacket(0x0042, 0x03, 0, byteArrayOf(0x02, 0x00, 0x40, 0x00, 1))
            ).protocolError
        )
    }

    @Test
    fun signalingCodecHandlesMultipleCommandsAndRejectsTrailingBytes() {
        val first = L2capSignalingCodec.encode(L2capSignalingCommand(0x08, 1, byteArrayOf(1)))
        val second = L2capSignalingCodec.encode(L2capSignalingCommand(0x09, 2, byteArrayOf(2, 3)))
        val decoded = L2capSignalingCodec.decode(first + second)!!
        assertEquals(2, decoded.size)
        assertEquals(0x09, decoded[1].code)
        assertArrayEquals(byteArrayOf(2, 3), decoded[1].data)
        assertNull(L2capSignalingCodec.decode(first + byteArrayOf(0x01)))
    }
}
