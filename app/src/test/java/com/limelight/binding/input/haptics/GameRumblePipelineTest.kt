package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GameRumblePipelineTest {
    private class Rig : AutoCloseable {
        var now = 0L
        val tasks = mutableListOf<Pair<Runnable, Long>>()
        val controllerWrites = mutableListOf<MixedRumbleState>()
        val deviceWrites = Collections.synchronizedList(mutableListOf<Int>())
        var targets = GameRumbleContext(GameRumbleMode.COORDINATED, true, true)
        val worker = Executors.newSingleThreadScheduledExecutor()
        val device = DeviceVibrationCoordinator(
            postDelayed = ::post, removeCallback = ::remove,
            vibrateDevice = { amplitude, _ -> deviceWrites.add(amplitude) },
            cancelDeviceVibration = { deviceWrites.add(0) },
            executor = worker, minimumIntervalMs = 0L
        )
        val renderer = RumbleOutputRenderer(
            ControllerHapticsMixer(), ControllerHapticsMixer(), { now }, ::post, ::remove,
            { targets.hasController }, { 33L }, { controllerWrites.add(it) },
            { device.submitGameRumble(DeviceVibrationCoordinator.GameSource.ROUTED_GAME, it, 100) }
        )
        val pipeline = GameRumblePipeline(renderer, { targets }, { now }, ::post, ::remove)

        fun post(callback: Runnable, delay: Long) { tasks.add(callback to now + delay) }
        fun remove(callback: Runnable) { tasks.removeAll { it.first === callback } }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.minByOrNull { it.second } ?: break
                if (next.second > end) break
                tasks.remove(next)
                now = next.second
                next.first.run()
                flush()
            }
            now = end
            flush()
        }
        fun flush() { worker.submit {}.get(2, TimeUnit.SECONDS) }
        fun host(low: Float, high: Float, player: Short = 0) {
            pipeline.submitHost(player, ControllerRumbleState(low, high))
            advance(0)
        }
        override fun close() {
            pipeline.stop()
            renderer.stop()
            device.stop()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun heldLowSettlesBodyToZeroWithoutStoppingController() = Rig().use { r ->
        r.host(1f, 0f)
        assertTrue(r.deviceWrites.last() > 0)
        r.advance(250)
        assertEquals(0, r.deviceWrites.last())
        assertEquals(ControllerRumbleState(1f, 0f), r.controllerWrites.last().output)
        assertTrue(r.tasks.isEmpty())
    }

    @Test fun repeatedPulsesRestartAndStopThroughTheActualDeviceWorker() = Rig().use { r ->
        repeat(3) {
            r.host(0f, 1f)
            r.advance(10)
            assertTrue(r.deviceWrites.last() > 0)
            r.advance(22)
            r.host(0f, 0f)
            assertEquals(0, r.deviceWrites.last())
            assertTrue(r.controllerWrites.last().isZero)
            r.advance(100)
        }
    }

    @Test fun diagnosticInputCannotResetHeldHostFeatures() = Rig().use { r ->
        r.host(1f, 0f)
        r.advance(250)
        val writes = r.deviceWrites.toList()
        r.pipeline.submitTest(0, ControllerRumbleState(0f, 1f))
        r.advance(40)
        r.pipeline.submitTest(0, ControllerRumbleState.ZERO)
        r.pipeline.replay(0)
        r.advance(40)
        assertEquals(writes, r.deviceWrites.toList())
        assertEquals(ControllerRumbleState(1f, 0f), r.controllerWrites.last().output)
    }

    @Test fun pulseExpiredBeforeTheStartDeadlineNeverReachesEitherSink() = Rig().use { r ->
        r.host(0f, 0f)
        r.advance(1)
        r.host(0f, 1f)
        r.advance(4)
        r.host(0f, 0f)
        r.advance(50)
        assertTrue(r.controllerWrites.all { it.isZero })
        assertTrue(r.deviceWrites.all { it == 0 })
    }

    @Test fun otherPlayersCannotDriveOrClearTheSharedBody() = Rig().use { r ->
        r.host(0f, 1f)
        r.advance(250)
        val writes = r.deviceWrites.toList()
        r.host(1f, 0f, 1)
        r.host(0f, 0f, 1)
        r.advance(40)
        assertEquals(writes, r.deviceWrites.toList())
    }

    @Test fun disconnectFallbackAndReconnectPreserveHeldState() = Rig().use { r ->
        r.host(1f, 0f)
        r.advance(250)
        r.targets = r.targets.copy(hasController = false)
        r.renderer.resetController(0)
        r.pipeline.replay(0)
        r.advance(40)
        assertTrue(r.deviceWrites.last() > 0)
        r.targets = r.targets.copy(hasController = true)
        r.renderer.resetController(0)
        r.pipeline.replay(0)
        r.advance(40)
        assertEquals(0, r.deviceWrites.last())
        assertEquals(ControllerRumbleState(1f, 0f), r.controllerWrites.last().output)
    }

    @Test fun audioOwnershipRestoresTheLatestRenderedGameLevel() = Rig().use { r ->
        r.host(0f, 1f)
        assertTrue(r.device.claimForAudio())
        r.flush()
        val writes = r.deviceWrites.size
        r.advance(250)
        assertEquals(writes, r.deviceWrites.size)
        r.device.releaseFromAudio()
        r.flush()
        assertTrue(r.deviceWrites.last() in 16..48)
        r.host(0f, 0f)
        assertEquals(0, r.deviceWrites.last())
    }

    @Test fun terminalStopCancelsPendingTicksAndOutput() = Rig().use { r ->
        r.host(0f, 1f)
        r.pipeline.stop()
        r.renderer.stop()
        r.device.stop()
        assertTrue(r.worker.awaitTermination(2, TimeUnit.SECONDS))
        val count = r.controllerWrites.size
        assertTrue(r.tasks.isEmpty())
        r.pipeline.submitHost(0, ControllerRumbleState(1f, 1f))
        r.pipeline.replay(0)
        assertEquals(count, r.controllerWrites.size)
        assertEquals(0, r.deviceWrites.last())
    }

    @Test fun advanceAndDuplicateSamplesDoNotCreateSignalChanges() {
        val tracker = RumbleSignalTracker()
        val start = tracker.sample(ControllerRumbleState(0f, 1f), 0)
        val tick = tracker.advance(20)
        val duplicate = tracker.sample(ControllerRumbleState(0f, 1f), 20)
        assertEquals(start.revision, tick.revision)
        assertEquals(tick, duplicate)
        assertEquals(0L, tick.changedAtMs)
        assertTrue(tick.decomposition.transientHigh < start.decomposition.transientHigh)
        val stop = tracker.sample(ControllerRumbleState.ZERO, 25)
        assertEquals(start.revision + 1, stop.revision)
        assertEquals(-1f, stop.highDelta, 0f)
        assertFalse(stop.decomposition.hasUnsettledTransients)
    }
}
