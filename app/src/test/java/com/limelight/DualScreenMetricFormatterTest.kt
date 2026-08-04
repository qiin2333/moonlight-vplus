package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Test

class DualScreenMetricFormatterTest {
    @Test
    fun formatsResolutionAndCodecOnSeparateLines() {
        assertEquals(
            "2560x1440",
            DualScreenMetricFormatter.resolution(2560, 1440)
        )
        assertEquals(
            "HEVC · HDR",
            DualScreenMetricFormatter.codec("HEVC", hdrActive = true)
        )
        assertEquals("--", DualScreenMetricFormatter.codec(null, hdrActive = false))
    }

    @Test
    fun extractsRttFromPackedPerformanceValue() {
        assertEquals("37 ms", DualScreenMetricFormatter.rtt(37L shl 32))
        assertEquals("0 ms", DualScreenMetricFormatter.rtt(-1L shl 32))
    }

    @Test
    fun formatsRatesAndClampsInvalidNegativeValues() {
        assertEquals("59.9 / 60.0", DualScreenMetricFormatter.fps(59.94f, 60f))
        assertEquals("0.0 ms", DualScreenMetricFormatter.latency(-2f))
        assertEquals("0.00%", DualScreenMetricFormatter.packetLoss(-0.5f))
    }
}
