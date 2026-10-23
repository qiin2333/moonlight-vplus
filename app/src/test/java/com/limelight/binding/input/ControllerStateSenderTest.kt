package com.limelight.binding.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerStateSenderTest {
    private data class Packet(val controller: Short, val buttons: Int, val rx: Short)

    private class Fixture {
        var time = 0L
        val packets = mutableListOf<Packet>()
        var result = 0
        val sender = ControllerStateSender(nowNanos = { time }) { c, _, b, _, _, _, _, rx, _ ->
            packets += Packet(c, b, rx)
            result
        }
        fun submit(
            controller: Short = 0, mask: Short = 1, buttons: Int = 0, lt: Byte = 0, rt: Byte = 0,
            lx: Short = 0, ly: Short = 0, rx: Short = 0, ry: Short = 0, force: Boolean = false
        ) = sender.submit(controller, mask, buttons, lt, rt, lx, ly, rx, ry, force)
    }

    @Test
    fun idleHardwareReportsProduceOnlyTheInitialPacketAndOneSecondRefresh() {
        val f = Fixture()
        repeat(1_001) {
            f.time = it * 1_000_000L
            f.submit()
        }
        assertEquals(2, f.packets.size)
    }

    @Test
    fun rapidPressReleaseAndStickChangesAreNeverDelayedOrCollapsed() {
        val f = Fixture()
        f.submit()
        f.submit(buttons = 1)
        f.submit(buttons = 0)
        f.submit(rx = 1)
        f.submit(rx = 0)
        assertEquals(listOf(0, 1, 0, 0, 0), f.packets.map { it.buttons })
        assertEquals(listOf<Short>(0, 0, 0, 1, 0), f.packets.map { it.rx })
    }

    @Test
    fun everyWireFieldAndTheControllerMaskParticipatesInDeduplication() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.submit(mask = 3) }, { it.submit(buttons = 0x100000) },
            { it.submit(lt = 1) }, { it.submit(rt = 1) },
            { it.submit(lx = 1) }, { it.submit(ly = 1) },
            { it.submit(rx = 1) }, { it.submit(ry = 1) }
        )
        // Change one field at a time: reverting a previous field could otherwise
        // hide a missing comparison for the field this iteration claims to test.
        changes.forEach { change ->
            val f = Fixture()
            f.submit()
            change(f)
            assertEquals(2, f.packets.size)
        }
    }

    @Test
    fun cachesArePerPlayerAndResetOrForcedNeutralAlwaysSends() {
        val f = Fixture()
        repeat(2) {
            f.submit(controller = 0, mask = 3)
            f.submit(controller = 1, mask = 3)
        }
        f.sender.reset(0)
        f.submit(mask = 3)
        f.submit(mask = 3, force = true)
        f.submit(mask = 3)
        f.submit(controller = 1, mask = 3)
        assertEquals(listOf<Short>(0, 1, 0, 0), f.packets.map { it.controller })
    }

    @Test
    fun rejectedReleaseIsRetriedAndNeverCachesTheWrongHostState() {
        val f = Fixture()
        f.submit(buttons = 1)
        f.result = -2
        f.submit(buttons = 0)
        f.result = 0
        f.submit(buttons = 0)
        f.submit(buttons = 0)
        assertEquals(listOf(1, 0, 0), f.packets.map { it.buttons })
    }
}
