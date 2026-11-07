package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrExtendedFrame
import com.limelight.nvstream.Ds5HapticsIrExtendedLane
import com.limelight.nvstream.Ds5HapticsIrFrame
import com.limelight.nvstream.Ds5HapticsIrLane
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional stage-level experiment. No Android I/O, networking, wire encoding or scheduler.
 * The C++ SDK outputs are inputs to the real allocator and RichTap packet builder.
 */
class AuthoredOfflineCorpusTest {
    private class Sink {
        var now = 0L
        var points = 0
        var bodies = 0
        var updates = 0
        var frequency = 0
        var active = false
        val renderer = RichTapAuthoredHaptics({ raw, _ ->
            active = true
            if (raw[8] == 4097 || raw[8] == 4096 && raw[15] == 5) points++ else bodies++
            frequency = raw[13]
        }, { active = false }, { now }, 1, { updates++ })
        fun resetCounts() { points = 0; bodies = 0; updates = 0 }
    }

    @Test fun exportOfflineNativeCommandsForGeneratedCorpus() {
        val path = System.getenv("HAPTICS_OFFLINE_DIR")
        assumeTrue("Set HAPTICS_OFFLINE_DIR after running tools/haptics-offline/corpus.py", !path.isNullOrEmpty())
        val directories = File(path!!).listFiles()!!.filter { File(it, "frames_240.csv").isFile }.sortedBy { it.name }
        assertTrue("No analyzed PCM scenes", directories.isNotEmpty())
        for (directory in directories) {
            val events = File(directory, "events_240.csv").readLines().drop(1).filter { it.isNotBlank() }
                .map { it.split(',') }.groupBy { it[2].toLong() }
            val after = Sink()
            var spNow = 0L
            val spAssets = mutableListOf<SoundPoolAuthoredPlayback.Asset>()
            var spCurrent: Pair<Int, SoundPoolAuthoredPlayback.Asset>? = null
            val soundPool = SoundPoolAuthoredPlayback({ asset ->
                spAssets += asset
                spAssets.size.also { spCurrent = it to asset }
            }, { id -> if (spCurrent?.first == id) spCurrent = null }, { spNow })
            var submitted = 0
            File(directory, "native_render.csv").bufferedWriter().use { trace ->
                trace.appendLine("time_us,low,high,gain,frequency_hint_hz,rt_frequency,rt_points,rt_body_starts,rt_gain_updates,sp_asset,sp_points,sp_body_starts,rt_active,controller_low,controller_high")
                for (line in File(directory, "frames_240.csv").readLines().drop(1)) {
                    val fields = line.split(',')
                    val timeUs = fields[0].toLong()
                    after.now = timeUs / 1000
                    after.resetCounts()
                    val low = maxOf(fields[1].toFloat(), fields[3].toFloat())
                    val high = maxOf(fields[2].toFloat(), fields[4].toFloat())
                    val plan = AuthoredIrAllocator.allocateNativeBody(
                        GameRumbleContext(GameRumbleMode.COORDINATED, true, true), ControllerRumbleState(low, high))
                    val controller = plan.controller!!
                    val native = plan.device!!
                    val frequency = run {
                        val left = Ds5HapticsIrLane(0f, 0f, 0f, 0f, fields[7].toFloat())
                        val right = Ds5HapticsIrLane(0f, 0f, 0f, 0f, fields[8].toFloat())
                        NativeAuthoredBody.frequencyHint(Ds5HapticsIrExtendedFrame(
                            Ds5HapticsIrFrame(0, 0, 0, timeUs, 240, left, right), 0, timeUs,
                            Ds5HapticsIrExtendedLane(fields[1].toFloat(), fields[2].toFloat()),
                            Ds5HapticsIrExtendedLane(fields[3].toFloat(), fields[4].toFloat()), emptyList()))
                    }
                    val continuous = NativeAuthoredBody.fromBands(native.lowFrequency, native.highFrequency, frequency)
                    val points = events[timeUs].orEmpty().map { maxOf(it[3].toFloat(), it[4].toFloat()) }
                    submitted += points.size
                    spNow = after.now
                    val spStart = spAssets.size
                    var expectedSpPoints = 0
                    for (strength in points.ifEmpty { listOf(0f) }) {
                        val gain = (strength / .09f * .6f).coerceIn(0f, 1f)
                        if ((gain / .6f * 8).roundToInt() > 0) expectedSpPoints++
                        after.renderer.render(continuous, gain, true)
                        soundPool.render(continuous, gain, true)
                    }
                    val newAssets = spAssets.drop(spStart)
                    val spPoints = newAssets.count { it.point > 0 }
                    assertEquals("SoundPool points at $timeUs in $directory", expectedSpPoints, spPoints)
                    trace.appendLine(listOf(timeUs, continuous.lowRms, continuous.highRms, continuous.gain,
                        continuous.frequencyHintHz, after.frequency, after.points, after.bodies, after.updates,
                        spCurrent?.second?.name ?: "", spPoints, newAssets.count { it.point == 0 },
                        after.active, controller.lowFrequency, controller.highFrequency).joinToString(","))
                }
            }
            assertEquals(events.values.sumOf { it.size }, submitted)
            after.renderer.close()
        }
    }
}
