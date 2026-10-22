package com.limelight.binding.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerMotionSenderTest {
    private data class Packet(val time: Long, val controller: Short, val type: Byte, val x: Float)

    /** Virtual monotonic clock; exercises tail delivery without sleeps or Android timers. */
    private class Fixture {
        var time = 0L
        var result = 0
        var failedType: Byte? = null
        val packets = mutableListOf<Packet>()
        val tasks = mutableMapOf<Runnable, Long>()
        val sender = ControllerMotionSender(
            nowNanos = { time },
            schedule = { task, delay -> tasks[task] = time + delay * 1_000_000L },
            cancel = { task -> tasks.remove(task); Unit },
            send = { c, t, x, _, _ ->
                packets += Packet(time, c, t, x)
                if (failedType == t) -2 else result
            }
        )

        fun advanceTo(ms: Long) {
            val target = ms * 1_000_000L
            while (true) {
                val next = tasks.minByOrNull { it.value } ?: break
                if (next.value > target) break
                time = next.value
                tasks.remove(next.key)
                next.key.run()
            }
            time = target
        }
        fun gyro(x: Float, controller: Short = 0) = sender.submit(controller, 2, x, 0f, 0f)
        fun frame(x: Float) = sender.submitFrame(0, x, 0f, 0f, -x, 1f, 0f)
        fun enableFrame() {
            sender.setReportRate(0, 1, 100)
            sender.setReportRate(0, 2, 100)
        }
    }

    @Test
    fun thousandHzSourceIsBoundedToHostRateAndFinalSampleSurvivesSourceSilence() {
        val f = Fixture()
        f.sender.setReportRate(0, 2, 100)
        repeat(1_000) {
            f.advanceTo(it.toLong())
            f.sender.setReportRate(0, 2, 100) // A repeated request must not reset the budget.
            f.gyro(it + 1f)
            assertTrue(f.tasks.size <= 1)
        }
        f.advanceTo(1_000)
        assertEquals(101, f.packets.size)
        assertEquals(1000f, f.packets.last().x)
        assertTrue(f.packets.zipWithNext().all { (a, b) -> b.time - a.time >= 10_000_000L })
        assertTrue(f.tasks.isEmpty())
    }

    @Test
    fun zeroGyroImmediatelyCancelsQueuedRotation() {
        val f = Fixture()
        f.sender.setReportRate(0, 2, 100)
        f.gyro(10f)
        f.advanceTo(1)
        f.gyro(20f)
        f.advanceTo(2)
        f.gyro(0f)
        assertEquals(2_000_000L, f.packets.last().time)
        f.advanceTo(100)
        assertEquals(listOf(10f, 0f), f.packets.map { it.x })
    }

    @Test
    fun disableStopsRotationAndDropsPendingAndFutureSamples() {
        val f = Fixture()
        f.sender.setReportRate(0, 2, 100)
        f.gyro(10f)
        f.advanceTo(1)
        f.gyro(20f)
        f.sender.setReportRate(0, 2, 0)
        f.gyro(30f)
        f.advanceTo(100)
        assertEquals(listOf(10f, 0f), f.packets.map { it.x })
        assertTrue(f.tasks.isEmpty())
    }

    @Test
    fun accelerometerGyroscopeAndPlayersHaveIndependentBudgets() {
        val f = Fixture()
        f.sender.setReportRate(0, 1, 50)
        f.sender.setReportRate(0, 2, 100)
        f.sender.setReportRate(1, 2, 200)
        repeat(21) {
            f.advanceTo(it.toLong())
            f.sender.submit(0, 1, 1f, 0f, 0f)
            f.gyro(1f)
            f.gyro(1f, 1)
        }
        assertEquals(2, f.packets.count { it.controller.toInt() == 0 && it.type.toInt() == 1 })
        assertEquals(3, f.packets.count { it.controller.toInt() == 0 && it.type.toInt() == 2 })
        assertEquals(5, f.packets.count { it.controller.toInt() == 1 })
    }

    @Test
    fun sourceReplacementPreservesDemandButRemovingTheHostSlotClearsIt() {
        for (clearDemand in listOf(false, true)) {
            val f = Fixture()
            f.gyro(9f)
            assertTrue(f.packets.isEmpty()) // No host request yet.
            f.sender.setReportRate(0, 2, 100)
            f.sender.submit(0, 1, 1f, 2f, 3f) // Gyro demand must not enable acceleration.
            f.gyro(1f)
            f.gyro(2f)
            f.sender.reset(0, clearDemand)
            f.gyro(3f)
            f.advanceTo(100)
            assertEquals(if (clearDemand) listOf(1f) else listOf(1f, 3f), f.packets.map { it.x })
            assertTrue(f.tasks.isEmpty())
            f.sender.setReportRate(0, 2, 100)
            f.gyro(4f)
            assertEquals(4f, f.packets.last().x)
        }
    }

    @Test
    fun failedSendRetriesLatestSampleWithoutExceedingTheRate() {
        val f = Fixture()
        f.sender.setReportRate(0, 2, 100)
        f.result = -2
        f.gyro(1f)
        f.advanceTo(1)
        f.gyro(2f)
        assertEquals(1, f.packets.size)
        f.result = 0
        f.advanceTo(10)
        assertEquals(listOf(1f, 2f), f.packets.map { it.x })
    }

    @Test
    fun streamStopCancelsAllTasksAndPreventsNewSends() {
        val f = Fixture()
        f.sender.setReportRate(0, 2, 100)
        f.gyro(1f)
        f.gyro(2f)
        f.sender.close()
        f.sender.setReportRate(0, 2, 100)
        f.gyro(3f)
        f.sender.stopGyro(0)
        f.advanceTo(100)
        assertEquals(listOf(1f), f.packets.map { it.x })
        assertTrue(f.tasks.isEmpty())
    }

    @Test
    fun localAssistantCancelsHostMotionOnlyOnceAndPreservesDemand() {
        val f = Fixture()
        f.sender.setReportRate(0, 2, 100)
        f.gyro(10f)
        f.gyro(20f)
        repeat(20) { f.sender.pauseGyro(0) }
        f.advanceTo(100)
        assertEquals(listOf(10f, 0f), f.packets.map { it.x })
        f.gyro(30f)
        assertEquals(30f, f.packets.last().x)
    }

    @Test
    fun failedFocusLossStopIsRetriedWithoutRevivingTheOldRotation() {
        val f = Fixture()
        f.sender.setReportRate(0, 2, 100)
        f.gyro(10f)
        f.gyro(20f)
        f.result = -2
        f.sender.stopGyro(0)
        f.result = 0
        f.advanceTo(10)
        assertEquals(listOf(10f, 0f, 0f), f.packets.map { it.x })
        f.advanceTo(20)
        f.gyro(30f)
        assertEquals(30f, f.packets.last().x)
    }

    @Test
    fun pairedTailUsesOneTaskAndTheLatestCompleteFrameIncludingStop() {
        val f = Fixture()
        f.enableFrame()
        f.frame(1f)
        f.advanceTo(4); f.frame(2f)
        f.advanceTo(8); f.frame(3f)
        assertEquals(1, f.tasks.size)
        f.advanceTo(10)
        f.advanceTo(11); f.frame(0f)
        f.advanceTo(100)
        assertEquals(listOf(1f, -1f, 3f, -3f, 0f, -0f), f.packets.map { it.x })
        assertTrue(f.packets.chunked(2).all { it[0].time == it[1].time })
        assertEquals(11_000_000L, f.packets.last().time)
        assertTrue(f.tasks.isEmpty())
    }

    @Test
    fun localAssistantCancelsPairedRotationButKeepsAccelerationAndCanResume() {
        val f = Fixture()
        f.enableFrame()
        f.frame(1f)
        f.advanceTo(1); f.frame(2f)
        f.sender.pauseGyro(0, onlyPaired = true)
        f.advanceTo(10)
        assertEquals(listOf(1f, 0f), f.packets.filter { it.type.toInt() == 2 }.map { it.x })
        assertEquals(-2f, f.packets.last().x)
        f.advanceTo(11); f.frame(3f)
        f.advanceTo(20)
        assertEquals(listOf(3f, -3f), f.packets.takeLast(2).map { it.x })
        f.frame(4f)
        f.sender.reset(0)
        f.sender.close()
        f.advanceTo(100)
        assertEquals(listOf(3f, -3f), f.packets.takeLast(2).map { it.x })
    }

    @Test
    fun unequalDemandUnpairsWithoutDroppingTheOtherSensorsPendingTail() {
        val f = Fixture()
        f.enableFrame()
        f.frame(1f)
        f.advanceTo(4); f.frame(2f)
        f.advanceTo(5); f.sender.setReportRate(0, 1, 50)
        f.advanceTo(10)
        assertEquals(2f, f.packets.last().x)
        f.advanceTo(11); f.frame(3f)
        assertEquals(-3f, f.packets.last().x)
        f.advanceTo(20)
        assertEquals(3f, f.packets.last().x)
        f.sender.setReportRate(0, 1, 100)
        f.frame(4f)
        f.advanceTo(30)
        assertEquals(listOf(4f, -4f), f.packets.takeLast(2).map { it.x })
        f.gyro(5f) // A phone/system sensor taking over must not be stopped by a rejected USB frame.
        f.sender.pauseGyro(0, onlyPaired = true)
        f.advanceTo(40)
        assertEquals(5f, f.packets.last().x)
    }

    @Test
    fun partialFrameFailureRetriesTheLatestPairAtTheSharedRate() {
        val f = Fixture()
        f.enableFrame()
        f.failedType = 1
        f.frame(1f)
        f.advanceTo(1); f.frame(2f)
        f.failedType = null
        f.advanceTo(10)
        assertEquals(listOf(1f, -1f, 2f, -2f), f.packets.map { it.x })
        assertTrue(f.tasks.isEmpty())
        f.failedType = 2
        f.advanceTo(11); f.frame(0f)
        f.advanceTo(12); f.frame(0f)
        assertEquals(6, f.packets.size) // The failed stop is immediate once, then rate-limited.
        f.failedType = null
        f.advanceTo(21)
        assertEquals(listOf(0L, 10_000_000L, 11_000_000L, 21_000_000L),
            f.packets.filter { it.type.toInt() == 2 }.map { it.time })
        assertTrue(f.tasks.isEmpty())
    }
}
