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
    fun shortPulseBetweenTicksIsNotDropped() {
        val scheduler = ManualScheduler()
        val slot = slot(scheduler)

        slot.submit(Frame(5))
        scheduler.advance(0)
        assertEquals(listOf(5), scheduler.dispatched)

        scheduler.advance(5)
        slot.submit(Frame(1))
        scheduler.advance(10)
        slot.submit(Frame(0))
        // The on-edge had to be flushed before the zero overwrote it.
        assertEquals(listOf(5, 1), scheduler.dispatched)

        scheduler.advance(18)
        assertEquals(listOf(5, 1, 0), scheduler.dispatched)
    }

    @Test
    fun boundaryFlushIsRateLimitedByTheEdgeFloor() {
        val scheduler = ManualScheduler()
        val slot = slot(scheduler)

        slot.submit(Frame(5))
        scheduler.advance(0)
        scheduler.advance(5)
        slot.submit(Frame(1))
        // Only 3ms after the previous write: the flush is suppressed and Frame(1) is lost.
        // This bounds write amplification for pathologically fast boundary alternation.
        scheduler.advance(3)
        slot.submit(Frame(0))
        scheduler.advance(25)

        assertEquals(listOf(5, 0), scheduler.dispatched)
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
