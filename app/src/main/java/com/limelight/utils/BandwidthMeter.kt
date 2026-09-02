package com.limelight.utils

import java.util.Locale

internal fun formatBandwidthMbps(bandwidthMbps: Double): String {
    if (!bandwidthMbps.isFinite() || bandwidthMbps < 0.0) return "N/A"
    return if (bandwidthMbps < 1.0) {
        String.format(Locale.US, "%.0f Kbps", bandwidthMbps * 1000.0)
    } else {
        String.format(Locale.US, "%.1f Mbps", bandwidthMbps)
    }
}

/** Converts a monotonically increasing byte counter into a stable Mbps value. */
internal class BandwidthMeter(
    private val smoothingFactor: Double = 0.25,
    private val idleHoldSamples: Int = 2,
    private val minIntervalNanos: Long = 250_000_000L,
    private val maxIntervalNanos: Long = 5_000_000_000L,
) {
    private var hasBaseline = false
    private var previousBytes = 0L
    private var previousTimeNanos = 0L
    private var smoothedMbps: Double? = null
    private var idleSamples = 0

    fun reset() {
        hasBaseline = false
        previousBytes = 0L
        previousTimeNanos = 0L
        smoothedMbps = null
        idleSamples = 0
    }

    /** Returns null until a valid interval is available, otherwise Mbps. */
    fun update(totalBytes: Long, nowNanos: Long): Double? {
        if (totalBytes < 0L || nowNanos < 0L) return smoothedMbps

        if (!hasBaseline || totalBytes < previousBytes || nowNanos < previousTimeNanos) {
            if (hasBaseline && (totalBytes < previousBytes || nowNanos < previousTimeNanos)) {
                smoothedMbps = null
            }
            previousBytes = totalBytes
            previousTimeNanos = nowNanos
            hasBaseline = true
            idleSamples = 0
            return smoothedMbps
        }

        val intervalNanos = nowNanos - previousTimeNanos
        if (intervalNanos !in minIntervalNanos..maxIntervalNanos) {
            previousBytes = totalBytes
            previousTimeNanos = nowNanos
            return smoothedMbps
        }

        val deltaBytes = totalBytes - previousBytes
        if (deltaBytes == 0L) {
            idleSamples++
            if (idleSamples > idleHoldSamples) {
                smoothedMbps = 0.0
            }
        } else {
            idleSamples = 0
            // bytes * 8 bits/byte / seconds / 1_000_000 bits/Mb;
            // with nanoseconds this reduces to bytes * 8_000 / nanos.
            val instantMbps = deltaBytes.toDouble() * 8_000.0 / intervalNanos.toDouble()
            smoothedMbps = smoothedMbps?.let {
                it + smoothingFactor * (instantMbps - it)
            } ?: instantMbps
        }

        previousBytes = totalBytes
        previousTimeNanos = nowNanos
        return smoothedMbps
    }
}
