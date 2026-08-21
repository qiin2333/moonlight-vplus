package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HciCommandExecutorTest {
    @Test
    fun permitsOneCommandAndCorrelatesOnlyItsCompletion() {
        val sent = ArrayList<Int>()
        val results = ArrayList<HciCommandResult>()
        val executor = HciCommandExecutor(
            sendCommand = { command -> sent.add(command.opcode); true }
        )

        assertTrue(executor.submit(HciCommandPacket(0x1001), results::add))
        assertFalse(executor.submit(HciCommandPacket(0x1005), results::add))
        assertFalse(executor.onEvent(commandComplete(0x1005, byteArrayOf(0x00))))
        assertTrue(executor.hasPendingCommand())

        assertTrue(executor.onEvent(commandComplete(0x1001, byteArrayOf(0x00, 0x09))))
        assertFalse(executor.hasPendingCommand())
        assertEquals(listOf(0x1001), sent)
        val completed = results.single() as HciCommandResult.Completed
        assertEquals(HciCommandCompletionType.COMMAND_COMPLETE, completed.type)
        assertEquals(0, completed.controllerStatus)
    }

    @Test
    fun commandStatusReleasesGateAndCallbackCanSubmitNextCommand() {
        val sent = ArrayList<Int>()
        val results = ArrayList<HciCommandResult>()
        val executor = HciCommandExecutor(
            sendCommand = { command -> sent.add(command.opcode); true }
        )

        assertTrue(executor.submit(HciCommandPacket(0x0405)) { result ->
            results.add(result)
            assertTrue(executor.submit(HciCommandPacket(0x1009), results::add))
        })
        assertTrue(executor.onEvent(
            HciEventPacket(
                eventCode = 0x0f,
                parameters = byteArrayOf(0x00, 0x01, 0x05, 0x04)
            )
        ))

        assertEquals(listOf(0x0405, 0x1009), sent)
        val status = results.single() as HciCommandResult.Completed
        assertEquals(HciCommandCompletionType.COMMAND_STATUS, status.type)
        assertEquals(0, status.controllerStatus)
    }

    @Test
    fun timeoutFailsPendingCommandAndReleasesGate() {
        var nowMs = 10L
        val results = ArrayList<HciCommandResult>()
        val executor = HciCommandExecutor(
            sendCommand = { true },
            monotonicTimeMs = { nowMs },
            commandTimeoutMs = 100L
        )

        assertTrue(executor.submit(HciCommandPacket(0x0c03), results::add))
        nowMs = 109L
        assertFalse(executor.checkTimeout())
        nowMs = 110L
        assertTrue(executor.checkTimeout())
        assertTrue(results.single() is HciCommandResult.Failed)
        assertFalse(executor.hasPendingCommand())
    }

    private fun commandComplete(opcode: Int, returnParameters: ByteArray): HciEventPacket {
        return HciEventPacket(
            eventCode = 0x0e,
            parameters = byteArrayOf(0x01, opcode.toByte(), (opcode ushr 8).toByte()) +
                returnParameters
        )
    }
}
