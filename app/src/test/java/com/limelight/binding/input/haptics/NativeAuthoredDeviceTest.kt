package com.limelight.binding.input.haptics

import com.limelight.nvstream.*
import org.junit.Assert.*
import org.junit.Test

class NativeAuthoredDeviceTest {
    private class Runtime(private val continuous: Boolean = true) : AutoCloseable {
        var now = 0L
        val renders = mutableListOf<Pair<Float, Float>>()
        val warms = mutableListOf<Boolean>()
        val ordinary = mutableListOf<Int>()
        var stops = 0
        var closes = 0
        private var stopIr: (() -> Unit)? = null
        private val callbacks = mutableListOf<Pair<Runnable, Long>>()
        val worker = VirtualHapticsExecutor { now * 1_000_000 }
        val post: (Runnable, Long) -> Unit = { task, delay -> callbacks += task to (now + delay) }
        val remove: (Runnable) -> Unit = { task -> callbacks.removeAll { it.first == task } }
        val sink = object : NativeAuthoredHaptics {
            override val ready = true
            override val supportsContinuous = continuous
            override fun render(body: Float, point: Float, keepWarm: Boolean): Long? {
                renders += body to point
                warms += keepWarm
                return if (point > 0) now + 180 else null
            }
            override fun stop() { stops++ }
            override fun close() { closes++ }
        }
        val phone = DeviceVibrationCoordinator(post, remove, { amplitude, _ -> ordinary += amplitude },
            { ordinary += 0 }, worker, clockMs = { now },
            dispatcherClockNanos = { now * 1_000_000 }, nativeHaptics = sink)

        fun authored(mode: GameRumbleMode, hasController: Boolean = true,
                     controllers: MutableList<ControllerRumbleState> = mutableListOf()): AuthoredIrCoordinator {
            val mixer = ControllerHapticsMixer()
            val renderer = RumbleOutputRenderer(mixer, ControllerHapticsMixer(), { now }, post, remove,
                controllerAvailable = { hasController }, controllerIntervalMs = { 20L },
                writeController = { controllers += it.output }, writeDeviceAmplitude = {})
            return AuthoredIrCoordinator(mixer, renderer, phone,
                context = { GameRumbleContext(mode, hasController, hasDevice = true) },
                supportsTick = { false }, strengthPercent = { 100 }, clockMs = { now },
                postDelayed = post, removeCallbacks = remove, scheduleExpiry = {}).also {
                stopIr = { it.stop(); renderer.stop() }
            }
        }

        fun submit(body: Float = .1f, point: Float = 0f) =
            phone.submitNativeAuthored(body, point, now + 200, now + 30)
        fun flush() {
            while (true) {
                val index = callbacks.indexOfFirst { it.second <= now }
                if (index < 0) break
                callbacks.removeAt(index).first.run()
            }
            worker.runReady()
        }
        fun advance(ms: Long) { now += ms; flush() }
        override fun close() { worker.blocked = false; stopIr?.invoke(); phone.stop(); flush() }
    }

    private fun frame(sequence: Int = 0, timeUs: Long = 0,
                      body: Ds5HapticsIrExtendedLane = Ds5HapticsIrExtendedLane(.1f, .2f)):
        Ds5HapticsIrExtendedFrame {
        val lane = Ds5HapticsIrLane(.3f, .4f, 0f, .5f, 200f)
        return Ds5HapticsIrExtendedFrame(Ds5HapticsIrFrame(0, 0, sequence, timeUs, 240, lane, lane),
            1, timeUs, body, body, listOf(Ds5HapticsIrExtendedEvent(sequence + 1, timeUs, .01f, .004f)))
    }

    @Test fun bodyUpdatesKeepAFreshPointAndNewPointsBypassContinuousPacing() {
        Runtime().use { r ->
            r.submit(point = .15f)
            r.submit(body = .2f)
            r.flush()
            assertEquals(.2f to .15f, r.renders.single())
            r.now = 5
            r.submit(point = .55f)
            r.flush()
            assertEquals(listOf(.15f, .55f), r.renders.map { it.second })
            assertTrue(r.ordinary.none { it > 0 })
        }
    }

    @Test fun stalledHardwareDropsExpiredPointsAndDisconnectStopsTheBackground() {
        Runtime().use { r ->
            r.worker.blocked = true
            r.submit(point = .5f)
            r.now = 50
            r.worker.blocked = false
            r.flush()
            assertEquals(.1f to 0f, r.renders.single())
            r.advance(151)
            assertEquals(1, r.stops)
            assertEquals(0, r.ordinary.last())
        }
    }

    @Test fun audioWaitsForNativeStopAndDoesNotReplaySuppressedPoints() {
        Runtime().use { r ->
            r.submit(point = .2f); r.flush()
            assertEquals(DeviceVibrationCoordinator.AudioClaimResult.OWNED_NOT_READY, r.phone.claimForAudio())
            r.flush()
            assertEquals(1, r.stops)
            assertEquals(DeviceVibrationCoordinator.AudioClaimResult.OWNED_READY, r.phone.claimForAudio())
            r.advance(170)
            r.submit(body = .3f, point = .6f)
            r.advance(20)
            assertEquals(1, r.renders.size)
            r.phone.releaseFromAudio(); r.flush()
            assertEquals(.3f to 0f, r.renders.last())
        }
    }

    @Test fun nativeWatchdogCannotInvalidateTouchQueuedBehindAStalledWorker() {
        Runtime().use { r ->
            r.submit(point = .2f); r.flush()
            r.worker.blocked = true
            r.phone.playTouchHaptic(20_000, 0, 500)
            r.advance(250)
            r.worker.blocked = false
            r.flush()
            assertEquals(1, r.stops)
            assertEquals(1, r.ordinary.count { it > 0 })
            assertEquals(1, r.renders.size)
        }
    }

    @Test fun shutdownClosesTheBankAndInvalidatesPendingNativeWork() {
        Runtime().use { r ->
            r.worker.blocked = true
            r.submit(point = .6f)
            r.phone.stop()
            r.worker.blocked = false
            r.flush()
            assertTrue(r.renders.isEmpty())
            assertEquals(1, r.closes)
        }
    }

    @Test fun epochResetClearsTheOldNativePointEvenWhenTheStopIsReplacedByNewBody() {
        Runtime().use { r ->
            r.submit(point = .5f); r.flush()
            r.phone.resetAuthoredIr()
            r.submit(body = .3f)
            r.flush()
            assertEquals(1, r.stops)
            assertEquals(.3f to 0f, r.renders.last())
        }
    }

    @Test fun silentHeartbeatsCannotKeepTheNativeTransportWarmIndefinitely() {
        Runtime().use { r ->
            r.phone.submitNativeAuthored(0f, 0f, 0, 0, active = false); r.flush()
            assertTrue(r.ordinary.isEmpty())
            assertTrue(r.warms.isEmpty())
            r.submit(body = 0f); r.flush()
            assertTrue(r.warms.last())
            repeat(16) { r.advance(100); r.submit(body = 0f); r.flush() }
            assertFalse(r.warms.last())
            r.submit(point = .2f); r.flush()
            assertTrue(r.warms.last())
        }
    }

    @Test fun extendedIrUsesGradedPointsAndRespectsControllerDeviceAllocation() {
        for (continuous in listOf(true, false)) for (mode in GameRumbleMode.entries)
            for (hasController in listOf(true, false)) Runtime(continuous).use { r ->
            val controllers = mutableListOf<ControllerRumbleState>()
            val coordinator = r.authored(mode, hasController, controllers = controllers)
            coordinator.submit(frame())
            r.flush(); r.advance(20)
            if (mode == GameRumbleMode.CONTROLLER) assertTrue(r.renders.isEmpty())
            else if (!continuous && (mode == GameRumbleMode.DEVICE || !hasController)) {
                // The empty preroll may warm the sink before the scheduled body arrives.
                assertTrue(r.renders.all { it.first == 0f && it.second == 0f })
                assertTrue(r.ordinary.any { it > 0 })
            }
            else {
                assertEquals(.2f, r.renders.last().second, .0001f)
                if (mode == GameRumbleMode.COORDINATED && hasController) {
                    assertEquals(0f, r.renders.last().first, 0f)
                    assertTrue(controllers.last().highFrequency <= .2f)
                } else assertEquals(.31623f, r.renders.last().first, .0001f)
            }
        }
    }

    @Test fun transientBackendWaitsThroughBodyGapsThenIgnoresInaudibleResidualBody() {
        Runtime(continuous = false).use { r ->
            val coordinator = r.authored(GameRumbleMode.DEVICE, hasController = false)
            coordinator.submit(frame()); r.flush(); r.advance(20)
            r.now = 40
            coordinator.submit(frame(1, 40_000, Ds5HapticsIrExtendedLane(0f, 0f)))
            r.flush(); r.advance(20)
            assertTrue("A brief body gap must not switch to the transient backend",
                r.renders.none { it.second > 0 })
            r.now = 150
            coordinator.submit(frame(2, 150_000, Ds5HapticsIrExtendedLane(.001f, .001f)))
            r.flush(); r.advance(20)
            assertEquals(0f to .2f, r.renders.last())
        }
    }
}
