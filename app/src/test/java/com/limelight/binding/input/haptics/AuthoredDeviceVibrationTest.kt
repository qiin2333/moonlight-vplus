package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class AuthoredDeviceVibrationTest {
    private class Runtime : AutoCloseable {
        var now = 0L
        var ticks = 0
        val writes = mutableListOf<Int>()
        val callbacks = mutableListOf<Pair<Runnable, Long>>()
        val worker = VirtualHapticsExecutor { now * 1_000_000L }
        val coordinator = DeviceVibrationCoordinator(
            { task, delay -> callbacks += task to delay }, { task -> callbacks.removeAll { it.first == task } },
            { amplitude, _ -> writes += amplitude }, { writes += 0 }, worker,
            playDeviceTick = { ticks++ }, clockMs = { now }, dispatcherClockNanos = { now * 1_000_000L })
        fun flush() = worker.runReady()
        override fun close() {
            worker.blocked = false
            coordinator.stop()
            flush()
        }
    }

    @Test fun enhancedWatchdogCannotInvalidateAHigherPriorityTouchWaitingForTheWorker() {
        Runtime().use { runtime ->
            runtime.coordinator.submitAuthoredIrDetail(false, 100, 30)
            runtime.coordinator.playTouchHaptic(20_000, 0, 500)
            runtime.now = 250
            runtime.callbacks.single { it.second == 200L }.first.run()
            runtime.flush()
            assertEquals(1, runtime.writes.count { it > 0 })
        }
    }

    @Test fun blockedExtendedContinuousBodyExpiresBeforeTheWorkerCanPlayIt() {
        Runtime().use { runtime ->
            runtime.worker.blocked = true
            runtime.coordinator.submitAuthoredIrBody(30_000, 0, 100, true, 100)
            runtime.now = 500
            runtime.worker.blocked = false
            runtime.flush()
            assertTrue(runtime.writes.none { it > 0 })
        }
    }

    @Test fun enhancedResetDropsPendingEventsWithoutInvalidatingQueuedTouch() {
        Runtime().use { runtime ->
            runtime.worker.blocked = true
            runtime.coordinator.playTouchHaptic(20_000, 0, 100)
            runtime.coordinator.submitAuthoredIrBody(0, 0, 100, true)
            runtime.coordinator.submitAuthoredIrDetail(true, 100, 30)
            runtime.coordinator.resetAuthoredIr()
            runtime.worker.blocked = false
            runtime.flush()
            assertEquals(1, runtime.writes.count { it > 0 })
            assertEquals(0, runtime.ticks)
        }
    }

    @Test fun endingLegacyAuthoredPlaybackCannotReviveAnOldOrdinaryRumble() {
        Runtime().use { runtime ->
            runtime.coordinator.submitGameRumble(DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
                20_000, 10_000, 100)
            runtime.flush()
            runtime.coordinator.submitAuthoredDetail(true, 100)
            runtime.flush()
            assertEquals(1, runtime.ticks)
            runtime.coordinator.submitAuthoredDetail(false, 100, active = false)
            runtime.flush()
            assertEquals(1, runtime.writes.count { it > 0 })
            assertEquals(0, runtime.writes.last())
        }
    }

    @Test fun detailTicksAvoidWaveformsAndYieldToAudioAndTouch() {
        for (enhanced in listOf(false, true)) Runtime().use { runtime ->
            fun tick() {
                if (enhanced) runtime.coordinator.submitAuthoredIrDetail(true, 100, runtime.now + 30)
                else runtime.coordinator.submitAuthoredDetail(true, 100)
                runtime.flush()
            }
            tick()
            assertEquals(1, runtime.ticks)
            assertTrue(runtime.writes.none { it > 0 })
            assertEquals(DeviceVibrationCoordinator.AudioClaimResult.OWNED_READY,
                runtime.coordinator.claimForAudio())
            tick()
            assertEquals(1, runtime.ticks)
            runtime.coordinator.releaseFromAudio()
            runtime.coordinator.playTouchHaptic(3_000, 3_000, 50)
            tick()
            assertEquals(1, runtime.ticks)
        }
    }

    @Test fun mutedLegacyTicksStaySilentThroughWatchdogAndEnd() {
        Runtime().use { runtime ->
            runtime.coordinator.submitAuthoredDetail(true, 0)
            runtime.flush()
            runtime.now = 250
            runtime.callbacks.single { it.second == 200L }.first.run()
            runtime.flush()
            runtime.coordinator.submitAuthoredDetail(false, 100, active = false)
            runtime.flush()
            assertEquals(0, runtime.ticks)
            assertTrue(runtime.writes.none { it > 0 })
        }
    }
}
