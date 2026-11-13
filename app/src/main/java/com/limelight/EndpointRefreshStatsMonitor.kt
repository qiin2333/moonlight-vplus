package com.limelight

import com.limelight.nvstream.jni.MoonBridge
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Periodically reads endpoint refresh counters without touching the media
 * receive mutex. The native getter returns null when the feature was not
 * negotiated for the current stream.
 */
class EndpointRefreshStatsMonitor {
    companion object {
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val VALUES_PER_STREAM = 12
        private const val VIDEO_OFFSET = 0
        private const val AUDIO_OFFSET = VALUES_PER_STREAM
    }

    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "EndpointRefreshStats").apply { isDaemon = true }
    }

    private var executor: ScheduledExecutorService? = null

    @Synchronized
    fun start() {
        stop()
        val scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory)
        executor = scheduler
        scheduler.scheduleAtFixedRate(
            { sample() },
            SAMPLE_INTERVAL_MS,
            SAMPLE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    private fun sample() {
        val values = try {
            MoonBridge.getEndpointRefreshStats()
        } catch (error: UnsatisfiedLinkError) {
            LimeLog.warning("Endpoint refresh stats are unavailable: ${error.message}")
            return
        } catch (error: RuntimeException) {
            LimeLog.warning("Endpoint refresh stats read failed: ${error.message}")
            return
        }

        if (values == null || values.size < VALUES_PER_STREAM * 2) return

        val streams = listOfNotNull(
            formatStream(values, VIDEO_OFFSET, "video"),
            formatStream(values, AUDIO_OFFSET, "audio"),
        )
        if (streams.isNotEmpty()) {
            LimeLog.info("Endpoint refresh stats: ${streams.joinToString("; ")}")
        }
    }

    private fun formatStream(values: LongArray, offset: Int, name: String): String? {
        if (values[offset] == 0L) return null

        val probes = values[offset + 1]
        val sendFailures = values[offset + 2]
        val confirmed = values[offset + 3]
        val challenges = values[offset + 4]
        val acknowledgements = values[offset + 5]
        val suspect = values[offset + 6] != 0L
        val degraded = values[offset + 7] != 0L
        val responses = values[offset + 8]
        val invalidResponses = values[offset + 9]
        val staleResponses = values[offset + 10]
        val unexpectedResponses = values[offset + 11]
        val unconfirmed = (probes - confirmed).coerceAtLeast(0L)
        val estimatedLoss = if (probes == 0L) {
            "unknown"
        } else {
            "${(unconfirmed * 100L) / probes}%"
        }

        return "$name{probes=$probes, confirmed=$confirmed, " +
            "estimated_loss=$estimatedLoss, send_failures=$sendFailures, " +
            "challenges=$challenges, acks=$acknowledgements, " +
            "responses=$responses, invalid=$invalidResponses, " +
            "stale=$staleResponses, unexpected=$unexpectedResponses, " +
            "suspect=$suspect, degraded=$degraded}"
    }
}
