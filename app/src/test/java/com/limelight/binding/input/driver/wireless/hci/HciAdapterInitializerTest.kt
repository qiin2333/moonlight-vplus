package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HciAdapterInitializerTest {
    @Test
    fun initializesVersionBufferSizeAndAddressInOrder() {
        val harness = Harness()

        assertTrue(harness.initializer.start())
        assertEquals(HciAdapterInitializationState.RESETTING, harness.initializer.state)
        assertEquals(listOf(HciOpcodes.RESET), harness.commands.map { it.opcode })

        assertTrue(harness.complete(HciOpcodes.RESET, byteArrayOf(0x00)))
        assertEquals(HciAdapterInitializationState.CONFIGURING_EVENT_MASK, harness.initializer.state)
        assertEquals(HciOpcodes.SET_EVENT_MASK, harness.commands.last().opcode)
        assertEquals(
            listOf(
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0xff.toByte(), 0xff.toByte(), 0xbf.toByte(), 0x3d.toByte()
            ),
            harness.commands.last().parameters.toList()
        )

        assertTrue(harness.complete(HciOpcodes.SET_EVENT_MASK, byteArrayOf(0x00)))
        assertEquals(HciAdapterInitializationState.READING_LOCAL_VERSION, harness.initializer.state)
        assertEquals(HciOpcodes.READ_LOCAL_VERSION, harness.commands.last().opcode)

        assertTrue(harness.complete(HciOpcodes.READ_LOCAL_VERSION, localVersionResponse()))
        assertEquals(HciAdapterInitializationState.READING_BUFFER_SIZE, harness.initializer.state)
        assertEquals(HciOpcodes.READ_BUFFER_SIZE, harness.commands.last().opcode)

        assertTrue(harness.complete(
            HciOpcodes.READ_BUFFER_SIZE,
            byteArrayOf(
                0x00,
                0xfb.toByte(), 0x03,
                0x00,
                0x08, 0x00,
                0x00, 0x00
            )
        ))
        assertEquals(HciAdapterInitializationState.ENABLING_SIMPLE_PAIRING, harness.initializer.state)
        assertEquals(HciOpcodes.WRITE_SIMPLE_PAIRING_MODE, harness.commands.last().opcode)
        assertEquals(listOf(0x01.toByte()), harness.commands.last().parameters.toList())

        assertTrue(harness.complete(HciOpcodes.WRITE_SIMPLE_PAIRING_MODE, byteArrayOf(0x00)))
        assertEquals(HciAdapterInitializationState.ENABLING_PAGE_SCAN, harness.initializer.state)
        assertEquals(HciOpcodes.WRITE_SCAN_ENABLE, harness.commands.last().opcode)
        assertEquals(listOf(0x02.toByte()), harness.commands.last().parameters.toList())

        assertTrue(harness.complete(HciOpcodes.WRITE_SCAN_ENABLE, byteArrayOf(0x00)))
        assertEquals(HciAdapterInitializationState.READING_ADDRESS, harness.initializer.state)
        assertEquals(HciOpcodes.READ_BD_ADDR, harness.commands.last().opcode)

        assertTrue(harness.complete(
            HciOpcodes.READ_BD_ADDR,
            byteArrayOf(0x00, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11)
        ))

        val capabilities = harness.initializer.capabilities!!
        assertEquals(HciAdapterInitializationState.READY, harness.initializer.state)
        assertEquals(7, harness.commands.size)
        assertEquals(1019, capabilities.aclDataPacketLength)
        assertEquals(8, capabilities.aclPacketCredits)
        assertEquals("11:22:33:44:55:66", capabilities.address.toString())
        assertEquals(0x004c, capabilities.localVersion!!.manufacturerId)
        assertEquals(0x1234, capabilities.localVersion!!.hciRevision)
        assertTrue(capabilities.pageScanEnabled)
        assertNull(harness.initializer.failure)
    }

    @Test
    fun leavesUnrelatedEventsForTheHostStateMachine() {
        val harness = Harness()
        assertTrue(harness.initializer.start())

        assertFalse(harness.executor.onEvent(HciEventPacket(0xff, byteArrayOf(0x00))))
        assertFalse(harness.complete(HciOpcodes.READ_BD_ADDR, byteArrayOf(0x00)))
        assertEquals(HciAdapterInitializationState.RESETTING, harness.initializer.state)
    }

    @Test
    fun rejectsControllerErrorAndMalformedCapabilities() {
        val failedCommand = Harness()
        failedCommand.initializer.start()
        assertTrue(failedCommand.complete(HciOpcodes.RESET, byteArrayOf(0x0c)))
        assertEquals(HciAdapterInitializationState.FAILED, failedCommand.initializer.state)
        assertEquals(
            HciAdapterInitializationErrorCode.COMMAND_FAILED,
            failedCommand.initializer.failure!!.code
        )
        assertEquals(0x0c, failedCommand.initializer.failure!!.controllerStatus)

        val malformed = Harness()
        malformed.initializer.start()
        malformed.complete(HciOpcodes.RESET, byteArrayOf(0x00))
        malformed.complete(HciOpcodes.SET_EVENT_MASK, byteArrayOf(0x00))
        malformed.complete(HciOpcodes.READ_LOCAL_VERSION, localVersionResponse())
        assertTrue(malformed.complete(
            HciOpcodes.READ_BUFFER_SIZE,
            byteArrayOf(0x00, 0x00, 0x00)
        ))
        assertEquals(HciAdapterInitializationState.FAILED, malformed.initializer.state)
        assertEquals(
            HciAdapterInitializationErrorCode.MALFORMED_RESPONSE,
            malformed.initializer.failure!!.code
        )
    }

    @Test
    fun unsupportedLocalVersionIsDiagnosticOnly() {
        val harness = Harness()
        harness.initializer.start()
        harness.complete(HciOpcodes.RESET, byteArrayOf(0x00))
        harness.complete(HciOpcodes.SET_EVENT_MASK, byteArrayOf(0x00))

        assertTrue(harness.complete(HciOpcodes.READ_LOCAL_VERSION, byteArrayOf(0x01)))
        assertEquals(HciAdapterInitializationState.READING_BUFFER_SIZE, harness.initializer.state)
        assertEquals(HciOpcodes.READ_BUFFER_SIZE, harness.commands.last().opcode)
    }

    @Test
    fun reportsSendFailureAndPerCommandTimeout() {
        val sendFailureExecutor = HciCommandExecutor(sendCommand = { false })
        val sendFailure = HciAdapterInitializer(sendFailureExecutor)
        assertFalse(sendFailure.start())
        assertEquals(HciAdapterInitializationState.FAILED, sendFailure.state)
        assertEquals(HciAdapterInitializationErrorCode.SEND_FAILED, sendFailure.failure!!.code)

        var nowMs = 50L
        val timeoutExecutor = HciCommandExecutor(
            sendCommand = { true },
            monotonicTimeMs = { nowMs },
            commandTimeoutMs = 1000L
        )
        val timeout = HciAdapterInitializer(timeoutExecutor)
        timeout.start()
        nowMs = 1049L
        assertFalse(timeoutExecutor.checkTimeout())
        nowMs = 1050L
        assertTrue(timeoutExecutor.checkTimeout())
        assertEquals(HciAdapterInitializationState.FAILED, timeout.state)
        assertEquals(HciAdapterInitializationErrorCode.TIMEOUT, timeout.failure!!.code)
        assertEquals(HciOpcodes.RESET, timeout.failure!!.opcode)
    }

    @Test
    fun retriesOnlyTheInitialResetTimeoutWhenConfigured() {
        var nowMs = 0L
        val commands = ArrayList<HciCommandPacket>()
        var settleCalls = 0
        val executor = HciCommandExecutor(
            sendCommand = { commands.add(it); true },
            monotonicTimeMs = { nowMs },
            commandTimeoutMs = 100L
        )
        val initializer = HciAdapterInitializer(
            executor,
            resetTimeoutRetries = 1,
            beforeResetRetry = { settleCalls++ }
        )

        assertTrue(initializer.start())
        nowMs = 100L
        assertTrue(executor.checkTimeout())

        assertEquals(1, settleCalls)
        assertEquals(2, commands.size)
        assertEquals(HciAdapterInitializationState.RESETTING, initializer.state)
        nowMs = 200L
        assertTrue(executor.checkTimeout())
        assertEquals(HciAdapterInitializationState.FAILED, initializer.state)
    }

    @Test
    fun csrCanContinueActiveDiscoveryWhenPageScanIsUnsupported() {
        val commands = ArrayList<HciCommandPacket>()
        val executor = HciCommandExecutor(sendCommand = { commands.add(it); true })
        val initializer = HciAdapterInitializer(executor, allowPageScanFailure = true)

        assertTrue(initializer.start())
        assertTrue(executor.onEvent(commandComplete(HciOpcodes.RESET, byteArrayOf(0x00))))
        assertTrue(executor.onEvent(commandComplete(HciOpcodes.SET_EVENT_MASK, byteArrayOf(0x00))))
        assertTrue(executor.onEvent(commandComplete(HciOpcodes.READ_LOCAL_VERSION, localVersionResponse())))
        assertTrue(executor.onEvent(commandComplete(
            HciOpcodes.READ_BUFFER_SIZE,
            byteArrayOf(0x00, 0xfb.toByte(), 0x03, 0x00, 0x08, 0x00, 0x00, 0x00)
        )))
        assertTrue(executor.onEvent(commandComplete(
            HciOpcodes.WRITE_SIMPLE_PAIRING_MODE,
            byteArrayOf(0x00)
        )))
        assertTrue(executor.onEvent(commandComplete(
            HciOpcodes.WRITE_SCAN_ENABLE,
            byteArrayOf(0x01)
        )))

        assertEquals(HciOpcodes.READ_BD_ADDR, commands.last().opcode)
        assertTrue(executor.onEvent(commandComplete(
            HciOpcodes.READ_BD_ADDR,
            byteArrayOf(0x00, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11)
        )))
        assertEquals(HciAdapterInitializationState.READY, initializer.state)
        assertFalse(initializer.capabilities!!.pageScanEnabled)
    }

    @Test
    fun csrCanContinueActiveDiscoveryWhenPageScanTimesOut() {
        var nowMs = 0L
        val commands = ArrayList<HciCommandPacket>()
        val executor = HciCommandExecutor(
            sendCommand = { commands.add(it); true },
            monotonicTimeMs = { nowMs },
            commandTimeoutMs = 100L
        )
        val initializer = HciAdapterInitializer(executor, allowPageScanFailure = true)

        assertTrue(initializer.start())
        assertTrue(executor.onEvent(commandComplete(HciOpcodes.RESET, byteArrayOf(0x00))))
        assertTrue(executor.onEvent(commandComplete(HciOpcodes.SET_EVENT_MASK, byteArrayOf(0x00))))
        assertTrue(executor.onEvent(commandComplete(HciOpcodes.READ_LOCAL_VERSION, localVersionResponse())))
        assertTrue(executor.onEvent(commandComplete(
            HciOpcodes.READ_BUFFER_SIZE,
            byteArrayOf(0x00, 0xfb.toByte(), 0x03, 0x00, 0x08, 0x00, 0x00, 0x00)
        )))
        assertTrue(executor.onEvent(commandComplete(
            HciOpcodes.WRITE_SIMPLE_PAIRING_MODE,
            byteArrayOf(0x00)
        )))
        assertEquals(HciOpcodes.WRITE_SCAN_ENABLE, commands.last().opcode)

        nowMs = 100L
        assertTrue(executor.checkTimeout())
        assertEquals(HciOpcodes.READ_BD_ADDR, commands.last().opcode)

        assertTrue(executor.onEvent(commandComplete(
            HciOpcodes.READ_BD_ADDR,
            byteArrayOf(0x00, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11)
        )))
        assertEquals(HciAdapterInitializationState.READY, initializer.state)
        assertFalse(initializer.capabilities!!.pageScanEnabled)
    }

    private class Harness {
        val commands = ArrayList<HciCommandPacket>()
        val executor = HciCommandExecutor(
            sendCommand = { command -> commands.add(command); true }
        )
        val initializer = HciAdapterInitializer(executor)

        fun complete(opcode: Int, returnParameters: ByteArray): Boolean {
            return executor.onEvent(commandComplete(opcode, returnParameters))
        }
    }

    companion object {
        private fun localVersionResponse(): ByteArray {
            return byteArrayOf(
                0x00,
                0x09,
                0x34, 0x12,
                0x09,
                0x4c, 0x00,
                0x78, 0x56
            )
        }

        private fun commandComplete(opcode: Int, returnParameters: ByteArray): HciEventPacket {
            return HciEventPacket(
                eventCode = 0x0e,
                parameters = byteArrayOf(
                    0x01,
                    opcode.toByte(),
                    (opcode ushr 8).toByte()
                ) + returnParameters
            )
        }
    }
}
