package com.limelight.binding.input.driver.wireless.l2cap

import com.limelight.binding.input.driver.wireless.hci.HciAclPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L2capHidSessionTest {
    @Test
    fun opensControlThenInterruptAfterBidirectionalConfiguration() {
        val harness = Harness()
        assertTrue(harness.session.start())
        assertSignal(harness.sent.last(), 0x02, 1, le16(0x0011) + le16(0x0040))

        harness.receiveSignal(command(0x03, 1, le16(0x0070) + le16(0x0040) + le16(0) + le16(0)))
        assertSignal(
            harness.sent.last(),
            0x04,
            2,
            le16(0x0070) + le16(0) + byteArrayOf(0x01, 0x02, 0xa0.toByte(), 0x02)
        )

        // Both commands share one C-frame to verify the required multi-command parser path.
        harness.receiveSignals(
            command(0x04, 0x40, le16(0x0040) + le16(0) + mtuOption(512)),
            command(0x05, 2, le16(0x0040) + le16(0) + le16(0))
        )
        assertSignal(harness.sent[harness.sent.lastIndex - 1], 0x05, 0x40, le16(0x0070) + le16(0) + le16(0))
        assertSignal(harness.sent.last(), 0x02, 3, le16(0x0013) + le16(0x0041))

        harness.receiveSignal(command(0x03, 3, le16(0x0071) + le16(0x0041) + le16(0) + le16(0)))
        harness.receiveSignals(
            command(0x04, 0x41, le16(0x0041) + le16(0)),
            command(0x05, 4, le16(0x0041) + le16(0) + le16(0))
        )

        assertEquals(L2capHidState.OPEN, harness.session.state)
        assertEquals(0x0070, harness.opened!!.controlRemoteCid)
        assertEquals(0x0071, harness.opened!!.interruptRemoteCid)
        assertNull(harness.failure)
    }

    @Test
    fun acceptsRemoteInitiatedControlAndInterruptChannels() {
        val harness = Harness(preferRemoteInitiated = true)
        assertTrue(harness.session.start())
        assertEquals(L2capHidState.WAITING_REMOTE_CONTROL, harness.session.state)
        assertTrue(harness.sent.isEmpty())

        harness.receiveSignal(command(0x02, 0x30, le16(0x0011) + le16(0x0070)))
        assertSignal(
            harness.sent[harness.sent.lastIndex - 1],
            0x03,
            0x30,
            le16(0x0040) + le16(0x0070) + le16(0) + le16(0)
        )
        assertSignal(
            harness.sent.last(),
            0x04,
            1,
            le16(0x0070) + le16(0) + mtuOption(672)
        )
        harness.receiveSignals(
            command(0x04, 0x40, le16(0x0040) + le16(0)),
            command(0x05, 1, le16(0x0040) + le16(0) + le16(0))
        )
        assertEquals(L2capHidState.OPENING_INTERRUPT, harness.session.state)

        harness.receiveSignal(command(0x02, 0x31, le16(0x0013) + le16(0x0071)))
        assertSignal(
            harness.sent[harness.sent.lastIndex - 1],
            0x03,
            0x31,
            le16(0x0041) + le16(0x0071) + le16(0) + le16(0)
        )
        harness.receiveSignals(
            command(0x04, 0x41, le16(0x0041) + le16(0)),
            command(0x05, 2, le16(0x0041) + le16(0) + le16(0))
        )

        assertEquals(L2capHidState.OPEN, harness.session.state)
        assertEquals(0x0070, harness.opened!!.controlRemoteCid)
        assertEquals(0x0071, harness.opened!!.interruptRemoteCid)
    }

    @Test
    fun remoteInitiatedGraceFallsBackToHostControlOpen() {
        var nowMs = 100L
        val harness = Harness(
            monotonicTimeMs = { nowMs },
            preferRemoteInitiated = true,
            remoteInitiatedGraceMs = 750L
        )
        assertTrue(harness.session.start())
        nowMs = 849L
        assertFalse(harness.session.checkTimeout())
        nowMs++
        assertTrue(harness.session.checkTimeout())
        assertEquals(L2capHidState.OPENING_CONTROL, harness.session.state)
        assertSignal(harness.sent.single(), 0x02, 1, le16(0x0011) + le16(0x0040))
    }

    @Test
    fun routesChannelDataAndHonorsPeerMtu() {
        val harness = Harness()
        harness.open()
        assertTrue(harness.session.sendControl(byteArrayOf(0x52, 1, 2)))
        val outbound = decodeL2cap(harness.sent.last())
        assertEquals(0x0070, outbound.channelId)
        assertArrayEquals(byteArrayOf(0x52, 1, 2), outbound.payload)
        assertFalse(harness.session.sendControl(ByteArray(513)))

        assertTrue(harness.session.onAcl(L2capPacketCodec.encode(
            L2capPacket(HANDLE, 0x0041, byteArrayOf(0xa1.toByte(), 0x31))
        )))
        assertArrayEquals(byteArrayOf(0xa1.toByte(), 0x31), harness.interruptData)
    }

    @Test
    fun refusesUnknownMandatoryConfigurationAndTimesOutPendingRequest() {
        val rejected = Harness()
        rejected.session.start()
        rejected.receiveSignal(command(0x03, 1, le16(0x0070) + le16(0x0040) + le16(0) + le16(0)))
        rejected.receiveSignal(command(
            0x04,
            0x40,
            le16(0x0040) + le16(0) + byteArrayOf(0x06, 0x01, 0x01)
        ))
        assertSignal(
            rejected.sent.last(),
            0x05,
            0x40,
            le16(0x0070) + le16(0) + le16(3) + byteArrayOf(0x06, 0x01, 0x01)
        )
        assertEquals(L2capHidState.CONFIGURING_CONTROL, rejected.session.state)

        var nowMs = 100L
        val timeout = Harness(monotonicTimeMs = { nowMs }, signalingTimeoutMs = 1000L)
        timeout.session.start()
        nowMs = 1099L
        assertFalse(timeout.session.checkTimeout())
        nowMs++
        assertTrue(timeout.session.checkTimeout())
        assertEquals(L2capHidErrorCode.TIMEOUT, timeout.failure!!.code)
    }

    @Test
    fun closesInterruptThenControlAndHandlesRemoteDisconnect() {
        val harness = Harness()
        harness.open()
        assertTrue(harness.session.close())
        assertSignal(harness.sent.last(), 0x06, 5, le16(0x0071) + le16(0x0041))
        harness.receiveSignal(command(0x07, 5, le16(0x0071) + le16(0x0041)))
        assertSignal(harness.sent.last(), 0x06, 6, le16(0x0070) + le16(0x0040))
        harness.receiveSignal(command(0x07, 6, le16(0x0070) + le16(0x0040)))
        assertEquals(L2capHidState.CLOSED, harness.session.state)
        assertTrue(harness.closed)

        val remote = Harness()
        remote.open()
        remote.receiveSignal(command(0x06, 0x55, le16(0x0041) + le16(0x0071)))
        assertSignal(
            remote.sent[remote.sent.lastIndex - 1],
            0x07,
            0x55,
            le16(0x0041) + le16(0x0071)
        )
        assertSignal(remote.sent.last(), 0x06, 5, le16(0x0070) + le16(0x0040))
        remote.receiveSignal(command(0x07, 5, le16(0x0070) + le16(0x0040)))
        assertEquals(L2capHidState.CLOSED, remote.session.state)
    }

    @Test
    fun closesCleanlyWhileConnectionIsStillOpening() {
        val noChannel = Harness()
        assertTrue(noChannel.session.start())
        assertTrue(noChannel.session.close())
        assertEquals(L2capHidState.CLOSED, noChannel.session.state)
        assertTrue(noChannel.closed)

        val controlConnected = Harness()
        assertTrue(controlConnected.session.start())
        controlConnected.receiveSignal(
            command(0x03, 1, le16(0x0070) + le16(0x0040) + le16(0) + le16(0))
        )
        assertEquals(L2capHidState.CONFIGURING_CONTROL, controlConnected.session.state)
        assertTrue(controlConnected.session.close())
        assertEquals(L2capHidState.CLOSING, controlConnected.session.state)
        assertSignal(
            controlConnected.sent.last(),
            0x06,
            3,
            le16(0x0070) + le16(0x0040)
        )
    }

    private class Harness(
        monotonicTimeMs: () -> Long = { 0L },
        signalingTimeoutMs: Long = 10_000L,
        preferRemoteInitiated: Boolean = false,
        remoteInitiatedGraceMs: Long = 750L
    ) : L2capHidListener {
        val sent = ArrayList<HciAclPacket>()
        var opened: L2capHidChannels? = null
        var interruptData: ByteArray? = null
        var closed = false
        var failure: L2capHidFailure? = null
        val session = L2capHidSession(
            HANDLE,
            sendAcl = { sent.add(it); true },
            listener = this,
            monotonicTimeMs = monotonicTimeMs,
            signalingTimeoutMs = signalingTimeoutMs,
            preferRemoteInitiated = preferRemoteInitiated,
            remoteInitiatedGraceMs = remoteInitiatedGraceMs
        )

        override fun onChannelsOpen(channels: L2capHidChannels) {
            opened = channels
        }

        override fun onControlData(payload: ByteArray) = Unit

        override fun onInterruptData(payload: ByteArray) {
            interruptData = payload
        }

        override fun onChannelsClosed() {
            closed = true
        }

        override fun onL2capFailure(failure: L2capHidFailure) {
            this.failure = failure
        }

        fun receiveSignal(command: L2capSignalingCommand) {
            receiveSignals(command)
        }

        fun receiveSignals(vararg commands: L2capSignalingCommand) {
            val payload = commands.fold(ByteArray(0)) { bytes, item ->
                bytes + L2capSignalingCodec.encode(item)
            }
            session.onAcl(L2capPacketCodec.encode(
                L2capPacket(HANDLE, L2capPacketCodec.SIGNALING_CID, payload)
            ))
        }

        fun open() {
            session.start()
            receiveSignal(command(0x03, 1, le16(0x0070) + le16(0x0040) + le16(0) + le16(0)))
            receiveSignals(
                command(0x04, 0x40, le16(0x0040) + le16(0) + mtuOption(512)),
                command(0x05, 2, le16(0x0040) + le16(0) + le16(0))
            )
            receiveSignal(command(0x03, 3, le16(0x0071) + le16(0x0041) + le16(0) + le16(0)))
            receiveSignals(
                command(0x04, 0x41, le16(0x0041) + le16(0)),
                command(0x05, 4, le16(0x0041) + le16(0) + le16(0))
            )
            assertEquals(L2capHidState.OPEN, session.state)
        }
    }

    companion object {
        private const val HANDLE = 0x0042

        private fun command(code: Int, identifier: Int, data: ByteArray): L2capSignalingCommand {
            return L2capSignalingCommand(code, identifier, data)
        }

        private fun le16(value: Int): ByteArray {
            return byteArrayOf(value.toByte(), (value ushr 8).toByte())
        }

        private fun mtuOption(mtu: Int): ByteArray {
            return byteArrayOf(0x01, 0x02, mtu.toByte(), (mtu ushr 8).toByte())
        }

        private fun decodeL2cap(packet: HciAclPacket): L2capPacket {
            val length = (packet.payload[0].toInt() and 0xff) or
                ((packet.payload[1].toInt() and 0xff) shl 8)
            val cid = (packet.payload[2].toInt() and 0xff) or
                ((packet.payload[3].toInt() and 0xff) shl 8)
            return L2capPacket(
                packet.connectionHandle,
                cid,
                packet.payload.copyOfRange(4, 4 + length)
            )
        }

        private fun assertSignal(
            packet: HciAclPacket,
            code: Int,
            identifier: Int,
            data: ByteArray
        ) {
            val l2cap = decodeL2cap(packet)
            assertEquals(L2capPacketCodec.SIGNALING_CID, l2cap.channelId)
            val signal = L2capSignalingCodec.decode(l2cap.payload)!!.single()
            assertEquals(code, signal.code)
            assertEquals(identifier, signal.identifier)
            assertArrayEquals(data, signal.data)
        }
    }
}
