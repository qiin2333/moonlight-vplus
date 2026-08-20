package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DeviceVibrationCoordinatorTest {
    private data class Vibration(val amplitude: Int, val durationMs: Long)

    @Test
    fun audioOwnershipSuppressesGameWritesAndRestoresLatestState() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val coordinator = coordinator(executor, vibrations)

        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            1_000,
            2_000,
            100
        )
        await { vibrations.size == 1 }

        coordinator.claimForAudio()
        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            3_000,
            4_000,
            100
        )
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(1, vibrations.size)

        coordinator.releaseFromAudio()
        await { vibrations.size == 2 }
        assertEquals(Vibration(16, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun audioClaimWaitsForAnAdmittedGameWriteWithoutBlockingTheCaller() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val gameWriteStarted = CountDownLatch(1)
        val releaseGameWrite = CountDownLatch(1)
        val coordinator = DeviceVibrationCoordinator(
            postDelayed = { _, _ -> },
            removeCallback = {},
            vibrateDevice = { _, _ ->
                gameWriteStarted.countDown()
                releaseGameWrite.await(2, TimeUnit.SECONDS)
            },
            cancelDeviceVibration = {},
            executor = executor
        )

        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            10_000,
            0,
            100
        )
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
        val touchCompletion = AtomicReference<Runnable?>()
        val coordinator = coordinator(
            executor,
            vibrations,
            postDelayed = { callback, _ -> touchCompletion.set(callback) }
        )

        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            1_000,
            1_000,
            100
        )
        await { vibrations.size == 1 }
        coordinator.playTouchHaptic(2_000, 2_000, 50)
        await { vibrations.size == 2 }

        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            3_000,
            3_000,
            100
        )
        val barrier = executor.submit {}
        barrier.get(2, TimeUnit.SECONDS)
        assertEquals(2, vibrations.size)

        val completion = touchCompletion.get()
        assertNotNull(completion)
        completion!!.run()
        await { vibrations.size == 3 }
        assertEquals(Vibration(16, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun gameSourcesMixAndClearingOneRestoresTheOther() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val coordinator = coordinator(executor, vibrations)

        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            10_000,
            4_000,
            100
        )
        await { vibrations.size == 1 }
        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.LEGACY_OVERLAY,
            30_000,
            2_000,
            100
        )
        await { vibrations.size == 2 }
        assertEquals(Vibration(96, 500), vibrations.last())

        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.LEGACY_OVERLAY,
            0,
            0,
            100
        )
        await { vibrations.size == 3 }
        assertEquals(Vibration(32, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun imperceptibleChangesAreDeduplicatedAndConstantStateRefreshesItsLease() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val scheduledRefresh = AtomicReference<Runnable?>()
        val coordinator = coordinator(
            executor,
            vibrations,
            postDelayed = { callback, _ -> scheduledRefresh.set(callback) }
        )

        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            10_000,
            0,
            100
        )
        await { vibrations.size == 1 }
        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            10_500,
            0,
            100
        )
        executor.submit {}.get(2, TimeUnit.SECONDS)
        assertEquals(1, vibrations.size)

        val refresh = scheduledRefresh.getAndSet(null)
        assertNotNull(refresh)
        refresh!!.run()
        await { vibrations.size == 2 }
        assertEquals(Vibration(32, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun strengthBoostIsAppliedAfterMotorAmplitudeConversion() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val vibrations = Collections.synchronizedList(mutableListOf<Vibration>())
        val coordinator = coordinator(executor, vibrations)

        coordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            0xFFFF.toShort(),
            0,
            200
        )

        await { vibrations.size == 1 }
        assertEquals(Vibration(255, 500), vibrations.last())

        coordinator.stop()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    private fun coordinator(
        executor: java.util.concurrent.ScheduledExecutorService,
        vibrations: MutableList<Vibration>,
        postDelayed: (Runnable, Long) -> Unit = { _, _ -> }
    ) = DeviceVibrationCoordinator(
        postDelayed = postDelayed,
        removeCallback = {},
        vibrateDevice = { amplitude, duration ->
            vibrations += Vibration(amplitude, duration)
        },
        cancelDeviceVibration = {},
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
