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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeviceVibrationCoordinatorTest {
    private data class Vibration(val amplitude: Int, val durationMs: Long)

    /** Manual clock + delayed-callback queue so observation windows and leases stay deterministic. */
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
        clock.advance(60)
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
            executor = executor,
            clockMs = { clock.nowMs }
        )

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        clock.advance(60)
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
        clock.advance(60)
        await { vibrations.size == 1 }

        coordinator.playTouchHaptic(2_000, 2_000, 50)
        await { vibrations.size == 2 }
        assertEquals(Vibration(7, 50), vibrations.last())

        coordinator.submitGameRumble(ROUTED_GAME, 176, 100)
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(2, vibrations.size)

        clock.advance(50)
        // Touch finished, but the game onset is under observation: nothing may be written yet.
        val drained = executor.submit {}
        drained.get(2, TimeUnit.SECONDS)
        assertEquals(2, vibrations.size)

        clock.advance(60)
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
        clock.advance(60)
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
        clock.advance(60)
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
        clock.advance(60)
        await { vibrations.size == 1 }
        assertEquals(Vibration(255, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun shortPulseIsEmittedAsAMeasuredOneShotInsteadOfALevel() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        clock.advance(25)
        coordinator.submitGameRumble(ROUTED_GAME, 0, 100)
        await { vibrations.size == 1 }
        assertEquals(Vibration(160, 25), vibrations.single())

        // No trailing level rewrite or cancel may follow the pulse.
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(1, vibrations.size)

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun subMinimumBlipProducesNoWrite() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        clock.advance(4)
        coordinator.submitGameRumble(ROUTED_GAME, 0, 100)

        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertTrue(vibrations.isEmpty())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun pulsePeakHoldsTheStrongestValueSeenInsideTheWindow() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val coordinator = coordinator(executor, vibrations, clock)

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        clock.advance(10)
        coordinator.submitGameRumble(ROUTED_GAME, 208, 100)
        clock.advance(20)
        coordinator.submitGameRumble(ROUTED_GAME, 0, 100)
        await { vibrations.size == 1 }
        assertEquals(Vibration(208, 30), vibrations.single())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun zeroAfterALevelCancelsTheMotorOnce() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val clock = FakeClock()
        val cancels = AtomicInteger()
        val coordinator = coordinator(executor, vibrations, clock) { cancels.incrementAndGet() }

        coordinator.submitGameRumble(ROUTED_GAME, 160, 100)
        clock.advance(60)
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
        executor = executor,
        clockMs = { clock.nowMs }
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue("Timed out waiting for vibration dispatch", condition())
    }
}
