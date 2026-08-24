package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HciLiveLinkTunerTest {
    @Test
    fun tunesOnlyAfterValidInputAndExitsLaterSniffMode() {
        var nowMs = 100L
        val commands = ArrayList<PendingCommand>()
        val tuner = HciLiveLinkTuner(
            submitCommand = { packet, callback ->
                commands += PendingCommand(packet, callback)
                true
            },
            monotonicTimeMs = { nowMs }
        )

        tuner.checkProgress()
        assertTrue(commands.isEmpty())

        tuner.onFirstValidInput(0x0042)
        assertCommand(
            commands.single().packet,
            HciOpcodes.WRITE_LINK_POLICY_SETTINGS,
            byteArrayOf(0x42, 0x00, 0x00, 0x00)
        )
        commands.removeAt(0).complete()

        nowMs = 599L
        tuner.checkProgress()
        assertTrue(commands.isEmpty())
        nowMs = 600L
        tuner.checkProgress()
        assertCommand(
            commands.single().packet,
            HciOpcodes.WRITE_LINK_SUPERVISION_TIMEOUT,
            byteArrayOf(0x42, 0x00, 0x00, 0x50)
        )
        commands.removeAt(0).complete()
        assertEquals(HciLiveLinkTuningState.TUNED, tuner.state)

        assertTrue(tuner.onEvent(modeChange(0x0042, mode = 0x02)))
        assertCommand(
            commands.single().packet,
            HciOpcodes.EXIT_SNIFF_MODE,
            byteArrayOf(0x42, 0x00)
        )
        commands.removeAt(0).complete()

        assertTrue(tuner.onEvent(modeChange(0x0042, mode = 0x00)))
        assertTrue(commands.isEmpty())
    }

    @Test
    fun optionalCommandsRetryWhenTheSharedGateIsBusy() {
        var accepts = false
        val commands = ArrayList<PendingCommand>()
        val tuner = HciLiveLinkTuner(
            submitCommand = { packet, callback ->
                if (accepts) commands += PendingCommand(packet, callback)
                accepts
            }
        )

        tuner.onFirstValidInput(0x0001)
        assertTrue(commands.isEmpty())
        assertEquals(HciLiveLinkTuningState.TUNING, tuner.state)

        accepts = true
        tuner.checkProgress()
        assertEquals(HciOpcodes.WRITE_LINK_POLICY_SETTINGS, commands.single().packet.opcode)
    }

    @Test
    fun ignoresOtherLinksAndResetsOnDisconnect() {
        val commands = ArrayList<PendingCommand>()
        val tuner = HciLiveLinkTuner(
            submitCommand = { packet, callback ->
                commands += PendingCommand(packet, callback)
                true
            }
        )
        tuner.onFirstValidInput(0x0042)

        assertFalse(tuner.onEvent(modeChange(0x0043, mode = 0x02)))
        tuner.onLinkDisconnected(0x0042)
        assertEquals(HciLiveLinkTuningState.IDLE, tuner.state)
    }

    private data class PendingCommand(
        val packet: HciCommandPacket,
        val callback: (HciCommandResult) -> Unit
    ) {
        fun complete() {
            callback(HciCommandResult.Completed(
                packet.opcode,
                HciCommandCompletionType.COMMAND_COMPLETE,
                0,
                byteArrayOf(0x00)
            ))
        }
    }

    private fun modeChange(handle: Int, mode: Int): HciEventPacket = HciEventPacket(
        0x14,
        byteArrayOf(
            0x00,
            handle.toByte(),
            (handle ushr 8).toByte(),
            mode.toByte(),
            0x00,
            0x00
        )
    )

    private fun assertCommand(packet: HciCommandPacket, opcode: Int, parameters: ByteArray) {
        assertEquals(opcode, packet.opcode)
        assertEquals(parameters.toList(), packet.parameters.toList())
    }
}
