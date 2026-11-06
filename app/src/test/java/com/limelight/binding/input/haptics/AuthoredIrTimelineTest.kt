package com.limelight.binding.input.haptics

import com.limelight.nvstream.*
import org.junit.Assert.*
import org.junit.Test

class AuthoredIrTimelineTest {
    private val lane = Ds5HapticsIrExtendedLane(0.1f, 0.2f)
    private fun frame(seq: Int, at: Long = seq * 5_000L, epoch: Int = 1,
                      events: List<Ds5HapticsIrExtendedEvent> = emptyList(), flags: Int = 0) =
        Ds5HapticsIrExtendedFrame(Ds5HapticsIrFrame(0, flags, seq, at, 240,
            Ds5HapticsIrLane(.3f, .4f, 0f, .5f, 200f), Ds5HapticsIrLane(.3f, .4f, 0f, .5f, 200f)),
            epoch, at, lane, lane, events)
    private fun event(id: Int, at: Long) = Ds5HapticsIrExtendedEvent(id, at, .2f, .1f)

    @Test fun sourceClockDoesNotMakeABackloggedPacketFreshAgain() {
        val timeline = AuthoredIrTimeline()
        timeline.accept(frame(0, 0), 1_000_000)
        timeline.poll(1_020_000)
        assertFalse(timeline.accept(frame(1, 5_000, events = listOf(event(1, 5_000))), 1_180_000))
        assertNull(timeline.poll(1_180_000))
        val expired = timeline.poll(1_210_000)!!
        assertTrue(expired.ended)
        assertTrue(expired.body.isZero)
    }

    @Test fun networkGapKeepsQueuedRedundantEventAndReorderedFramesCanSupplyMissingEvents() {
        val timeline = AuthoredIrTimeline()
        timeline.accept(frame(1, 5_000, events = listOf(event(1, 5_000))), 1_000_000)
        assertFalse(timeline.accept(frame(1, 5_000, events = listOf(event(1, 5_000))), 1_001_000))
        timeline.accept(frame(3, 15_000, events = listOf(event(1, 5_000))), 1_010_000)
        // Frame 2 arrives late: keep the latest body, but recover its still-fresh event.
        assertTrue(timeline.accept(frame(2, 10_000, events = listOf(event(2, 10_000))), 1_011_000))
        assertEquals(1, timeline.poll(1_020_000)!!.event!!.value.eventId)
        assertEquals(2, timeline.poll(1_025_000)!!.event!!.value.eventId)
        assertNull(timeline.poll(1_025_000))
        // IDs can repeat after restart; a delayed END from the retired epoch cannot stop it.
        assertTrue(timeline.accept(frame(0, 0, 2, listOf(event(1, 0))), 1_030_000))
        assertEquals(1, timeline.poll(1_050_000)!!.event!!.value.eventId)
        assertFalse(timeline.accept(frame(4, flags = Ds5HapticsIrFrame.FLAG_STREAM_END), 1_051_000))
    }

    @Test fun playoutLeadDoesNotExtendEventFreshness() {
        val timeline = AuthoredIrTimeline()
        timeline.accept(frame(1, events = listOf(event(1, 5_000))), 1_000_000)
        assertNull(timeline.poll(1_010_000)!!.event)
        assertNull(timeline.poll(1_031_000)!!.event)
    }

    @Test fun discontinuityEndAndSequenceWrapHaveExplicitLifetimes() {
        val timeline = AuthoredIrTimeline()
        timeline.accept(frame(-1, 0, events = listOf(event(1, 0))), 10_000)
        assertTrue(timeline.accept(frame(0, 5_000, flags = Ds5HapticsIrFrame.FLAG_DISCONTINUITY), 15_000))
        assertNull(timeline.poll(15_000)!!.event)
        timeline.accept(frame(1, 10_000, flags = Ds5HapticsIrFrame.FLAG_STREAM_END), 20_000)
        assertTrue(timeline.poll(20_000)!!.ended)
        assertFalse(timeline.accept(frame(2, 15_000), 25_000))
        assertTrue(timeline.accept(frame(0, 0, epoch = 2), 30_000))
    }

    @Test fun sourceResetIsPropagatedAndBurstClockCorrectionsDoNotReverseBodyOrder() {
        val timeline = AuthoredIrTimeline()
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
    @Test fun sourceClockDriftDoesNotPermanentlyExpireTheStream() {
        val timeline = AuthoredIrTimeline()
        repeat(3600) { seq ->
            val now = 1_000_000L + seq * 1_000_000L
            val source = seq * 999_950L // Host runs 50 ppm slower for one hour.
            assertTrue("clock drift at $seq", timeline.accept(frame(seq, source), now))
            assertFalse(timeline.poll(now + 20_000)!!.ended)
        }
    }

    @Test fun aSourcePauseRecoversOnlyAfterSustainedProgress() {
        val timeline = AuthoredIrTimeline()
        timeline.accept(frame(0, 0), 1_000_000)
        timeline.poll(1_020_000)
        assertFalse(timeline.accept(frame(1, 5_000), 3_000_000))
        assertTrue(timeline.poll(3_000_000)!!.ended)
        var accepted = false
        for (seq in 2..221) {
            val now = 3_000_000L + (seq - 1) * 5_000L
            accepted = timeline.accept(frame(seq), now)
            timeline.poll(now + 20_000)
        }
        assertTrue(accepted)
    }

}
