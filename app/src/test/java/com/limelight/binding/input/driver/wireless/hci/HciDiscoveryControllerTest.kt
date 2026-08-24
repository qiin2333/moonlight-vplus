package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HciDiscoveryControllerTest {
    @Test
    fun discoversDeduplicatesAndNamesOnlyPeripheralCandidates() {
        val harness = Harness()
        assertTrue(harness.controller.start())
        assertEquals(HciOpcodes.INQUIRY, harness.commands.single().packet.opcode)
        assertArrayEquals(
            byteArrayOf(0x33, 0x8b.toByte(), 0x9e.toByte(), 0x01, 0x00),
            harness.commands.single().packet.parameters
        )

        harness.completeCommandStatus(HciOpcodes.INQUIRY)
        assertEquals(HciDiscoveryState.INQUIRING, harness.controller.state)

        val dualSenseAddress = HciBluetoothAddress(0x112233445566)
        val phoneAddress = HciBluetoothAddress(0xa1a2a3a4a5a6)
        assertTrue(harness.controller.onEvent(
            HciEventPacket(
                eventCode = 0x02,
                parameters = standardInquiryResult(
                    InquiryRecord(dualSenseAddress, 0x01, 0x002508, 0x1234),
                    InquiryRecord(phoneAddress, 0x02, 0x020c00, 0x2345)
                )
            )
        ))
        assertEquals(2, harness.controller.snapshot().size)

        // A repeated controller response updates paging data without adding another candidate.
        harness.controller.onEvent(HciEventPacket(
            0x02,
            standardInquiryResult(InquiryRecord(dualSenseAddress, 0x02, 0x002508, 0x3456))
        ))
        assertEquals(2, harness.controller.snapshot().size)

        assertTrue(harness.controller.onEvent(HciEventPacket(0x01, byteArrayOf(0x00))))
        val nameRequest = harness.commands.last().packet
        assertEquals(HciOpcodes.REMOTE_NAME_REQUEST, nameRequest.opcode)
        assertArrayEquals(dualSenseAddress.toLittleEndianByteArray(), nameRequest.parameters.copyOf(6))
        assertEquals(0x02, nameRequest.parameters[6].toInt() and 0xff)
        assertEquals(0x56, nameRequest.parameters[8].toInt() and 0xff)
        assertEquals(0xb4, nameRequest.parameters[9].toInt() and 0xff)

        harness.completeCommandStatus(HciOpcodes.REMOTE_NAME_REQUEST)
        assertTrue(harness.controller.onEvent(
            HciEventPacket(
                0x07,
                remoteNameComplete(dualSenseAddress, "DualSense Wireless Controller")
            )
        ))

        assertEquals(HciDiscoveryState.COMPLETE, harness.controller.state)
        assertEquals(2, harness.completed!!.size)
        val named = harness.completed!!.single { it.address == dualSenseAddress }
        assertEquals("DualSense Wireless Controller", named.name)
        assertTrue(named.isPotentialDualSense)
        assertNull(harness.completed!!.single { it.address == phoneAddress }.name)
        assertEquals(2, harness.commands.size)
    }

    @Test
    fun malformedResultFailsWithoutPublishingPartialDevices() {
        val harness = Harness()
        harness.controller.start()
        harness.completeCommandStatus(HciOpcodes.INQUIRY)

        assertTrue(harness.controller.onEvent(
            HciEventPacket(0x02, byteArrayOf(0x01, 0x00, 0x01))
        ))
        assertEquals(HciDiscoveryState.FAILED, harness.controller.state)
        assertEquals(HciDiscoveryErrorCode.MALFORMED_EVENT, harness.failure!!.code)
        assertTrue(harness.controller.snapshot().isEmpty())
    }

    @Test
    fun inquiryOperationTimeoutAndCancelAreDeterministic() {
        var nowMs = 100L
        val timeoutHarness = Harness(monotonicTimeMs = { nowMs })
        timeoutHarness.controller.start()
        timeoutHarness.completeCommandStatus(HciOpcodes.INQUIRY)
        nowMs = 100L + 1280L + 1999L
        assertFalse(timeoutHarness.controller.checkTimeout())
        nowMs++
        assertTrue(timeoutHarness.controller.checkTimeout())
        assertEquals(HciDiscoveryErrorCode.TIMEOUT, timeoutHarness.failure!!.code)

        val cancelHarness = Harness()
        cancelHarness.controller.start()
        cancelHarness.completeCommandStatus(HciOpcodes.INQUIRY)
        assertTrue(cancelHarness.controller.cancel())
        assertEquals(HciOpcodes.INQUIRY_CANCEL, cancelHarness.commands.last().packet.opcode)
        cancelHarness.completeCommandComplete(HciOpcodes.INQUIRY_CANCEL)
        assertEquals(HciDiscoveryState.CANCELLED, cancelHarness.controller.state)
        assertTrue(cancelHarness.cancelled)
    }

    @Test
    fun commandTimeoutsFailTheActiveDiscoveryStage() {
        val inquiryHarness = Harness()
        inquiryHarness.controller.start()
        inquiryHarness.timeoutCommand(HciOpcodes.INQUIRY)
        assertEquals(HciDiscoveryState.FAILED, inquiryHarness.controller.state)
        assertEquals(HciDiscoveryErrorCode.TIMEOUT, inquiryHarness.failure!!.code)
        assertEquals(HciOpcodes.INQUIRY, inquiryHarness.failure!!.opcode)

        val nameHarness = Harness()
        val address = HciBluetoothAddress(0x112233445566)
        nameHarness.controller.start()
        nameHarness.completeCommandStatus(HciOpcodes.INQUIRY)
        nameHarness.controller.onEvent(HciEventPacket(
            0x02,
            standardInquiryResult(InquiryRecord(address, 0x01, 0x002508, 0x1234))
        ))
        nameHarness.controller.onEvent(HciEventPacket(0x01, byteArrayOf(0x00)))
        nameHarness.timeoutCommand(HciOpcodes.REMOTE_NAME_REQUEST)
        assertEquals(HciDiscoveryState.FAILED, nameHarness.controller.state)
        assertEquals(HciDiscoveryErrorCode.TIMEOUT, nameHarness.failure!!.code)
        assertEquals(HciOpcodes.REMOTE_NAME_REQUEST, nameHarness.failure!!.opcode)
    }

    @Test
    fun remoteNameCodecRejectsInvalidUtf8() {
        val address = HciBluetoothAddress(0x010203040506)
        val invalid = byteArrayOf(0x00) + address.toLittleEndianByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28, 0x00)
        assertNull(HciInquiryEventCodec.decodeRemoteNameComplete(invalid))
    }

    private data class SubmittedCommand(
        val packet: HciCommandPacket,
        val callback: (HciCommandResult) -> Unit
    )

    private class Harness(
        monotonicTimeMs: () -> Long = { 0L }
    ) : HciDiscoveryListener {
        val commands = ArrayList<SubmittedCommand>()
        var completed: List<HciDiscoveredDevice>? = null
        var failure: HciDiscoveryFailure? = null
        var cancelled = false
        val controller = HciDiscoveryController(
            submitCommand = { packet, callback ->
                commands.add(SubmittedCommand(packet, callback))
                true
            },
            listener = this,
            monotonicTimeMs = monotonicTimeMs,
            inquiryLength = 1
        )

        override fun onDeviceFound(device: HciDiscoveredDevice) = Unit

        override fun onDiscoveryComplete(devices: List<HciDiscoveredDevice>) {
            completed = devices
        }

        override fun onDiscoveryCancelled() {
            cancelled = true
        }

        override fun onDiscoveryFailure(failure: HciDiscoveryFailure) {
            this.failure = failure
        }

        fun completeCommandStatus(opcode: Int, status: Int = 0) {
            val submitted = commands.last { it.packet.opcode == opcode }
            submitted.callback(
                HciCommandResult.Completed(
                    opcode,
                    HciCommandCompletionType.COMMAND_STATUS,
                    status,
                    ByteArray(0)
                )
            )
        }

        fun completeCommandComplete(opcode: Int, status: Int = 0) {
            val submitted = commands.last { it.packet.opcode == opcode }
            submitted.callback(
                HciCommandResult.Completed(
                    opcode,
                    HciCommandCompletionType.COMMAND_COMPLETE,
                    status,
                    byteArrayOf(status.toByte())
                )
            )
        }

        fun timeoutCommand(opcode: Int) {
            val submitted = commands.last { it.packet.opcode == opcode }
            submitted.callback(HciCommandResult.Failed(opcode, HciCommandFailureCode.TIMEOUT))
        }
    }

    private data class InquiryRecord(
        val address: HciBluetoothAddress,
        val pageScanMode: Int,
        val classOfDevice: Int,
        val clockOffset: Int
    )

    companion object {
        private fun standardInquiryResult(vararg records: InquiryRecord): ByteArray {
            val result = ArrayList<Byte>()
            result.add(records.size.toByte())
            records.forEach { result.addAll(it.address.toLittleEndianByteArray().toList()) }
            records.forEach { result.add(it.pageScanMode.toByte()) }
            records.forEach { result.addAll(listOf(0x00, 0x00)) }
            records.forEach {
                result.add(it.classOfDevice.toByte())
                result.add((it.classOfDevice ushr 8).toByte())
                result.add((it.classOfDevice ushr 16).toByte())
            }
            records.forEach {
                result.add(it.clockOffset.toByte())
                result.add((it.clockOffset ushr 8).toByte())
            }
            return result.toByteArray()
        }

        private fun remoteNameComplete(address: HciBluetoothAddress, name: String): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            return byteArrayOf(0x00) + address.toLittleEndianByteArray() + nameBytes + byteArrayOf(0x00)
        }
    }
}
