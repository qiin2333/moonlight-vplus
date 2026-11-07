package com.limelight.binding.input.haptics

import com.limelight.nvstream.*
import org.junit.Assert.*
import org.junit.Test

class NativeAuthoredDeviceTest {
    private class Runtime : AutoCloseable {
        var now = 0L
        val renders = mutableListOf<Pair<Float, Float>>()
        val bodies = mutableListOf<NativeAuthoredBody>()
        val warms = mutableListOf<Boolean>()
        val ordinary = mutableListOf<Int>()
        var stops = 0
        var closes = 0
        var nativeBackend: NativeAuthoredHaptics? = null
        private var stopIr: (() -> Unit)? = null
        private val callbacks = mutableListOf<Pair<Runnable, Long>>()
        val worker = VirtualHapticsExecutor { now * 1_000_000 }
        val post: (Runnable, Long) -> Unit = { task, delay -> callbacks += task to (now + delay) }
        val remove: (Runnable) -> Unit = { task -> callbacks.removeAll { it.first == task } }
        val sink = object : NativeAuthoredHaptics {
            override val ready = true
            override fun render(body: NativeAuthoredBody, point: Float, keepWarm: Boolean): Long? {
                bodies += body
                renders += body.gain to point
                warms += keepWarm
                nativeBackend?.let { return it.render(body, point, keepWarm) }
                return if (point > 0) now + 180 else null
            }
            override fun stop() { stops++; nativeBackend?.stop() }
            override fun close() { closes++; nativeBackend?.close() }
        }
        val phone = DeviceVibrationCoordinator(post, remove, { amplitude, _ -> ordinary += amplitude },
            { ordinary += 0 }, worker, clockMs = { now },
            dispatcherClockNanos = { now * 1_000_000 }, nativeHaptics = sink)

        fun authored(mode: GameRumbleMode, hasController: Boolean = true,
                     controllers: MutableList<ControllerRumbleState> = mutableListOf(),
                     strength: Int = 100, ordinaryHost: ControllerRumbleState? = null): AuthoredIrCoordinator {
            val mixer = ControllerHapticsMixer()
            ordinaryHost?.let { mixer.submit(0, RumbleSource.HOST, it, now, now + 1_000) }
            val renderer = RumbleOutputRenderer(mixer, ControllerHapticsMixer(), { now }, post, remove,
                controllerAvailable = { hasController }, controllerIntervalMs = { 20L },
                writeController = { controllers += it.output }, writeDeviceAmplitude = {})
            return AuthoredIrCoordinator(mixer, renderer, phone,
                context = { GameRumbleContext(mode, hasController, hasDevice = true) },
                supportsTick = { false }, strengthPercent = { strength }, clockMs = { now },
                postDelayed = post, removeCallbacks = remove, scheduleExpiry = {}).also {
                stopIr = { it.stop(); renderer.stop() }
            }
        }

        fun submit(body: Float = .1f, point: Float = 0f) =
            phone.submitNativeAuthored(nativeBodyWithGain(body), point, now + 200, now + 30)
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

    private fun frame(sequence: Int = 0, timeUs: Long = 0, point: Float = .01f,
                      low: Float = .1f, high: Float = .2f): Ds5HapticsIrExtendedFrame {
        val lane = Ds5HapticsIrLane(.3f, .4f, 0f, .5f, 200f)
        val body = Ds5HapticsIrExtendedLane(low, high)
        return Ds5HapticsIrExtendedFrame(Ds5HapticsIrFrame(0, 0, sequence, timeUs, 240, lane, lane),
            1, timeUs, body, body, if (point > 0)
                listOf(Ds5HapticsIrExtendedEvent(sequence + 1, timeUs, point, point * .4f)) else emptyList())
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
            r.phone.submitNativeAuthored(NativeAuthoredBody.ZERO, 0f, 0, 0, active = false); r.flush()
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
        for (mode in GameRumbleMode.entries)
            for (hasController in listOf(true, false)) Runtime().use { r ->
            val controllers = mutableListOf<ControllerRumbleState>()
            val coordinator = r.authored(mode, hasController, controllers = controllers)
            coordinator.submit(frame())
            r.flush(); r.advance(20)
            if (mode == GameRumbleMode.CONTROLLER) assertTrue(r.renders.isEmpty())
            else {
                assertEquals(.06667f, r.renders.last().second, .0001f)
                if (mode == GameRumbleMode.COORDINATED && hasController) {
                    assertTrue(controllers.last().isZero)
                }
                assertEquals(.31623f, r.renders.last().first, .0001f)
            }
        }
    }

    @Test fun weakSourcePointsKeepTheirContrastAndStrongPointsKeepTheNativeEndpoint() {
        val levels = mutableListOf<Int>()
        for (point in listOf(.0045f, .009f, .018f, .045f, .09f)) Runtime().use { r ->
            r.nativeBackend = RichTapAuthoredHaptics({ raw, _ ->
                assertEquals(4097, raw[8]) // Still a short native event, not a body carrier.
                levels += raw[12]
            }, {}, { r.now }, 1, {})
            r.authored(GameRumbleMode.COORDINATED).submit(frame(point = point, low = 0f, high = 0f))
            r.flush(); r.advance(20)
            assertEquals(point / .09f * .6f, r.renders.last().second, .00001f)
        }
        // Tenfold source contrast remains tenfold before backend quantization/clipping.
        // The previous sqrt curve mapped the weakest value here to about level 22.
        assertEquals(listOf(5, 10, 20, 50, 100), levels)
    }

    @Test fun denseDiscretePointsAreNotRateLimitedOrTurnedIntoControllerRumble() {
        Runtime().use { r ->
            val packets = mutableListOf<IntArray>()
            val controllers = mutableListOf<ControllerRumbleState>()
            r.nativeBackend = RichTapAuthoredHaptics({ raw, _ -> packets += raw }, {}, { r.now }, 1, {})
            val coordinator = r.authored(GameRumbleMode.COORDINATED, controllers = controllers)
            // 80 source points/second, alternating 10/15 ms, with silent heartbeats.
            for (sequence in 0..100) {
                val point = if (sequence < 100 && sequence % 5 in listOf(0, 2)) .016f else 0f
                coordinator.submit(frame(sequence, sequence * 5_000L, point, low = 0f, high = 0f))
                r.flush(); r.advance(5)
            }
            repeat(4) { r.advance(5) }
            assertEquals(40, packets.size)
            assertTrue(packets.all { it[8] == 4097 && it[12] in 1..99 })
            assertTrue(controllers.all { it.isZero })
        }
    }

    @Test fun coordinatedSwellStaysContinuousAndFineTextureDoesNotDriveTheLargeMotor() {
        Runtime().use { r ->
            val controllers = mutableListOf<ControllerRumbleState>()
            val packets = mutableListOf<IntArray>()
            val updates = mutableListOf<Int>()
            r.nativeBackend = RichTapAuthoredHaptics({ raw, _ -> packets += raw }, {}, { r.now }, 1,
                { updates += it })
            val coordinator = r.authored(GameRumbleMode.COORDINATED, controllers = controllers)
            for ((sequence, high) in listOf(.03f, .06f, .09f, .06f, .03f).withIndex()) {
                coordinator.submit(frame(sequence, sequence * 40_000L, point = 0f, low = high / 2, high = high))
                r.flush(); r.advance(20); r.advance(20)
            }
            assertTrue(r.renders.all { it.second == 0f })
            assertTrue(r.renders.count { it.first > 0f } >= 5)
            assertTrue(controllers.all { it.isZero })
            assertEquals(1, packets.size) // One carrier, with gain changes instead of retriggered ticks.
            assertEquals(4096, packets.single()[8])
            assertTrue(updates.size >= 4)
        }
    }

    @Test fun frequencyAndBothBandsSurviveTimelineAndLatestWinsWorker() {
        Runtime().use { r ->
            val coordinator = r.authored(GameRumbleMode.COORDINATED)
            for ((sequence, hz) in listOf(100f, 180f, 300f).withIndex()) {
                val original = frame(sequence, sequence * 40_000L, point = 0f, low = .03f, high = .05f)
                val lane = original.base.left.copy(zeroCrossingRate = 2 * hz)
                coordinator.submit(original.copy(base = original.base.copy(left = lane, right = lane)))
                r.flush(); r.advance(20); r.advance(20)
                assertEquals(.03f, r.bodies.last().lowRms, 0f)
                assertEquals(.05f, r.bodies.last().highRms, 0f)
                assertEquals(hz, r.bodies.last().frequencyHintHz, .001f)
            }
            assertTrue(r.renders.all { it.second == 0f })
        }
    }

    @Test fun nativeTextureOwnershipDoesNotMuteIndependentHostRumble() {
        Runtime().use { r ->
            val controllers = mutableListOf<ControllerRumbleState>()
            val host = ControllerRumbleState(.3f, .2f)
            r.authored(GameRumbleMode.COORDINATED, controllers = controllers, ordinaryHost = host)
                .submit(frame(low = .1f, high = .02f))
            r.flush(); r.advance(20)
            assertEquals(host, controllers.last())
            assertEquals(.1f, r.bodies.last().lowRms, 0f)
            assertEquals(.02f, r.bodies.last().highRms, 0f)
            assertTrue(r.renders.last().second > 0f)
        }
    }

    @Test fun nativeUserStrengthScalesBothLayersWithoutChangingControllerAllocation() {
        for (strength in listOf(0, 50, 100, 200)) Runtime().use { r ->
            val controllers = mutableListOf<ControllerRumbleState>()
            r.authored(GameRumbleMode.COORDINATED, controllers = controllers, strength = strength)
                .submit(frame(point = .018f, low = .2f, high = .05f))
            r.flush(); r.advance(20)
            if (strength == 0) {
                assertTrue(r.renders.isEmpty())
                assertEquals(.2f, controllers.last().lowFrequency, .0001f)
            } else {
                assertEquals(.291548f * strength / 100f, r.renders.last().first, .0001f)
                assertEquals(.12f * strength / 100f, r.renders.last().second, .0001f)
                assertEquals(0f, controllers.last().lowFrequency, 0f)
                assertEquals(0f, controllers.last().highFrequency, 0f)
            }
        }
    }
}
