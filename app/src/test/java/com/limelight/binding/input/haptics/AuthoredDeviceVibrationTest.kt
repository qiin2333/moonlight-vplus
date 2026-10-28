package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AuthoredDeviceVibrationTest {
    @Test fun v3WatchdogCannotInvalidateAHigherPriorityTouchWaitingForTheWorker() {
        var now = 0L
        val worker = VirtualHapticsExecutor { now * 1_000_000L }
        val callbacks = mutableListOf<Pair<Runnable, Long>>()
        var touches = 0
        val coordinator = DeviceVibrationCoordinator(
            { task, delay -> callbacks += task to delay }, { task -> callbacks.removeAll { it.first == task } },
            { _, _ -> touches++ }, {}, worker, playDeviceTick = {}, clockMs = { now },
            dispatcherClockNanos = { now * 1_000_000L })
        try {
            coordinator.submitAuthoredV3(false, 100, 30)
            coordinator.playTouchHaptic(20_000, 0, 500)
            now = 250
            callbacks.single { it.second == 200L }.first.run()
            worker.runReady()
            assertEquals("An expired IR lease must not cancel the pending touch", 1, touches)
        } finally { coordinator.stop(); worker.runReady() }
    }

    @Test fun blockedV3ContinuousBodyExpiresWithoutChangingOrdinaryHostLifetime() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val now = AtomicLong()
        val waves = AtomicInteger()
        val coordinator = DeviceVibrationCoordinator({ _, _ -> }, {}, { _, _ -> waves.incrementAndGet() },
            {}, worker, playDeviceTick = {}, clockMs = now::get)
        try {
            coordinator.submitAuthoredV3Body(30_000, 0, 100, true, 100)
            now.set(500)
            release.countDown()
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(0, waves.get())
        } finally { release.countDown(); coordinator.stop(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun v3ResetCannotInvalidateAnAlreadyQueuedHigherPriorityTouch() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waves = AtomicInteger()
        worker.execute { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val coordinator = DeviceVibrationCoordinator({ _, _ -> }, {}, { _, _ -> waves.incrementAndGet() },
            {}, worker, playDeviceTick = {}, clockMs = { 0L })
        try {
            coordinator.playTouchHaptic(20_000, 0, 100)
            coordinator.submitAuthoredV3Body(0, 0, 100, true)
            coordinator.submitAuthoredV3(true, 100, 30)
            coordinator.resetAuthoredV3()
            release.countDown()
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1, waves.get())
        } finally { release.countDown(); coordinator.stop(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun v3OwnershipPreservesLatestOrdinaryHostAndItsExplicitZero() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val coordinator = DeviceVibrationCoordinator({ _, _ -> }, {}, { amp, _ -> writes += amp },
            { writes += 0 }, worker, playDeviceTick = {}, clockMs = { 0L })
        fun flush() { worker.submit {}.get(2, TimeUnit.SECONDS) }
        try {
            coordinator.submitAuthoredV3Body(0, 0, 100, true)
            coordinator.submitAuthoredV3(true, 100, 30)
            flush()
            coordinator.submitGameRumble(DeviceVibrationCoordinator.GameSource.ROUTED_GAME, 30_000, 0, 100)
            coordinator.submitAuthoredV3Body(0, 0, 100, false)
            coordinator.submitAuthoredV3(false, 0, 0, false)
            flush()
            assertTrue("The new ordinary source must resume at authored END", writes.last() > 0)
            coordinator.submitAuthoredV3Body(0, 0, 100, true)
            coordinator.submitAuthoredV3(true, 100, 30)
            flush()
            coordinator.submitGameRumble(DeviceVibrationCoordinator.GameSource.ROUTED_GAME, 0, 0, 100)
            coordinator.resetAuthoredV3()
            flush()
            assertEquals("An ordinary zero during authored playback must remain zero", 0, writes.last())
        } finally { coordinator.stop(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun v3TicksHaveNoDeviceModelGateAndSourceResetInvalidatesPendingEvents() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val now = AtomicLong()
        val ticks = AtomicInteger()
        val coordinator = DeviceVibrationCoordinator({ _, _ -> }, {}, { _, _ -> }, {}, worker,
            playDeviceTick = { ticks.incrementAndGet() }, clockMs = now::get)
        try {
            repeat(8) {
                coordinator.submitAuthoredV3Body(0, 0, 100, true)
                coordinator.submitAuthoredV3(true, 100, 30)
                worker.submit {}.get(2, TimeUnit.SECONDS)
            }
            assertEquals(8, ticks.get())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            worker.execute { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            coordinator.submitAuthoredV3(true, 100, 30)
            coordinator.resetAuthoredV3()
            release.countDown()
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(8, ticks.get())
        } finally { coordinator.stop(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun endingAuthoredPlaybackCannotReviveAnOldOrdinaryRumble() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val initialWave = CountDownLatch(1)
        val tick = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val waves = AtomicInteger()
        val coordinator = DeviceVibrationCoordinator({ _, _ -> }, {},
            vibrateDevice = { _, _ -> waves.incrementAndGet(); initialWave.countDown() },
            cancelDeviceVibration = { cancelled.countDown() }, executor = worker,
            playDeviceTick = { tick.countDown() },
            // This test checks source replacement, not wall-clock freshness under build load.
            clockMs = { 0L })
        try {
            coordinator.submitGameRumble(DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
                20_000, 10_000, 100)
            assertTrue(initialWave.await(2, TimeUnit.SECONDS))
            coordinator.submitAuthoredDetail(true, 100)
            assertTrue(tick.await(2, TimeUnit.SECONDS))
            coordinator.submitAuthoredDetail(false, 100, active = false)
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(1, waves.get())
        } finally { coordinator.stop(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun detailTicksDoNotUseTheContinuousWaveformAndYieldToAudioAndTouch() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val ticks = AtomicInteger()
        val continuous = AtomicInteger()
        val completions = Collections.synchronizedList(mutableListOf<Pair<Runnable, Long>>())
        val coordinator = DeviceVibrationCoordinator(
            { callback, delay -> completions += callback to delay }, {},
            { _, _ -> continuous.incrementAndGet() }, {}, worker,
            playDeviceTick = { ticks.incrementAndGet() }
        )
        try {
            coordinator.submitAuthoredDetail(true, 100)
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1, ticks.get())
            assertEquals(0, continuous.get())
            assertEquals(DeviceVibrationCoordinator.AudioClaimResult.OWNED_READY, coordinator.claimForAudio())
            coordinator.submitAuthoredDetail(true, 100)
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1, ticks.get())
            coordinator.releaseFromAudio()
            coordinator.playTouchHaptic(3_000, 3_000, 50)
            coordinator.submitAuthoredDetail(true, 100)
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1, ticks.get())
        } finally { coordinator.stop(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun aBlockedWorkerDropsOldTicksInsteadOfReplayingAStaleBurst() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { blocked.countDown(); release.await(2, TimeUnit.SECONDS) }
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        val now = AtomicLong(0)
        val ticks = AtomicInteger()
        val coordinator = DeviceVibrationCoordinator({ _, _ -> }, {}, { _, _ -> }, {}, worker,
            playDeviceTick = { ticks.incrementAndGet() }, clockMs = now::get)
        try {
            repeat(2_000) { coordinator.submitAuthoredDetail(true, 100) }
            now.set(500)
            release.countDown()
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(0, ticks.get())
        } finally { release.countDown(); coordinator.stop(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun muteAndEndPreventQueuedTicksAndWatchdogReleasesTheDetailLease() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val callbacks = Collections.synchronizedList(mutableListOf<Runnable>())
        val ticks = AtomicInteger()
        val now = AtomicLong(0)
        val coordinator = DeviceVibrationCoordinator(
            { callback, _ -> callbacks += callback }, { callbacks.remove(it) },
            { _, _ -> }, {}, worker, playDeviceTick = { ticks.incrementAndGet() }, clockMs = now::get)
        try {
            coordinator.submitAuthoredDetail(true, 0)
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(0, ticks.get())
            now.set(250)
            callbacks.first().run()
            worker.submit {}.get(2, TimeUnit.SECONDS)
            coordinator.submitAuthoredDetail(false, 100, active = false)
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(0, ticks.get())
        } finally { coordinator.stop(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }
}
