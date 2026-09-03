package com.limelight.utils

import java.util.Locale

internal fun formatBandwidthSpeed(bandwidthMbps: Double): String {
    if (!bandwidthMbps.isFinite() || bandwidthMbps < 0.0) return "N/A"
    val kBps = bandwidthMbps * 125_000.0 / 1024.0
    return if (kBps < 1024.0) {
        String.format(Locale.US, "%.0f\u00A0K\u2060/\u2060s", kBps)
    } else {
        String.format(Locale.US, "%.2f\u00A0M\u2060/\u2060s", kBps / 1024.0)
    }
}

/**
 * Converts a monotonically increasing byte counter into an instantaneous Mbps value.
 *
 * No smoothing by design: the perf overlay must reflect real rate changes (e.g. the
 * host switching to a static desktop) within one sampling window. Stability comes
 * from the precise per-packet RTP byte counter, not from averaging here.
 */
internal class BandwidthMeter(
    private val minIntervalNanos: Long = 250_000_000L,
    private val maxIntervalNanos: Long = 5_000_000_000L,
) {
    private var hasBaseline = false
    private var previousBytes = 0L
    private var previousTimeNanos = 0L
    private var lastMbps: Double? = null

    fun reset() {
        hasBaseline = false
        previousBytes = 0L
        previousTimeNanos = 0L
        lastMbps = null
    }

    /** Returns null until a valid interval is available, otherwise Mbps. */
    fun update(totalBytes: Long, nowNanos: Long): Double? {
        if (totalBytes < 0L || nowNanos < 0L) return lastMbps

        if (!hasBaseline || totalBytes < previousBytes || nowNanos < previousTimeNanos) {
            if (hasBaseline && (totalBytes < previousBytes || nowNanos < previousTimeNanos)) {
                lastMbps = null
            }
            previousBytes = totalBytes
            previousTimeNanos = nowNanos
            hasBaseline = true
            return lastMbps
        }

        val intervalNanos = nowNanos - previousTimeNanos
        if (intervalNanos < minIntervalNanos) {
            return lastMbps
        }
        if (intervalNanos > maxIntervalNanos) {
            previousBytes = totalBytes
            previousTimeNanos = nowNanos
            return lastMbps
        }

        // bytes * 8 bits/byte / seconds / 1_000_000 bits/Mb;
        // with nanoseconds this reduces to bytes * 8_000 / nanos.
        lastMbps = (totalBytes - previousBytes).toDouble() * 8_000.0 / intervalNanos.toDouble()
        previousBytes = totalBytes
        previousTimeNanos = nowNanos
        return lastMbps
    }
}
