package com.limelight.binding.input.haptics

import com.limelight.nvstream.*
import org.junit.Assert.*
import org.junit.Test

class AuthoredV3TimelineTest {
    private val lane = Ds5HapticsIrV3Lane(0.1f, 0.2f, 0.3f, 0.4f)
    private fun frame(seq: Int, at: Long = seq * 5_000L, epoch: Int = 1,
                      events: List<Ds5HapticsIrV3Event> = emptyList(), flags: Int = 0) =
        Ds5HapticsIrV3Frame(0, flags, 1, epoch, seq, at, 240, lane, lane, events)
    private fun event(id: Int, at: Long) = Ds5HapticsIrV3Event(id, at, .2f, .1f, 1f, .7f)

    @Test fun everySourceEventAtSixtyPerSecondSurvivesWithoutAHardRefractory() {
        val timeline = AuthoredV3Timeline()
        var count = 0
        repeat(240) { seq ->
            val sourceUs = (seq + 1) * 5_000L
            val eventIndex = sourceUs / 16_667
            val previousIndex = (sourceUs - 5_000) / 16_667
            val list = if (eventIndex > previousIndex) listOf(event(eventIndex.toInt(), eventIndex * 16_667)) else emptyList()
            assertTrue(timeline.accept(frame(seq, sourceUs, events = list), sourceUs + 8_000_000))
            if (timeline.poll(sourceUs + 8_000_000)?.event != null) count++
        }
        repeat(4) { if (timeline.poll(9_205_000 + it * 5_000L)?.event != null) count++ }
        assertEquals(71, count)
    }

    @Test fun duplicateEventsReorderedFramesAndRetiredEpochsCannotReplay() {
        val timeline = AuthoredV3Timeline()
        assertTrue(timeline.accept(frame(1, events = listOf(event(7, 5_000))), 1_000_000))
        assertFalse(timeline.accept(frame(1, events = listOf(event(7, 5_000))), 1_001_000))
        assertTrue(timeline.accept(frame(2, events = listOf(event(7, 5_000))), 1_005_000))
        assertNotNull(timeline.poll(1_020_000)!!.event)
        assertTrue(timeline.accept(frame(0, 0, 2, listOf(event(7, 0))), 1_030_000))
        assertNotNull(timeline.poll(1_050_000)!!.event)
        assertFalse(timeline.accept(frame(3, epoch = 1), 1_055_000))
    }

    @Test fun stalledConsumerDropsOldSlotsInsteadOfReplayingABurst() {
        val timeline = AuthoredV3Timeline()
        repeat(500) { seq -> timeline.accept(frame(seq, events = listOf(event(seq, seq * 5_000L))), seq * 5_000L) }
        assertTrue(timeline.queuedEvents <= AuthoredV3Timeline.MAX_EVENTS)
        assertNotNull(timeline.poll(2_500_000)!!.event)
        assertNull(timeline.poll(2_500_000))
        assertTrue(timeline.poll(2_800_000)!!.ended)
        assertEquals(0, timeline.queuedEvents)
    }

    @Test fun sourceClockDoesNotMakeABackloggedPacketFreshAgain() {
        val timeline = AuthoredV3Timeline()
        timeline.accept(frame(0, 0), 1_000_000)
        timeline.poll(1_020_000)
        assertFalse(timeline.accept(frame(1, 5_000, events = listOf(event(1, 5_000))), 1_180_000))
        assertNull(timeline.poll(1_180_000))
        val expired = timeline.poll(1_210_000)!!
        assertTrue(expired.ended)
        assertTrue(expired.body.isZero)
    }

    @Test fun networkGapKeepsQueuedRedundantEventAndReorderedFramesCanSupplyMissingEvents() {
        val timeline = AuthoredV3Timeline()
        timeline.accept(frame(1, 5_000, events = listOf(event(1, 5_000))), 1_000_000)
        timeline.accept(frame(3, 15_000, events = listOf(event(1, 5_000))), 1_010_000)
        // Frame 2 arrives late: keep the latest body, but recover its still-fresh event.
        assertTrue(timeline.accept(frame(2, 10_000, events = listOf(event(2, 10_000))), 1_011_000))
        assertEquals(1, timeline.poll(1_020_000)!!.event!!.value.eventId)
        assertEquals(2, timeline.poll(1_025_000)!!.event!!.value.eventId)
        assertNull(timeline.poll(1_025_000))
    }

    @Test fun playoutLeadDoesNotExtendEventFreshness() {
        val timeline = AuthoredV3Timeline()
        timeline.accept(frame(1, events = listOf(event(1, 5_000))), 1_000_000)
        assertNull(timeline.poll(1_010_000)!!.event)
        assertNull(timeline.poll(1_031_000)!!.event)
    }

    @Test fun legacyZeroDoesNotClearAuthoredBodyAndAuthoredEndDoesNotClearRealHost() {
        val mixer = ControllerHapticsMixer()
        val body = ControllerRumbleState(.4f, .1f)
        mixer.submit(0, RumbleSource.AUTHORED_BODY, body, 0, 200)
        assertEquals(body, mixer.submit(0, RumbleSource.HOST, ControllerRumbleState.ZERO, 1).output)
        mixer.submit(0, RumbleSource.HOST, ControllerRumbleState(.2f, .3f), 2)
        mixer.clearSource(0, RumbleSource.AUTHORED_BODY, 3)
        assertEquals(ControllerRumbleState(.2f, .3f), mixer.clearSource(0, RumbleSource.AUTHORED_TRANSIENT, 3).output)
    }

    @Test fun discontinuityEndAndSequenceWrapHaveExplicitLifetimes() {
        val timeline = AuthoredV3Timeline()
        timeline.accept(frame(-1, 0, events = listOf(event(1, 0))), 10_000)
        assertTrue(timeline.accept(frame(0, 5_000, flags = Ds5HapticsIrFrame.FLAG_DISCONTINUITY), 15_000))
        assertNull(timeline.poll(15_000)!!.event)
        timeline.accept(frame(1, 10_000, flags = Ds5HapticsIrFrame.FLAG_STREAM_END), 20_000)
        assertTrue(timeline.poll(20_000)!!.ended)
        assertFalse(timeline.accept(frame(2, 15_000), 25_000))
        assertTrue(timeline.accept(frame(0, 0, epoch = 2), 30_000))
    }

    @Test fun phoneKeepsContinuousBodyAndCoordinatedControllerKeepsIndependentDetails() {
        val policy = AuthoredV3PhonePolicy()
        val body = ControllerRumbleState(.2f, .1f)
        assertFalse(policy.useDetails(body, 100, false, GameRumbleMode.DEVICE, false, true))
        assertFalse(policy.useDetails(ControllerRumbleState.ZERO, 150, false, GameRumbleMode.DEVICE, false, true))
        assertTrue(policy.useDetails(ControllerRumbleState.ZERO, 181, false, GameRumbleMode.DEVICE, false, true))
        assertTrue(policy.useDetails(body, 200, false, GameRumbleMode.COORDINATED, true, true))
        assertFalse(policy.useDetails(body, 200, false, GameRumbleMode.COORDINATED, true, false))
    }

    @Test fun sourceResetIsPropagatedAndBurstClockCorrectionsDoNotReverseBodyOrder() {
        val timeline = AuthoredV3Timeline()
        timeline.accept(frame(1, 5_000).copy(left = lane.copy(continuousLow = .1f), right = lane.copy(continuousLow = .1f)), 1_000_000)
        timeline.accept(frame(2, 10_000).copy(left = lane.copy(continuousLow = .2f), right = lane.copy(continuousLow = .2f)), 1_000_000)
        timeline.accept(frame(3, 15_000).copy(left = lane.copy(continuousLow = .3f), right = lane.copy(continuousLow = .3f)), 1_000_000)
        val output = timeline.poll(1_020_000)!!
        assertTrue(output.reset)
        assertEquals(.3f, output.body.lowFrequency)
        timeline.accept(frame(0, 0, 2), 1_030_000)
        val reset = timeline.poll(1_030_000)!!
        assertTrue(reset.reset)
        assertTrue(reset.body.isZero)
    }
}
