package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrExtendedFrame
import com.limelight.nvstream.Ds5HapticsIrExtendedLane
import com.limelight.nvstream.Ds5HapticsIrFrame
import com.limelight.nvstream.Ds5HapticsIrLane
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional stage-level experiment. No Android I/O, networking, wire encoding or scheduler.
 * The C++ SDK outputs are inputs to the real allocator and RichTap packet builder.
 * Old/new point gain equations are numerical models, separately covered by coordinator tests.
 */
class AuthoredOfflineCorpusTest {
    private class Sink {
        var now = 0L
        var points = 0
        var bodies = 0
        var updates = 0
        var frequency = 0
        var gain = 0
        val renderer = RichTapAuthoredHaptics({ raw, _ ->
            if (raw[8] == 4097 || raw[8] == 4096 && raw[15] == 5) points++ else bodies++
            frequency = raw[13]
            if (raw[8] == 4096) gain = if (raw[15] == 5) raw[23] else 100
        }, {}, { now }, 1, { updates++ })
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
            val before = Sink()
            val after = Sink()
            var spNow = 0L
            val spAssets = mutableListOf<SoundPoolAuthoredPlayback.Asset>()
            val soundPool = SoundPoolAuthoredPlayback({ spAssets += it; spAssets.size }, {}, { spNow })
            val renderTrace = File(directory, "native_render.csv").bufferedWriter()
            renderTrace.appendLine("time_us,low,high,gain,frequency_hint_hz,rt_frequency,rt_points,rt_body_starts,rt_gain_updates,sp_asset,sp_points,sp_body_starts")
            var submitted = 0
            File(directory, "client.csv").bufferedWriter().use { output ->
                output.appendLine("time_us,old_low,old_high,new_low,new_high,new_body,old_point_gain,new_point_gain,old_points,new_points,new_body_starts,new_updates,old_sp_points,new_sp_points")
                for (line in File(directory, "frames_240.csv").readLines().drop(1)) {
                    val fields = line.split(',')
                    val timeUs = fields[0].toLong()
                    before.now = timeUs / 1000
                    after.now = before.now
                    before.resetCounts(); after.resetCounts()
                    val low = maxOf(fields[1].toFloat(), fields[3].toFloat())
                    val high = maxOf(fields[2].toFloat(), fields[4].toFloat())
                    val plan = AuthoredIrAllocator.allocateNativeBody(
                        GameRumbleContext(GameRumbleMode.COORDINATED, true, true), ControllerRumbleState(low, high))
                    val controller = plan.controller!!
                    val native = plan.device!!
                    val frequency = if (fields.size >= 9) {
                        val left = Ds5HapticsIrLane(0f, 0f, 0f, 0f, fields[7].toFloat())
                        val right = Ds5HapticsIrLane(0f, 0f, 0f, 0f, fields[8].toFloat())
                        NativeAuthoredBody.frequencyHint(Ds5HapticsIrExtendedFrame(
                            Ds5HapticsIrFrame(0, 0, 0, timeUs, 240, left, right), 0, timeUs,
                            Ds5HapticsIrExtendedLane(fields[1].toFloat(), fields[2].toFloat()),
                            Ds5HapticsIrExtendedLane(fields[3].toFloat(), fields[4].toFloat()), emptyList()))
                    } else 0f
                    val continuous = NativeAuthoredBody.fromBands(native.lowFrequency, native.highFrequency, frequency)
                    val bodyGain = sqrt(2f * (native.lowFrequency * native.lowFrequency + native.highFrequency * native.highFrequency))
                    assertTrue(controller.lowFrequency <= low)
                    assertEquals(low * low, controller.lowFrequency * controller.lowFrequency +
                        native.lowFrequency * native.lowFrequency, .000001f)
                    val points = events[timeUs].orEmpty().map { maxOf(it[3].toFloat(), it[4].toFloat()) }
                    submitted += points.size
                    var oldPeak = 0f
                    var newPeak = 0f
                    var oldSp = 0
                    var newSp = 0
                    spNow = after.now
                    val spStart = spAssets.size
                    for (strength in points.ifEmpty { listOf(0f) }) {
                        val oldGain = sqrt(strength) * 2f
                        val newGain = strength / .09f * .6f
                        oldPeak = maxOf(oldPeak, oldGain); newPeak = maxOf(newPeak, newGain)
                        // SoundPool quantization only: no claim about its waveform scheduling.
                        if ((oldGain.coerceIn(0f, 1f) / .6f * 8).roundToInt() > 0) oldSp++
                        if ((newGain.coerceIn(0f, 1f) / .6f * 8).roundToInt() > 0) newSp++
                        before.renderer.render(NativeAuthoredBody.ZERO, oldGain.coerceIn(0f, 1f), true)
                        after.renderer.render(continuous, newGain.coerceIn(0f, 1f), true)
                        soundPool.render(continuous, newGain.coerceIn(0f, 1f), true)
                    }
                    val newAssets = spAssets.drop(spStart)
                    renderTrace.appendLine(listOf(timeUs, continuous.lowRms, continuous.highRms, continuous.gain,
                        continuous.frequencyHintHz, after.frequency, after.points, after.bodies, after.updates,
                        spAssets.lastOrNull()?.name ?: "", newAssets.count { it.point > 0 }, newAssets.count { it.point == 0 }).joinToString(","))
                    output.appendLine(listOf(timeUs, low, high, controller.lowFrequency, controller.highFrequency,
                        bodyGain, oldPeak, newPeak, before.points, after.points, after.bodies, after.updates, oldSp, newSp).joinToString(","))
                }
            }
            renderTrace.close()
            assertEquals(events.values.sumOf { it.size }, submitted)
            before.renderer.close(); after.renderer.close()
        }
    }
}
