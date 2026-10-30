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
        override fun close() { worker.blocked = false; phone.stop(); flush() }
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
        for (continuous in listOf(true, false)) for (mode in GameRumbleMode.entries) Runtime(continuous).use { r ->
            val mixer = ControllerHapticsMixer()
            val controllers = mutableListOf<ControllerRumbleState>()
            val renderer = RumbleOutputRenderer(mixer, ControllerHapticsMixer(), { r.now }, r.post, r.remove,
                controllerAvailable = { true }, controllerIntervalMs = { 20L },
                writeController = { controllers += it.output }, writeDeviceAmplitude = {})
            val coordinator = AuthoredIrCoordinator(mixer, renderer, r.phone,
                context = { GameRumbleContext(mode, hasController = true, hasDevice = true) },
                supportsTick = { false }, strengthPercent = { 100 }, clockMs = { r.now },
                postDelayed = r.post, removeCallbacks = r.remove, scheduleExpiry = {})
            val lane = Ds5HapticsIrLane(.3f, .4f, 0f, .5f, 200f)
            val body = Ds5HapticsIrExtendedLane(.1f, .2f)
            coordinator.submit(Ds5HapticsIrExtendedFrame(Ds5HapticsIrFrame(0, 0, 0, 0, 240, lane, lane),
                1, 0, body, body, listOf(Ds5HapticsIrExtendedEvent(1, 0, .01f, .004f))))
            r.flush(); r.advance(20)
            if (mode == GameRumbleMode.CONTROLLER) assertTrue(r.renders.isEmpty())
            else if (mode == GameRumbleMode.DEVICE && !continuous) {
                // The empty preroll may warm the sink before the scheduled body arrives.
                assertTrue(r.renders.all { it.first == 0f && it.second == 0f })
                assertTrue(r.ordinary.any { it > 0 })
            }
            else {
                assertEquals(.2f, r.renders.last().second, .0001f)
                if (mode == GameRumbleMode.COORDINATED) {
                    assertEquals(0f, r.renders.last().first, 0f)
                    assertTrue(controllers.last().highFrequency <= .2f)
                } else assertEquals(.31623f, r.renders.last().first, .0001f)
            }
            coordinator.stop(); renderer.stop()
        }
    }
}
