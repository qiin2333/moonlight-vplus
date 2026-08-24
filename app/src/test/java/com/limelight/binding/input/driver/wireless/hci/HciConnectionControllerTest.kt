package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HciConnectionControllerTest {
    @Test
    fun createsAclConnectionUsingDiscoveryPagingData() {
        val harness = Harness()
        assertTrue(harness.controller.start())
        val command = harness.commands.single().packet
        assertEquals(HciOpcodes.CREATE_CONNECTION, command.opcode)
        assertArrayEquals(
            ADDRESS.toLittleEndianByteArray() + byteArrayOf(
                0x18, 0xcc.toByte(),
                0x02, 0x00,
                0x56, 0xb4.toByte(),
                0x01
            ),
            command.parameters
        )

        harness.completeCommandStatus(HciOpcodes.CREATE_CONNECTION)
        assertEquals(HciConnectionState.CONNECTING, harness.controller.state)
        assertTrue(harness.controller.onEvent(connectionComplete(status = 0, handle = 0x0042)))

        assertEquals(HciConnectionState.CONNECTED, harness.controller.state)
        assertEquals(0x0042, harness.connected!!.connectionHandle)
        assertFalse(harness.connected!!.encrypted)
        assertEquals(harness.connected, harness.controller.link)
    }

    @Test
    fun acceptsControllerInitiatedAclConnectionAsCentral() {
        val harness = Harness(incoming = true)

        assertTrue(harness.controller.start())
        val command = harness.commands.single().packet
        assertEquals(HciOpcodes.ACCEPT_CONNECTION_REQUEST, command.opcode)
        assertArrayEquals(
            ADDRESS.toLittleEndianByteArray() + byteArrayOf(0x01),
            command.parameters
        )

        harness.completeCommandStatus(HciOpcodes.ACCEPT_CONNECTION_REQUEST)
        assertEquals(HciConnectionState.CONNECTING, harness.controller.state)
        assertTrue(harness.controller.onEvent(connectionComplete(status = 0, handle = 0x0042)))
        assertEquals(HciConnectionState.CONNECTED, harness.controller.state)
        assertEquals(ADDRESS, harness.connected!!.address)
        assertTrue(harness.connected!!.initiatedByRemote)
    }

    @Test
    fun connectionRequestCodecRejectsMalformedAndDecodesAclRequest() {
        assertNull(HciConnectionEventCodec.decodeConnectionRequest(byteArrayOf(0x00)))
        val decoded = HciConnectionEventCodec.decodeConnectionRequest(
            ADDRESS.toLittleEndianByteArray() + byteArrayOf(
                0x08, 0x25, 0x00,
                0x01
            )
        )!!
        assertEquals(ADDRESS, decoded.address)
        assertEquals(0x002508, decoded.classOfDevice)
        assertTrue(decoded.isAcl)
    }

    @Test
    fun reportsControllerRejectionAndMalformedCompletion() {
        val rejected = Harness()
        rejected.controller.start()
        rejected.completeCommandStatus(HciOpcodes.CREATE_CONNECTION, status = 0x0c)
        assertEquals(HciConnectionState.FAILED, rejected.controller.state)
        assertEquals(HciConnectionErrorCode.COMMAND_FAILED, rejected.failure!!.code)
        assertEquals(0x0c, rejected.failure!!.controllerStatus)

        val malformed = Harness()
        malformed.controller.start()
        malformed.completeCommandStatus(HciOpcodes.CREATE_CONNECTION)
        assertTrue(malformed.controller.onEvent(HciEventPacket(0x03, byteArrayOf(0x00))))
        assertEquals(HciConnectionErrorCode.MALFORMED_EVENT, malformed.failure!!.code)

        val commandTimeout = Harness()
        commandTimeout.controller.start()
        commandTimeout.timeoutCommand(HciOpcodes.CREATE_CONNECTION)
        assertEquals(HciConnectionState.FAILED, commandTimeout.controller.state)
        assertEquals(HciConnectionErrorCode.TIMEOUT, commandTimeout.failure!!.code)
    }

    @Test
    fun ignoresConnectionCompletionForAnotherAddress() {
        val harness = Harness()
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.CREATE_CONNECTION)
        assertFalse(harness.controller.onEvent(connectionComplete(
            status = 0,
            handle = 0x0042,
            address = HciBluetoothAddress(0x010203040506)
        )))
        assertEquals(HciConnectionState.CONNECTING, harness.controller.state)
        assertNull(harness.connected)
    }

    @Test
    fun cancellationWaitsForMandatoryConnectionComplete() {
        val harness = Harness()
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.CREATE_CONNECTION)
        assertTrue(harness.controller.cancel())
        assertEquals(HciConnectionState.CANCELLING, harness.controller.state)
        assertEquals(HciOpcodes.CREATE_CONNECTION_CANCEL, harness.commands.last().packet.opcode)

        harness.completeCreateConnectionCancel()
        assertEquals(HciConnectionState.CANCELLING, harness.controller.state)
        assertFalse(harness.cancelled)

        assertTrue(harness.controller.onEvent(connectionComplete(status = 0x02, handle = 0)))
        assertEquals(HciConnectionState.CANCELLED, harness.controller.state)
        assertTrue(harness.cancelled)
    }

    @Test
    fun operationTimeoutCancelsTheControllerProcedureBeforeFailing() {
        var nowMs = 100L
        val harness = Harness(monotonicTimeMs = { nowMs }, connectionTimeoutMs = 1000L)
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.CREATE_CONNECTION)
        nowMs = 1099L
        assertFalse(harness.controller.checkTimeout())
        nowMs++
        assertTrue(harness.controller.checkTimeout())
        assertEquals(HciOpcodes.CREATE_CONNECTION_CANCEL, harness.commands.last().packet.opcode)
        assertNull(harness.failure)

        harness.completeCreateConnectionCancel()
        harness.controller.onEvent(connectionComplete(status = 0x02, handle = 0))
        assertEquals(HciConnectionState.FAILED, harness.controller.state)
        assertEquals(HciConnectionErrorCode.TIMEOUT, harness.failure!!.code)
    }

    @Test
    fun disconnectsConnectedLinkAndPublishesReason() {
        val harness = Harness()
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.CREATE_CONNECTION)
        harness.controller.onEvent(connectionComplete(status = 0, handle = 0x0234))

        assertTrue(harness.controller.cancel())
        val command = harness.commands.last().packet
        assertEquals(HciOpcodes.DISCONNECT, command.opcode)
        assertArrayEquals(byteArrayOf(0x34, 0x02, 0x13), command.parameters)
        harness.completeCommandStatus(HciOpcodes.DISCONNECT)
        assertTrue(harness.controller.onEvent(disconnectionComplete(0x0234, 0x16)))

        assertEquals(HciConnectionState.DISCONNECTED, harness.controller.state)
        assertEquals(0x16, harness.disconnectionReason)
    }

    @Test
    fun remoteDisconnectionIsAttributedToTheActiveHandle() {
        val harness = Harness()
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.CREATE_CONNECTION)
        harness.controller.onEvent(connectionComplete(status = 0, handle = 0x0042))

        assertFalse(harness.controller.onEvent(disconnectionComplete(0x0043, 0x08)))
        assertEquals(HciConnectionState.CONNECTED, harness.controller.state)
        assertTrue(harness.controller.onEvent(disconnectionComplete(0x0042, 0x08)))
        assertEquals(HciConnectionState.DISCONNECTED, harness.controller.state)
        assertEquals(0x08, harness.disconnectionReason)
    }

    private data class SubmittedCommand(
        val packet: HciCommandPacket,
        val callback: (HciCommandResult) -> Unit
    )

    private class Harness(
        monotonicTimeMs: () -> Long = { 0L },
        connectionTimeoutMs: Long = 15_000L,
        incoming: Boolean = false
    ) : HciConnectionListener {
        val commands = ArrayList<SubmittedCommand>()
        var connected: HciAclLink? = null
        var disconnectionReason: Int? = null
        var cancelled = false
        var failure: HciConnectionFailure? = null
        val controller = HciConnectionController(
            DEVICE,
            submitCommand = { packet, callback ->
                commands.add(SubmittedCommand(packet, callback))
                true
            },
            listener = this,
            monotonicTimeMs = monotonicTimeMs,
            connectionTimeoutMs = connectionTimeoutMs,
            incomingRequest = if (incoming) {
                HciConnectionRequest(ADDRESS, DEVICE.classOfDevice, 0x01)
            } else {
                null
            }
        )

        override fun onConnected(link: HciAclLink) {
            connected = link
        }

        override fun onDisconnected(link: HciAclLink, reason: Int) {
            disconnectionReason = reason
        }

        override fun onConnectionCancelled() {
            cancelled = true
        }

        override fun onConnectionFailure(failure: HciConnectionFailure) {
            this.failure = failure
        }

        fun completeCommandStatus(opcode: Int, status: Int = 0) {
            commands.last { it.packet.opcode == opcode }.callback(
                HciCommandResult.Completed(
                    opcode,
                    HciCommandCompletionType.COMMAND_STATUS,
                    status,
                    ByteArray(0)
                )
            )
        }

        fun completeCreateConnectionCancel(status: Int = 0) {
            commands.last { it.packet.opcode == HciOpcodes.CREATE_CONNECTION_CANCEL }.callback(
                HciCommandResult.Completed(
                    HciOpcodes.CREATE_CONNECTION_CANCEL,
                    HciCommandCompletionType.COMMAND_COMPLETE,
                    status,
                    byteArrayOf(status.toByte()) + ADDRESS.toLittleEndianByteArray()
                )
            )
        }

        fun timeoutCommand(opcode: Int) {
            commands.last { it.packet.opcode == opcode }.callback(
                HciCommandResult.Failed(opcode, HciCommandFailureCode.TIMEOUT)
            )
        }
    }

    companion object {
        private val ADDRESS = HciBluetoothAddress(0x112233445566)
        private val DEVICE = HciDiscoveredDevice(
            address = ADDRESS,
            pageScanRepetitionMode = 0x02,
            classOfDevice = 0x002508,
            clockOffset = 0x3456,
            name = "DualSense Wireless Controller"
        )

        private fun connectionComplete(
            status: Int,
            handle: Int,
            address: HciBluetoothAddress = ADDRESS,
            linkType: Int = 0x01,
            encrypted: Int = 0x00
        ): HciEventPacket {
            return HciEventPacket(
                0x03,
                byteArrayOf(status.toByte(), handle.toByte(), (handle ushr 8).toByte()) +
                    address.toLittleEndianByteArray() + byteArrayOf(
                    linkType.toByte(),
                    encrypted.toByte()
                )
            )
        }

        private fun disconnectionComplete(handle: Int, reason: Int): HciEventPacket {
            return HciEventPacket(
                0x05,
                byteArrayOf(0x00, handle.toByte(), (handle ushr 8).toByte(), reason.toByte())
            )
        }
    }
}
