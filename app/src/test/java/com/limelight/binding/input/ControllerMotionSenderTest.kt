package com.limelight.binding.input

import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerMotionSenderTest {
    private data class Packet(val time: Long, val controller: Short, val type: Byte,
                              val x: Float, val y: Float = 0f, val z: Float = 0f)

    private class Fixture {
        var time = 0L
        var failedType: Byte? = null
        val packets = mutableListOf<Packet>()
        val tasks = mutableMapOf<Runnable, Long>()
        val sender = ControllerMotionSender(
            schedule = { task, delay -> tasks[task] = time + delay * 1_000_000L },
            cancel = { tasks.remove(it); Unit },
            send = { c, t, x, y, z ->
                packets += Packet(time, c, t, x, y, z)
                if (failedType == t) -2 else 0
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
        fun frame(x: Float) = sender.submitFrame(0, x, 0f, 0f, -x, 9.80665f, 0f)
        fun enable() {
            sender.setReportRate(0, 1, 100)
            sender.setReportRate(0, 2, 100)
        }
        fun gyros() = packets.filter { it.type.toInt() == 2 }
    }

    @Test
    fun sourceCadenceAndZeroMeanMotionSurviveZeroCrossingsWithoutResampling() {
        for (frame in listOf(false, true)) for (hz in listOf(0, 40, 99, 100, 101)) for (phase in listOf(0.0, PI)) {
            val f = Fixture().apply { enable() }
            val expected = (0L until 5_000L step 4).map { ms ->
                f.advanceTo(ms)
                val x = if (hz == 0) 1f else (round(sin(2 * PI * hz * ms / 1000 + phase) * 16) / 16).toFloat()
                val y = x * .5f
                val z = -x * .25f
                if (frame) f.sender.submitFrame(0, x, y, z, -x, 9.80665f, 1f)
                else f.sender.submit(0, 2, x, y, z)
                Packet(ms * 1_000_000, 0, 2, x, y, z)
            }
            val actual = f.gyros()
            assertEquals(expected.size, actual.size)
            expected.forEachIndexed { i, packet -> assertEquals(packet, actual[i]) }
            if (frame) assertEquals(expected.map { Packet(it.time, 0, 1, -it.x, 9.80665f, 1f) },
                f.packets.filter { it.type.toInt() == 1 })
            val angle = f.gyros().sumOf { it.x.toDouble() * .004 }
            assertEquals(if (hz == 0) 5.0 else 0.0, angle, 1e-8)
            f.advanceTo(6_000)
            assertEquals(expected.size, f.gyros().size) // Source silence must not replay movement.
            assertTrue(f.tasks.isEmpty())
        }
    }

    @Test
    fun hostDemandIsIndependentBySensorAndPlayerAndIdleStopsSendNothing() {
        val f = Fixture()
        f.sender.stopGyro(0)
        f.frame(9f)
        assertTrue(f.packets.isEmpty())
        f.sender.setReportRate(0, 1, 50)
        f.sender.setReportRate(1, 2, 100)
        f.frame(1f)
        f.gyro(2f, 1)
        f.sender.setReportRate(1, 2, 200)
        f.sender.setReportRate(1, 2, 0)
        f.gyro(3f, 1)
        f.sender.submit(0, 3, 1f, 0f, 0f)
        f.gyro(1f, -1)
        f.gyro(1f, 16)
        assertEquals(listOf(Packet(0, 0, 1, -1f, 9.80665f), Packet(0, 1, 2, 2f), Packet(0, 1, 2, 0f)), f.packets)
        assertTrue(f.tasks.isEmpty())
    }

    @Test
    fun sourceReplacementClearsRotationAndOldRetryCannotStopANewerSource() {
        val f = Fixture().apply { enable() }
        f.gyro(1f)
        f.advanceTo(1)
        f.failedType = 2
        f.sender.reset(0)
        val oldRetry = f.tasks.keys.single()
        f.sender.setReportRate(0, 2, 50)
        f.advanceTo(9)
        f.failedType = null
        f.gyro(2f)
        oldRetry.run()
        assertTrue(f.tasks.isEmpty())
        f.advanceTo(10)
        f.failedType = 2
        f.sender.stopGyro(0)
        oldRetry.run() // Even a second stop must not reactivate the obsolete callback.
        assertEquals(1, f.tasks.size)
        assertEquals(listOf(1f, 0f, 2f, 0f), f.gyros().map { it.x })
        f.failedType = null
        f.advanceTo(20)
        assertEquals(listOf(0L, 1L, 9L, 10L, 20L), f.gyros().map { it.time / 1_000_000 })
        assertTrue(f.tasks.isEmpty())
    }

    @Test
    fun onlyRejectedStopsRetryAtABoundedRateIncludingAfterHostDisable() {
        for (mode in 0..4) { // Capture, focus loss, host disable, source replacement, ordinary final zero.
            val f = Fixture().apply { enable() }
            f.frame(2f)
            f.advanceTo(1)
            f.failedType = 2
            when (mode) {
                0 -> f.sender.pauseGyro(0)
                1 -> f.sender.stopGyro(0)
                2 -> f.sender.setReportRate(0, 2, 0)
                3 -> f.sender.reset(0)
                else -> f.gyro(0f)
            }
            for (ms in 2L..10L) {
                f.advanceTo(ms)
                f.sender.stopGyro(0)
            }
            assertEquals(listOf(2f, 0f), f.gyros().map { it.x })
            assertEquals(1, f.tasks.size)
            f.failedType = null
            f.advanceTo(11)
            assertEquals(listOf(2f, 0f, 0f), f.gyros().map { it.x })
            assertEquals(11_000_000L, f.gyros().last().time)
            f.sender.submit(0, 1, 9f, 0f, 0f) // A gyro stop leaves accelerometer demand intact.
            assertEquals(1, f.packets.last().type.toInt())
            f.advanceTo(100)
            assertTrue(f.tasks.isEmpty())
            if (mode == 2) {
                f.gyro(9f)
                assertEquals(0f, f.gyros().last().x)
            }
        }
    }

    @Test
    fun rejectedMovementIsNotReplayedAndRejectedUsbFramesDoNotStopPhoneGyro() {
        val f = Fixture().apply { enable() }
        f.gyro(1f)
        f.failedType = 2
        f.frame(9f)
        f.sender.pauseGyro(0, onlyFromFrame = true)
        f.advanceTo(100)
        assertEquals(listOf(1f, 9f), f.gyros().map { it.x })
        assertEquals(-9f, f.packets.last().x) // Acceleration still forwards when gyro enqueue fails.
        assertTrue(f.tasks.isEmpty())
        f.failedType = null
        f.frame(2f)
        f.sender.pauseGyro(0, onlyFromFrame = true)
        f.gyro(3f)
        f.sender.pauseGyro(0, onlyFromFrame = true)
        assertEquals(listOf(1f, 9f, 2f, 0f, 3f), f.gyros().map { it.x })
    }

    @Test
    fun slotRemovalAndCloseCancelRetriesWithoutLeakingDemandOrOldCallbacks() {
        for (close in listOf(false, true)) {
            val f = Fixture().apply { enable() }
            f.gyro(1f)
            f.failedType = 2
            f.sender.stopGyro(0)
            val oldRetry = f.tasks.keys.single()
            if (close) f.sender.close() else f.sender.reset(0, clearDemand = true)
            f.failedType = null
            f.frame(2f)
            oldRetry.run()
            f.advanceTo(100)
            assertEquals(listOf(1f, 0f), f.gyros().map { it.x })
            assertTrue(f.tasks.isEmpty())
            f.sender.setReportRate(0, 2, 100)
            f.gyro(3f)
            oldRetry.run()
            assertEquals(if (close) 0f else 3f, f.gyros().last().x)
        }
    }
}
