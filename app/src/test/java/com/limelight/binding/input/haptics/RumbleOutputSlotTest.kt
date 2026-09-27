package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Test

class RumbleOutputSlotTest {
    private data class Frame(val amplitude: Int) {
        val isZero: Boolean get() = amplitude == 0
    }

    private class ManualScheduler {
        var nowMs = 0L
        private val queue = mutableListOf<Pair<Runnable, Long>>()
        val dispatched = mutableListOf<Int>()

        fun post(callback: Runnable, delayMs: Long) {
            queue += callback to (nowMs + delayMs)
        }

        fun remove(callback: Runnable) {
            queue.removeAll { it.first === callback }
        }

        fun advance(ms: Long) {
            nowMs += ms
            while (true) {
                val index = queue.indexOfFirst { it.second <= nowMs }
                if (index < 0) break
                queue.removeAt(index).first.run()
            }
        }
    }

    private fun slot(scheduler: ManualScheduler, pacing: Long = 33L) = RumbleOutputSlot<Frame>(
        pacingMs = { pacing },
        edgeFloorMs = 10L,
        isZero = { it.isZero },
        postDelayed = scheduler::post,
        removeCallbacks = scheduler::remove,
        clockMs = { scheduler.nowMs },
        dispatch = { scheduler.dispatched += it.amplitude }
    )

    @Test
    fun steadyUpdatesCollapseToTheLatestValue() {
        val scheduler = ManualScheduler()
        val slot = slot(scheduler)

        slot.submit(Frame(5))
        scheduler.advance(0)
        slot.submit(Frame(1))
        slot.submit(Frame(2))
        scheduler.advance(33)

        assertEquals(listOf(5, 2), scheduler.dispatched)
    }

    @Test
    fun shortPulseStartsAtTheEdgeDeadlineAndStopsAtItsOriginalEnd() {
        val scheduler = ManualScheduler()
        val slot = slot(scheduler)
        slot.submit(Frame(0))
        scheduler.advance(0)
        scheduler.advance(5)
        slot.submit(Frame(160))
        scheduler.advance(5)
        assertEquals(listOf(0, 160), scheduler.dispatched)
        scheduler.advance(22)
        slot.submit(Frame(0))
        scheduler.advance(0)
        assertEquals(listOf(0, 160, 0), scheduler.dispatched)
        scheduler.advance(100)
        assertEquals(listOf(0, 160, 0), scheduler.dispatched)
    }

    @Test
    fun expiredPendingPulseIsNotReplayedAtItsFallingEdge() {
        val scheduler = ManualScheduler()
        val slot = slot(scheduler)
        slot.submit(Frame(0))
        scheduler.advance(0)
        scheduler.advance(2)
        slot.submit(Frame(160))
        scheduler.advance(3)
        slot.submit(Frame(0))
        scheduler.advance(30)
        assertEquals(listOf(0), scheduler.dispatched)
    }

    @Test
    fun stopSupersedesAPendingLevelInsideTheEdgeFloor() {
        val scheduler = ManualScheduler()
        val slot = slot(scheduler)
        slot.submit(Frame(160))
        scheduler.advance(0)
        scheduler.advance(2)
        slot.submit(Frame(200))
        scheduler.advance(1)
        slot.submit(Frame(0))
        scheduler.advance(0)
        assertEquals(listOf(160, 0), scheduler.dispatched)
        scheduler.advance(100)
        assertEquals(listOf(160, 0), scheduler.dispatched)
    }

    @Test
    fun duplicateValuesAreNotRewritten() {
        val scheduler = ManualScheduler()
        val slot = slot(scheduler)

        slot.submit(Frame(5))
        scheduler.advance(0)
        scheduler.advance(33)
        slot.submit(Frame(5))
        scheduler.advance(33)

        assertEquals(listOf(5), scheduler.dispatched)
    }

    @Test
    fun cancelDropsPendingWork() {
        val scheduler = ManualScheduler()
        val slot = slot(scheduler)

        slot.submit(Frame(5))
        scheduler.advance(0)
        slot.submit(Frame(1))
        slot.cancel()
        scheduler.advance(100)

        assertEquals(listOf(5), scheduler.dispatched)
    }
}
