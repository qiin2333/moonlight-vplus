package com.limelight.binding.video

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.limelight.nvstream.jni.MoonBridge
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

/** Bounded local observations. Queue occupancy alone is not a backlog verdict. */
internal class PyrowaveProbeStats(private val startedNs: Long) {
    private class Metric {
        private val recent = DoubleArray(240)
        private var index = 0
        private var size = 0
        var count = 0L
            private set
        private var sum = 0.0
        private var maximum = 0.0

        fun add(value: Double?) {
            if (value == null || !value.isFinite() || value < 0.0) return
            recent[index] = value
            index = (index + 1) % recent.size
            size = (size + 1).coerceAtMost(recent.size)
            count++
            sum += value
            maximum = max(maximum, value)
        }

        fun snapshot(): Map<String, Any?> {
            val ordered = List(size) { recent[(index + recent.size - size + it) % recent.size] }
            val sorted = ordered.sorted()
            return mapOf(
                "samples" to size, "total_samples" to count,
                "lifetime_avg" to if (count > 0) sum / count else null,
                "lifetime_max" to if (count > 0) maximum else null,
                "recent_p95" to if (size > 0) sorted[ceil(size * 0.95).toInt() - 1] else null,
                "series" to ordered,
            )
        }
    }

    private data class Bucket(
        val second: Long,
        var firstFrame: Int,
        var lastFrame: Int,
        var frames: Int = 0,
        var queuePeak: Int = 0,
        var queueWaitPeakMs: Double? = null,
        var submitPeakMs: Double = 0.0,
        var acquirePeakMs: Double? = null,
        var completionPeakMs: Double? = null,
        var unconfirmedPeakFrames: Double? = null,
        var displayExtraWaitPeakMs: Double? = null,
    )

    private val queueBefore = Metric()
    private val queueAfter = Metric()
    private val queueWait = Metric()
    private val receiveToEnqueue = Metric()
    private val submit = Metric()
    private val acquire = Metric()
    private val completion = Metric()
    private val present = Metric()
    private val unconfirmedPresents = Metric()
    private val displayLateness = Metric()
    private val timeline = ArrayDeque<Bucket>()
    private val results = linkedMapOf<String, Long>()
    private var omittedSeconds = 0L
    private var lastFrame = 0
    private var frameGaps = 0L
    private var frames = 0L
    private var displayTimingSupported: Boolean? = null

    fun record(nowNs: Long, frame: Int, before: Int, after: Int, queueWaitUs: Long?,
               receiveToEnqueueUs: Long?, submitUs: Long, result: String, gpu: LongArray?) {
        if (frame <= 0 || nowNs < startedNs) return
        frames++
        if (lastFrame > 0 && frame > lastFrame + 1) frameGaps += frame.toLong() - lastFrame - 1
        lastFrame = frame
        results[result] = (results[result] ?: 0L) + 1
        queueBefore.add(before.toDouble())
        queueAfter.add(after.toDouble())
        val queueMs = queueWaitUs?.takeIf { it >= 0 }?.div(1000.0)
        queueWait.add(queueMs)
        receiveToEnqueue.add(receiveToEnqueueUs?.takeIf { it >= 0 }?.div(1000.0))
        val submitMs = submitUs / 1000.0
        submit.add(submitMs)
        if (gpu != null && gpu.size >= 7) {
            acquire.add(gpu[0].takeIf { it >= 0 }?.div(1000.0))
            completion.add(gpu[1].takeIf { it >= 0 }?.div(1000.0))
            present.add(gpu[2].takeIf { it >= 0 }?.div(1000.0))
            unconfirmedPresents.add(gpu[3].takeIf { it >= 0 }?.toDouble())
            displayLateness.add(gpu[4].takeIf { it >= 0 }?.div(1000.0))
            displayTimingSupported = when (gpu[5]) {
                0L -> false
                1L -> true
                else -> null
            }
        }
        val second = (nowNs - startedNs) / 1_000_000_000L
        if (timeline.peekLast()?.second != second) {
            if (timeline.size == 600) {
                timeline.removeFirst()
                omittedSeconds++
            }
            timeline.addLast(Bucket(second, frame, frame))
        }
        checkNotNull(timeline.peekLast()).apply {
            lastFrame = frame
            frames++
            queuePeak = max(queuePeak, max(before, after))
            if (queueMs != null) queueWaitPeakMs = max(queueWaitPeakMs ?: 0.0, queueMs)
            submitPeakMs = max(submitPeakMs, submitMs)
            if (gpu != null && gpu.size >= 7) {
                if (gpu[0] >= 0) acquirePeakMs = max(acquirePeakMs ?: 0.0, gpu[0] / 1000.0)
                if (gpu[1] >= 0) completionPeakMs = max(completionPeakMs ?: 0.0, gpu[1] / 1000.0)
                if (gpu[3] >= 0) unconfirmedPeakFrames = max(unconfirmedPeakFrames ?: 0.0, gpu[3].toDouble())
                if (gpu[4] >= 0) displayExtraWaitPeakMs = max(displayExtraWaitPeakMs ?: 0.0, gpu[4] / 1000.0)
            }
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "frames" to frames, "frame_number_gaps" to frameGaps,
        "results" to results.toMap(), "percentile_scope" to "last_240_samples",
        "pending_decode_before_frames" to queueBefore.snapshot(),
        "pending_decode_after_frames" to queueAfter.snapshot(),
        "decode_queue_wait_ms" to queueWait.snapshot(),
        "receive_to_enqueue_ms" to receiveToEnqueue.snapshot(),
        "submit_ms" to submit.snapshot(), "swapchain_acquire_wait_ms" to acquire.snapshot(),
        "gpu_completion_wait_ms" to completion.snapshot(), "present_call_ms" to present.snapshot(),
        "display_timing_supported" to displayTimingSupported,
        "display_timing_supported_scope" to "native_extension_and_entry_availability_not_feedback_query_success",
        "display_feedback_query_interval_ms" to 100,
        "unconfirmed_presents_frames" to unconfirmedPresents.snapshot(),
        "display_extra_wait_ms" to displayLateness.snapshot(),
        "present_feedback_scope" to "asynchronous_confirmation_not_exact_live_queue_depth",
        "timeline" to timeline.map { bucket ->
            mapOf("second" to bucket.second, "first_frame" to bucket.firstFrame,
                "last_frame" to bucket.lastFrame, "frames" to bucket.frames,
                "decode_queue_peak_frames" to bucket.queuePeak,
                "decode_queue_wait_peak_ms" to bucket.queueWaitPeakMs,
                "submit_peak_ms" to bucket.submitPeakMs,
                "swapchain_acquire_wait_peak_ms" to bucket.acquirePeakMs,
                "gpu_completion_wait_peak_ms" to bucket.completionPeakMs,
                "unconfirmed_presents_peak_frames" to bucket.unconfirmedPeakFrames,
                "display_extra_wait_peak_ms" to bucket.displayExtraWaitPeakMs)
        },
        "omitted_seconds" to omittedSeconds,
    )
}

internal class PyrowavePerformanceProbe private constructor(
    private val directory: File, private val width: Int, private val height: Int,
    private val fps: Int, private val gpuPath: Boolean,
) {
    private val startedNs = SystemClock.elapsedRealtimeNanos()
    private val probeId = UUID.randomUUID().toString()
    private val stats = PyrowaveProbeStats(startedNs)
    private var finished = false

    @Synchronized
    fun record(frame: Int, before: Int, after: Int, queueWaitUs: Long?, receiveToEnqueueUs: Long?,
               submitUs: Long, result: PyrowaveDecoderSession.SubmitResult, gpu: LongArray?) {
        if (!finished) stats.record(SystemClock.elapsedRealtimeNanos(), frame, before, after,
            queueWaitUs, receiveToEnqueueUs, submitUs, result.name, gpu)
    }

    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        val report = stats.snapshot() + mapOf(
            "schema" to 1, "role" to "moonlight-vplus", "codec" to "pyrowave",
            "local_probe_id" to probeId,
            "width" to width, "height" to height, "fps" to fps, "initial_gpu_path" to gpuPath,
            "configuration_scope" to "initial_stream_configuration_not_per_frame",
            "duration_ms" to (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L,
            "clock" to "local_monotonic_durations_no_cross_host_clock_subtraction",
            "decode_queue_wait_scope" to "enqueue_to_java_submit_including_native_to_java_transfer",
            "gpu_completion_wait_scope" to "CPU_fence_wait_including_decode_and_conversion_not_GPU_execution_time",
        )
        try {
            writer.execute {
                try {
                    if (!directory.isDirectory && !directory.mkdirs()) error("probe directory unavailable")
                    val file = File(directory, "moonlight-pyrowave-${System.currentTimeMillis()}-$probeId.json")
                    file.writeText(JSONObject(report).toString(2), Charsets.UTF_8)
                    Log.i(TAG, "PyroWave performance probe saved: ${file.absolutePath}")
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to save PyroWave performance probe", error)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to schedule PyroWave performance probe export", error)
        }
    }

    companion object {
        private const val TAG = "PyroWaveProbe"
        private val writer by lazy {
            ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
                { task -> Thread(task, "PyroWave probe writer") }, ThreadPoolExecutor.AbortPolicy())
        }

        fun create(context: Context, width: Int, height: Int, fps: Int, gpu: Boolean): PyrowavePerformanceProbe? {
            if (!MoonBridge.isPyrowaveProbeEnabled()) return null
            val application = context.applicationContext
            val directory = application.getExternalFilesDir("pyrowave-probes") ?: File(application.filesDir, "pyrowave-probes")
            Log.e(TAG, "PyroWave performance probe enabled; diagnostic collection may affect timing")
            return PyrowavePerformanceProbe(directory, width, height, fps, gpu)
        }
    }
}
