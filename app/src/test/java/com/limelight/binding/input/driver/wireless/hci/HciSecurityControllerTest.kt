package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HciSecurityControllerTest {
    @Test
    fun performsJustWorksPairingStoresKeyAndEnablesEncryption() {
        val harness = Harness()
        assertTrue(harness.controller.start())
        assertCommand(harness, HciOpcodes.AUTHENTICATION_REQUESTED, byteArrayOf(0x42, 0x00))
        harness.completeCommandStatus(HciOpcodes.AUTHENTICATION_REQUESTED)

        assertTrue(harness.controller.onEvent(addressEvent(0x17)))
        assertCommand(
            harness,
            HciOpcodes.LINK_KEY_REQUEST_NEGATIVE_REPLY,
            ADDRESS.toLittleEndianByteArray()
        )
        harness.completeAddressReply(HciOpcodes.LINK_KEY_REQUEST_NEGATIVE_REPLY)

        assertTrue(harness.controller.onEvent(addressEvent(0x31)))
        assertCommand(
            harness,
            HciOpcodes.IO_CAPABILITY_REQUEST_REPLY,
            ADDRESS.toLittleEndianByteArray() + byteArrayOf(0x03, 0x00, 0x04)
        )
        harness.completeAddressReply(HciOpcodes.IO_CAPABILITY_REQUEST_REPLY)

        assertTrue(harness.controller.onEvent(HciEventPacket(
            0x33,
            ADDRESS.toLittleEndianByteArray() + byteArrayOf(0x40, 0xe2.toByte(), 0x01, 0x00)
        )))
        assertCommand(
            harness,
            HciOpcodes.USER_CONFIRMATION_REQUEST_REPLY,
            ADDRESS.toLittleEndianByteArray()
        )
        harness.completeAddressReply(HciOpcodes.USER_CONFIRMATION_REQUEST_REPLY)

        val linkKey = ByteArray(16) { (it + 1).toByte() }
        assertTrue(harness.controller.onEvent(HciEventPacket(
            0x18,
            ADDRESS.toLittleEndianByteArray() + linkKey + byteArrayOf(0x04)
        )))
        assertArrayEquals(linkKey, harness.store.saved!!.value)
        assertEquals(0x04, harness.store.saved!!.type)

        assertTrue(harness.controller.onEvent(HciEventPacket(
            0x36,
            byteArrayOf(0x00) + ADDRESS.toLittleEndianByteArray()
        )))
        assertTrue(harness.controller.onEvent(statusAndHandleEvent(0x06, 0x00)))
        assertCommand(
            harness,
            HciOpcodes.SET_CONNECTION_ENCRYPTION,
            byteArrayOf(0x42, 0x00, 0x01)
        )
        harness.completeCommandStatus(HciOpcodes.SET_CONNECTION_ENCRYPTION)
        assertTrue(harness.controller.onEvent(HciEventPacket(
            0x08,
            byteArrayOf(0x00, 0x42, 0x00, 0x01)
        )))

        assertEquals(HciSecurityState.ENCRYPTED, harness.controller.state)
        assertTrue(harness.encrypted!!.encrypted)
        assertNull(harness.failure)
    }

    @Test
    fun acceptsSecureConnectionsAesCcmEncryptionMode() {
        val harness = Harness()
        assertTrue(harness.controller.start())
        harness.completeCommandStatus(HciOpcodes.AUTHENTICATION_REQUESTED)
        assertTrue(harness.controller.onEvent(statusAndHandleEvent(0x06, 0x00)))
        harness.completeCommandStatus(HciOpcodes.SET_CONNECTION_ENCRYPTION)

        assertTrue(harness.controller.onEvent(HciEventPacket(
            0x08,
            byteArrayOf(0x00, 0x42, 0x00, 0x02)
        )))

        assertEquals(HciSecurityState.ENCRYPTED, harness.controller.state)
        assertTrue(harness.encrypted!!.encrypted)
    }

    @Test
    fun repliesWithTheDualSenseLegacyFallbackPinWhenRequested() {
        val harness = Harness()
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.AUTHENTICATION_REQUESTED)

        assertTrue(harness.controller.onEvent(addressEvent(0x16)))
        assertCommand(
            harness,
            HciOpcodes.PIN_CODE_REQUEST_REPLY,
            ADDRESS.toLittleEndianByteArray() + byteArrayOf(0x04) +
                byteArrayOf(0x30, 0x30, 0x30, 0x30) + ByteArray(12)
        )
        harness.completeAddressReply(HciOpcodes.PIN_CODE_REQUEST_REPLY)
        assertEquals(HciSecurityState.AUTHENTICATING, harness.controller.state)
        assertNull(harness.failure)
    }

    @Test
    fun reusesStoredKeyAndRetriesPairingOnceWhenItIsRejected() {
        val oldKey = ByteArray(16) { 0x5a }
        val store = MemoryKeyStore(HciLinkKey(oldKey, 0x04))
        val harness = Harness(store = store)
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.AUTHENTICATION_REQUESTED)

        harness.controller.onEvent(addressEvent(0x17))
        assertCommand(
            harness,
            HciOpcodes.LINK_KEY_REQUEST_REPLY,
            ADDRESS.toLittleEndianByteArray() + oldKey
        )
        harness.completeAddressReply(HciOpcodes.LINK_KEY_REQUEST_REPLY)
        harness.controller.onEvent(statusAndHandleEvent(0x06, 0x06))

        assertTrue(store.removed)
        assertEquals(HciOpcodes.AUTHENTICATION_REQUESTED, harness.commands.last().packet.opcode)
        harness.completeCommandStatus(HciOpcodes.AUTHENTICATION_REQUESTED)
        harness.controller.onEvent(addressEvent(0x17))
        assertEquals(
            HciOpcodes.LINK_KEY_REQUEST_NEGATIVE_REPLY,
            harness.commands.last().packet.opcode
        )
        assertEquals(HciSecurityState.AUTHENTICATING, harness.controller.state)
    }

    @Test
    fun rejectsMalformedSecurityEventsAndTracksLinkLoss() {
        val malformed = Harness()
        malformed.controller.start()
        malformed.completeCommandStatus(HciOpcodes.AUTHENTICATION_REQUESTED)
        assertTrue(malformed.controller.onEvent(HciEventPacket(0x31, byteArrayOf(0x01))))
        assertEquals(HciSecurityErrorCode.MALFORMED_EVENT, malformed.failure!!.code)

        val disconnected = Harness()
        disconnected.controller.start()
        disconnected.completeCommandStatus(HciOpcodes.AUTHENTICATION_REQUESTED)
        assertFalse(disconnected.controller.onLinkDisconnected(0x0043))
        assertTrue(disconnected.controller.onLinkDisconnected(0x0042))
        assertEquals(HciSecurityErrorCode.LINK_DISCONNECTED, disconnected.failure!!.code)
    }

    @Test
    fun authenticationOperationHasABoundedTimeout() {
        var nowMs = 100L
        val harness = Harness(
            monotonicTimeMs = { nowMs },
            authenticationTimeoutMs = 1000L
        )
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.AUTHENTICATION_REQUESTED)
        nowMs = 1099L
        assertFalse(harness.controller.checkTimeout())
        nowMs++
        assertTrue(harness.controller.checkTimeout())
        assertEquals(HciSecurityErrorCode.TIMEOUT, harness.failure!!.code)
    }

    private data class SubmittedCommand(
        val packet: HciCommandPacket,
        val callback: (HciCommandResult) -> Unit
    )

    private class MemoryKeyStore(initial: HciLinkKey? = null) : HciLinkKeyStore {
        var saved = initial
        var removed = false

        override fun load(address: HciBluetoothAddress): HciLinkKey? = saved

        override fun save(address: HciBluetoothAddress, key: HciLinkKey) {
            saved = key
        }

        override fun remove(address: HciBluetoothAddress) {
            saved = null
            removed = true
        }
    }

    private class Harness(
        val store: MemoryKeyStore = MemoryKeyStore(),
        monotonicTimeMs: () -> Long = { 0L },
        authenticationTimeoutMs: Long = 30_000L
    ) : HciSecurityListener {
        val commands = ArrayList<SubmittedCommand>()
        var encrypted: HciAclLink? = null
        var failure: HciSecurityFailure? = null
        val controller = HciSecurityController(
            LINK,
            store,
            submitCommand = { packet, callback ->
                commands.add(SubmittedCommand(packet, callback))
                true
            },
            listener = this,
            monotonicTimeMs = monotonicTimeMs,
            authenticationTimeoutMs = authenticationTimeoutMs
        )

        override fun onLinkEncrypted(link: HciAclLink) {
            encrypted = link
        }

        override fun onSecurityFailure(failure: HciSecurityFailure) {
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

        fun completeAddressReply(opcode: Int, status: Int = 0) {
            commands.last { it.packet.opcode == opcode }.callback(
                HciCommandResult.Completed(
                    opcode,
                    HciCommandCompletionType.COMMAND_COMPLETE,
                    status,
                    byteArrayOf(status.toByte()) + ADDRESS.toLittleEndianByteArray()
                )
            )
        }
    }

    companion object {
        private val ADDRESS = HciBluetoothAddress(0x112233445566)
        private val LINK = HciAclLink(ADDRESS, 0x0042, encrypted = false)

        private fun assertCommand(harness: Harness, opcode: Int, parameters: ByteArray) {
            assertEquals(opcode, harness.commands.last().packet.opcode)
            assertArrayEquals(parameters, harness.commands.last().packet.parameters)
        }

        private fun addressEvent(eventCode: Int): HciEventPacket {
            return HciEventPacket(eventCode, ADDRESS.toLittleEndianByteArray())
        }

        private fun statusAndHandleEvent(eventCode: Int, status: Int): HciEventPacket {
            return HciEventPacket(eventCode, byteArrayOf(status.toByte(), 0x42, 0x00))
        }
    }
}
