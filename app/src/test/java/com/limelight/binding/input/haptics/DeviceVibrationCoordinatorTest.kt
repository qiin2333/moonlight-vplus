package com.limelight.binding.input.haptics

import com.limelight.binding.input.haptics.DeviceVibrationCoordinator.GameSource.LEGACY_OVERLAY
import com.limelight.binding.input.haptics.DeviceVibrationCoordinator.GameSource.ROUTED_GAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeviceVibrationCoordinatorTest {
    private data class Vibration(val amplitude: Int, val durationMs: Long)

    /** Manual clock + delayed-callback queue so leases stay deterministic. */
    private class FakeClock {
        var nowMs = 0L
        private val queue = mutableListOf<Pair<Runnable, Long>>()

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

    @Test
    fun audioOwnershipSuppressesGameWritesAndRestoresLatestState() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        await { vibrations.size == 1 }
        assertEquals(Vibration(160, 500), vibrations.single())

        coordinator.claimForAudio()
        coordinator.submitGameRumble(ROUTED_GAME, 208, 100)
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(1, vibrations.size)

        coordinator.releaseFromAudio()
        await { vibrations.size == 2 }
        assertEquals(Vibration(208, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun audioClaimWaitsForAnAdmittedGameWriteWithoutBlockingTheCaller() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val gameWriteStarted = CountDownLatch(1)
        val releaseGameWrite = CountDownLatch(1)
        val clock = FakeClock()
        val coordinator = DeviceVibrationCoordinator(
            postDelayed = clock::post,
            removeCallback = clock::remove,
            vibrateDevice = { _, _ ->
                gameWriteStarted.countDown()
                releaseGameWrite.await(2, TimeUnit.SECONDS)
            },
            cancelDeviceVibration = {},
            executor = executor
        )

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        assertTrue(gameWriteStarted.await(2, TimeUnit.SECONDS))

        assertFalse(coordinator.claimForAudio())
        releaseGameWrite.countDown()
        executor.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue(coordinator.claimForAudio())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun touchHapticRestoresGameStateUpdatedDuringPulse() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        await { vibrations.size == 1 }

        coordinator.playTouchHaptic(2_000, 2_000, 50)
        await { vibrations.size == 2 }
        assertEquals(Vibration(7, 50), vibrations.last())

        coordinator.submitGameRumble(ROUTED_GAME, 176, 100)
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(2, vibrations.size)

        clock.advance(50)
        await { vibrations.size == 3 }
        assertEquals(Vibration(176, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun gameSourcesMixAndClearingOneRestoresTheOther() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        await { vibrations.size == 1 }

        coordinator.submitGameRumble(LEGACY_OVERLAY, 208, 100)
        await { vibrations.size == 2 }
        assertEquals(Vibration(208, 500), vibrations.last())

        coordinator.submitGameRumble(LEGACY_OVERLAY, 0, 100)
        await { vibrations.size == 3 }
        assertEquals(Vibration(160, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun imperceptibleChangesAreDeduplicatedAndConstantStateRefreshesItsLease() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        await { vibrations.size == 1 }

        // Quantizes back to 160: no write, but the level lease must stay scheduled.
        coordinator.submitGameRumble(ROUTED_GAME, 161, 100)
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(1, vibrations.size)

        clock.advance(375)
        await { vibrations.size == 2 }
        assertEquals(Vibration(160, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun strengthBoostIsAppliedToTheTargetAmplitude() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 128, 200)
        await { vibrations.size == 1 }
        assertEquals(Vibration(255, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun shortPulseStartsBeforeItsFallingEdgeAndIsNeverReplayed() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val cancels = AtomicInteger()
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock) { cancels.incrementAndGet() }
        try {
            coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
            await { vibrations.size == 1 }
            assertEquals(Vibration(160, 500), vibrations.single())
            clock.advance(25)
            coordinator.submitGameRumble(ROUTED_GAME, 0, 100)
            await { cancels.get() == 1 }
            clock.advance(1000)
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1, vibrations.size)
        } finally {
            coordinator.stop()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun stopReplacesAParkedLevelWithoutWaitingForItsCooldown() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val cancels = AtomicInteger()
        val clock = FakeClock()
        val coordinator = DeviceVibrationCoordinator(
            postDelayed = clock::post, removeCallback = clock::remove,
            vibrateDevice = { amplitude, duration -> vibrations += Vibration(amplitude, duration) },
            cancelDeviceVibration = { cancels.incrementAndGet() }, executor = executor,
            minimumIntervalMs = 60_000L
        )
        try {
            coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
            await { vibrations.size == 1 }
            executor.submit {}.get(2, TimeUnit.SECONDS)
            coordinator.submitGameRumble(ROUTED_GAME, 208, 100)
            coordinator.submitGameRumble(ROUTED_GAME, 0, 100)
            await { cancels.get() == 1 }
            assertEquals(1, vibrations.size)
            // A new climbing beat also bypasses the held-level cooldown.
            coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
            await { vibrations.size == 2 }
        } finally {
            coordinator.stop()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun zeroAfterALevelCancelsTheMotorOnce() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val cancels = AtomicInteger()
        val coordinator = coordinator(executor, vibrations, clock) { cancels.incrementAndGet() }

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        await { vibrations.size == 1 }

        coordinator.submitGameRumble(ROUTED_GAME, 0, 100)
        await { cancels.get() == 1 }

        // Repeated zeros are deduplicated.
        coordinator.submitGameRumble(ROUTED_GAME, 0, 100)
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(1, cancels.get())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun audioReleaseResubmitsAnUnchangedGameLevel() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        await { vibrations.size == 1 }

        coordinator.claimForAudio()
        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(1, vibrations.size)

        // The motor stopped with the audio session: releasing must reprogram the same level
        // immediately even though its amplitude never changed.
        coordinator.releaseFromAudio()
        await { vibrations.size == 2 }
        assertEquals(Vibration(160, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun touchCompletionResubmitsAnUnchangedGameLevel() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        await { vibrations.size == 1 }

        coordinator.playTouchHaptic(2_000, 2_000, 50)
        await { vibrations.size == 2 }
        assertEquals(Vibration(7, 50), vibrations.last())

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(2, vibrations.size)

        clock.advance(50)
        await { vibrations.size == 3 }
        assertEquals(Vibration(160, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun audioRestorationIsScheduledWithZeroDelay() = assertRestorationDelay(audio = true)

    @Test
    fun touchRestorationIsScheduledWithZeroDelay() = assertRestorationDelay(audio = false)

    private fun assertRestorationDelay(audio: Boolean) {
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        val executor = object : ScheduledThreadPoolExecutor(1) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                // Record the requested delay but execute immediately, so the test does not
                // depend on wall-clock scheduling or wait a minute for the touch write.
                delays += unit.toNanos(delay)
                return super.schedule(command, 0L, TimeUnit.NANOSECONDS)
            }
        }
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = DeviceVibrationCoordinator(
            postDelayed = clock::post,
            removeCallback = clock::remove,
            vibrateDevice = { amplitude, duration -> vibrations += Vibration(amplitude, duration) },
            cancelDeviceVibration = {},
            executor = executor,
            minimumIntervalMs = 60_000L
        )
        try {
            coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
            await { vibrations.size == 1 }
            executor.submit {}.get(2, TimeUnit.SECONDS)
            if (audio) {
                assertTrue(coordinator.claimForAudio())
            } else {
                coordinator.playTouchHaptic(2_000, 2_000, 50)
                await { vibrations.size == 2 }
                executor.submit {}.get(2, TimeUnit.SECONDS)
            }
            delays.clear()
            val beforeRestore = vibrations.size
            if (audio) coordinator.releaseFromAudio() else clock.advance(50)
            await { vibrations.size == beforeRestore + 1 }
            assertEquals(listOf(0L), delays.toList())
            assertEquals(Vibration(160, 500), vibrations.last())
        } finally {
            coordinator.stop()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun coordinator(
        executor: ScheduledExecutorService,
        vibrations: MutableList<Vibration>,
        clock: FakeClock,
        cancelDeviceVibration: () -> Unit = {}
    ) = DeviceVibrationCoordinator(
        postDelayed = clock::post,
        removeCallback = clock::remove,
        vibrateDevice = { amplitude, duration ->
            vibrations += Vibration(amplitude, duration)
        },
        cancelDeviceVibration = cancelDeviceVibration,
        executor = executor
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue("Timed out waiting for vibration dispatch", condition())
    }
}
