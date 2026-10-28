package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrExtendedDecoder
import com.limelight.nvstream.Ds5HapticsIrExtendedFrame
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Contract integration with real host wire, timeline, policies, mixer and phone dispatcher.
 * The transport, main-loop clock, controller port and phone native calls are fakes.
 * The production AuthoredIrCoordinator is exercised; Android ControllerHandler/Looper wiring and physical actuator response are outside this test.
 */
class AuthoredIrMockIntegrationTest {
    private data class Arrival(val atMs: Long, val frame: Ds5HapticsIrExtendedFrame)
    private data class Write(val atMs: Long, val amplitude: Int)

    private fun fixture(name: String): List<Ds5HapticsIrExtendedFrame> {
        val asset = sequenceOf(File("src/debug/assets/haptics/generated/$name.irextbin"),
            File("app/src/debug/assets/haptics/generated/$name.irextbin"))
            .firstOrNull(File::isFile)
        assertNotNull("The packaged host-generated fixture is required: $name", asset)
        return Ds5HapticsIrExtendedDecoder.decode(asset!!.readBytes())
    }

    private fun transport(frames: List<Ds5HapticsIrExtendedFrame>, faults: Boolean = false,
                          omitEnd: Boolean = false): List<Arrival> {
        val sourceOrigin = frames.first().windowEndUs
        return frames.flatMapIndexed { index, frame ->
            if (omitEnd && frame.ended || faults && index > 0 && !frame.ended && index % 5 == 2)
                emptyList()
            else {
                val base = 10_000 + (frame.windowEndUs - sourceOrigin) / 1000
                val at = base + if (faults && index > 0) listOf(0L, 7L, 1L, 5L)[index % 4] else 0L
                listOf(Arrival(at, frame)) + if (faults && index % 3 == 0)
                    listOf(Arrival(at + 2, frame)) else emptyList()
            }
        }.sortedBy { it.atMs }
    }

    private class Runtime(
        val mode: GameRumbleMode = GameRumbleMode.COORDINATED,
        val hasController: Boolean = true,
        val hasPhone: Boolean = true,
        val supportsTick: Boolean = true
    ) : AutoCloseable {
        var nowMs = 10_000L
        private var closed = false
        val mixer = ControllerHapticsMixer()
        private val deviceMixer = ControllerHapticsMixer()
        val tickTimes = mutableListOf<Long>()
        val phoneWrites = mutableListOf<Write>()
        val controllerWrites = mutableListOf<Pair<Long, ControllerRumbleState>>()
        val deliveredIds = mutableListOf<Int>()
        var maxInbox = 0
        private val callbacks = linkedMapOf<Runnable, Long>()
        private val worker = VirtualHapticsExecutor { nowMs * 1_000_000 }
        var outputBlocked: Boolean
            get() = worker.blocked
            set(value) { worker.blocked = value }
        val maxOutputQueue: Int get() = worker.maxPending
        val phone = DeviceVibrationCoordinator(
            { task, delay -> callbacks[task] = nowMs + delay }, { callbacks.remove(it); Unit },
            { amplitude, _ -> phoneWrites += Write(nowMs, amplitude) },
            { phoneWrites += Write(nowMs, 0) }, worker,
            playDeviceTick = if (supportsTick) ({ tickTimes += nowMs; Unit }) else null,
            clockMs = { nowMs }, dispatcherClockNanos = { nowMs * 1_000_000 })
        private fun targets(number: Short) = GameRumbleContext(mode, hasController,
            hasPhone && number.toInt() == 0)
        private val renderer = RumbleOutputRenderer(mixer, deviceMixer, { nowMs },
            { task, delay -> callbacks[task] = nowMs + delay }, { callbacks.remove(it); Unit },
            { hasController }, { 20L },
            { controllerWrites += nowMs to it.output },
            { phone.submitGameRumble(DeviceVibrationCoordinator.GameSource.ROUTED_GAME, it, 100) },
            { !closed })
        private val ordinary = GameRumblePipeline(renderer, ::targets, { nowMs },
            { task, delay -> callbacks[task] = nowMs + delay }, { callbacks.remove(it); Unit })
        private val expiry = Runnable {
            mixer.pruneExpired(nowMs).forEach(renderer::queueController)
            deviceMixer.pruneExpired(nowMs).forEach(renderer::queueDevice)
            scheduleExpiry()
        }
        private fun scheduleExpiry() {
            callbacks.remove(expiry)
            listOfNotNull(mixer.nextExpiryAtMs(), deviceMixer.nextExpiryAtMs()).minOrNull()?.let {
                callbacks[expiry] = maxOf(nowMs + 1, it)
            }
        }
        private val authored = AuthoredIrCoordinator(mixer, renderer, phone, ::targets,
            { supportsTick }, { 100 }, { nowMs },
            { task, delay -> callbacks[task] = nowMs + delay }, { callbacks.remove(it); Unit },
            ::scheduleExpiry, { !closed }, { _, output ->
                output.event?.let { deliveredIds += it.value.eventId }
            })
        val queuedEvents: Int get() = authored.queuedEvents
        fun flush() = worker.runReady()
        fun receive(frame: Ds5HapticsIrExtendedFrame) {
            authored.submit(frame)
            maxInbox = maxOf(maxInbox, authored.queuedEvents)
        }
        fun step(atMs: Long, pump: Boolean = true) {
            nowMs = atMs
            if (pump) {
                var rounds = 0
                while (callbacks.values.any { it <= nowMs }) {
                    check(++rounds < 1000) { "Output callbacks must not spin" }
                    callbacks.filterValues { it <= nowMs }.keys.toList().forEach {
                        callbacks.remove(it)
                        it.run()
                    }
                }
            }
            flush()
        }
        fun host(state: ControllerRumbleState) {
            ordinary.submitHost(0, state)
            step(nowMs)
            renderer.flushDevice()
            flush()
        }
        fun replay(arrivals: List<Arrival>, paused: LongRange = LongRange.EMPTY) {
            var index = 0
            for (time in nowMs..arrivals.last().atMs + 300) {
                nowMs = time
                while (index < arrivals.size && arrivals[index].atMs <= time) receive(arrivals[index++].frame)
                step(time, time !in paused)
            }
            flush()
            println("ExtendedMock mode=$mode controller=$hasController phone=$hasPhone tickSupport=$supportsTick " +
                "events=${deliveredIds.size} ticks=${tickTimes.size} " +
                "waves=${phoneWrites.count { it.amplitude > 0 }} inbox=$maxInbox workerQueue=$maxOutputQueue")
        }
        override fun close() {
            closed = true
            worker.blocked = false
            authored.stop()
            ordinary.stop()
            renderer.stop()
            phone.stop()
            flush()
            worker.shutdownNow()
        }
    }

    @Test fun fiftyRainDropsPerSecondReachPhoneWithoutTurningIntoWaveformRumble() {
        Runtime(GameRumbleMode.DEVICE, hasController = false).use { runtime ->
            runtime.replay(transport(fixture("pulse_density_50")))
            assertEquals(100, runtime.tickTimes.size)
            assertEquals(100, runtime.deliveredIds.toSet().size)
            assertTrue(runtime.phoneWrites.none { it.amplitude > 0 })
            assertTrue(runtime.controllerWrites.isEmpty())
            assertEquals(0, runtime.queuedEvents)
        }
    }

    @Test fun coordinatedRainAndMixedBodyKeepPhoneDetailsSeparateFromControllerBody() {
        for (name in listOf("rain-haptics", "mixed_clean_body_0.12_separate_0")) {
            val frames = fixture(name)
            val expected = frames.flatMap { it.events }.map { it.eventId }.toSet().size
            Runtime().use { runtime ->
                runtime.replay(transport(frames))
                assertEquals(name, expected, runtime.tickTimes.size)
                assertEquals(name, expected, runtime.deliveredIds.toSet().size)
                assertTrue("$name: phone must use presets while controller carries body",
                    runtime.phoneWrites.none { it.amplitude > 0 })
                assertTrue("$name: the real continuous layer must survive",
                    runtime.controllerWrites.any { maxOf(it.second.lowFrequency, it.second.highFrequency) > .01f })
                assertTrue(runtime.controllerWrites.last().second.isZero)
            }
        }
    }

    @Test fun onePhoneKeepsContinuousBodyInsteadOfReplacingItWithEachMixedTransient() {
        Runtime(GameRumbleMode.DEVICE, hasController = false).use { runtime ->
            runtime.replay(transport(fixture("mixed_clean_body_0.12_separate_0")))
            assertTrue(runtime.phoneWrites.any { it.amplitude > 0 })
            assertTrue(runtime.tickTimes.none { it in 10_600L..12_000L })
            assertTrue(runtime.controllerWrites.isEmpty())
            assertEquals(0, runtime.phoneWrites.last().amplitude)
        }
    }

    @Test fun deterministicLossDuplicatesAndReorderingRecoverAllRedundantRainEventsOnce() {
        Runtime().use { runtime ->
            runtime.replay(transport(fixture("pulse_density_50"), faults = true))
            assertEquals(100, runtime.deliveredIds.size)
            assertEquals(100, runtime.deliveredIds.toSet().size)
            assertEquals(100, runtime.tickTimes.size)
            assertTrue(runtime.maxInbox <= AuthoredIrTimeline.MAX_EVENTS)
            assertTrue(runtime.controllerWrites.last().second.isZero)
        }
    }

    @Test fun consumerPauseDropsMissedDropsRatherThanPlayingACatchUpTrain() {
        Runtime().use { runtime ->
            runtime.replay(transport(fixture("pulse_density_50")), 10_000L..12_200L)
            assertTrue(runtime.tickTimes.size in 1..99)
            assertEquals(runtime.deliveredIds.size, runtime.deliveredIds.toSet().size)
            assertTrue(runtime.tickTimes.groupingBy { it }.eachCount().values.all { it == 1 })
            assertEquals(AuthoredIrTimeline.MAX_EVENTS, runtime.maxInbox)
            assertEquals(0, runtime.queuedEvents)
        }
    }

    @Test fun blockedNativeWorkerKeepsOneTaskAndDoesNotReplayExpiredRainWhenReleased() {
        Runtime().use { runtime ->
            runtime.outputBlocked = true
            runtime.replay(transport(fixture("pulse_density_50")))
            assertTrue(runtime.maxOutputQueue <= 1)
            assertTrue(runtime.tickTimes.isEmpty())
            runtime.outputBlocked = false
            runtime.flush()
            assertTrue(runtime.tickTimes.isEmpty())
            assertTrue(runtime.phoneWrites.none { it.amplitude > 0 })
            assertEquals(0, runtime.phoneWrites.last().amplitude)
        }
    }

    @Test fun losingEndStillStopsContinuousControllerBodyThroughTheSourceLease() {
        Runtime().use { runtime ->
            val frames = fixture("sustain_250hz_0.12")
            runtime.replay(transport(frames, omitEnd = true))
            assertTrue(runtime.controllerWrites.any { it.second.highFrequency > .01f })
            assertTrue(runtime.controllerWrites.last().second.isZero)
            val latestSource = transport(frames.filterNot { it.ended }).last().atMs
            assertTrue(runtime.controllerWrites.last().first <= latestSource + 201)
            assertTrue(runtime.phoneWrites.none { it.amplitude > 0 })
        }
    }

    @Test fun missingTickCapabilityOrMissingPhoneUsesBoundedFallbackAndEndsCleanly() {
        for (hasPhone in listOf(true, false)) Runtime(supportsTick = false, hasPhone = hasPhone).use { runtime ->
            runtime.replay(transport(fixture("pulse_density_50")))
            assertTrue(runtime.tickTimes.isEmpty())
            assertTrue(runtime.controllerWrites.any { !it.second.isZero })
            assertTrue(runtime.controllerWrites.last().second.isZero)
            if (hasPhone) assertTrue(runtime.phoneWrites.any { it.amplitude > 0 })
            // Source-reset cancellation is harmless even when the phone sink is absent.
            else assertTrue(runtime.phoneWrites.none { it.amplitude > 0 })
            assertEquals(0, runtime.queuedEvents)
        }
    }

    @Test fun authoredEndRestoresTheLatestOrdinaryStateIncludingAnExplicitZero() {
        for (ordinary in listOf(.7f, 0f)) Runtime(GameRumbleMode.DEVICE, hasController = false).use { runtime ->
            val frames = fixture("pulse_density_50")
            runtime.host(ControllerRumbleState(.2f, 0f))
            val original = runtime.phoneWrites.last().amplitude
            runtime.receive(frames.first())
            runtime.step(10_020)
            runtime.host(ControllerRumbleState(ordinary, 0f))
            runtime.replay(transport(frames.drop(1)).map { it.copy(atMs = it.atMs + 5) })
            val restored = runtime.phoneWrites.last().amplitude
            if (ordinary == 0f) assertEquals(0, restored) else assertTrue(restored > original)
        }
    }


}
