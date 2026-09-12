package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the production body pipeline components with a manual output-looper clock.
 * Android ControllerHandler discovery/Binder wiring is outside this JVM test.
 */
class CoordinatedBodyDispatchTest {
    private class OutputLooper {
        @Volatile var nowMs = 0L
        private val tasks = mutableListOf<Pair<Runnable, Long>>()
        fun post(runnable: Runnable, delay: Long) { tasks += runnable to nowMs + delay }
        fun remove(runnable: Runnable) { tasks.removeAll { it.first === runnable } }
        fun advance(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val next = tasks.filter { it.second <= target }.minByOrNull { it.second } ?: break
                tasks.remove(next)
                nowMs = next.second
                next.first.run()
            }
            nowMs = target
        }
    }

    @Test
    fun everyHitOnHeldBackgroundReachesTheDeviceThenCancels() {
        val looper = OutputLooper()
        val worker = Executors.newSingleThreadScheduledExecutor()
        val writes = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        val device = DeviceVibrationCoordinator(
            postDelayed = looper::post,
            removeCallback = looper::remove,
            vibrateDevice = { amplitude, _ -> writes += looper.nowMs to amplitude },
            cancelDeviceVibration = { writes += looper.nowMs to 0 },
            executor = worker,
            // Held updates cannot accidentally run during this test. Boundaries must bypass
            // this cooldown, and falling edges must discard the pending decaying level.
            minimumIntervalMs = 60_000L
        )
        val slot = RumbleOutputSlot<ControllerRumbleState>(
            pacingMs = { 33L }, edgeFloorMs = 10L, isZero = { it.isZero },
            postDelayed = looper::post, removeCallbacks = looper::remove,
            clockMs = { looper.nowMs },
            dispatch = {
                device.submitGameRumble(DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
                    SingleMotorRumbleFold.amplitude(it.lowFrequency, it.highFrequency), 100)
            }
        )
        val analyzer = RumbleEnvelopeAnalyzer(clockMs = { looper.nowMs })
        val mixer = ControllerHapticsMixer()
        fun drainWorker() { worker.submit {}.get(2, TimeUnit.SECONDS) }
        fun render(input: ControllerRumbleState) {
            val route = GameRumbleRouter.route(GameRumbleMode.COORDINATED, input,
                true, true, DeviceHapticsTier.COMPOSITION, analyzer.decompose(input))
            assertEquals(input, route.controller)
            slot.submit(mixer.submit(0, RumbleSource.HOST, route.device!!, looper.nowMs).output)
            looper.advance(0)
            drainWorker()
        }
        val background = ControllerRumbleState(0.3f, 0.2f)
        val hit = ControllerRumbleState(0.3f, 0.8f)
        try {
            render(background)
            repeat(20) { looper.advance(20); render(background) }
            assertEquals(0, writes.last().second)
            writes.clear()
            repeat(3) { beat ->
                looper.advance(100)
                val onsetMs = looper.nowMs
                render(hit)
                assertEquals(beat * 2 + 1, writes.size)
                assertEquals(onsetMs, writes.last().first)
                assertTrue(writes.last().second > 0)
                looper.advance(20)
                render(hit)
                looper.advance(20)
                render(background)
                assertEquals(beat * 2 + 2, writes.size)
                assertEquals(onsetMs + 40L to 0, writes.last())
                // No HOST zero packet: only the transient compensation becomes silent.
                repeat(15) { looper.advance(20); render(background) }
                assertEquals(beat * 2 + 2, writes.size)
            }
        } finally {
            slot.cancel()
            device.stop()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
