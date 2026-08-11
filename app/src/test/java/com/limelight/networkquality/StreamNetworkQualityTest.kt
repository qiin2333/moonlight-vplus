package com.limelight.networkquality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamNetworkQualityTest {
    @Test
    fun recommendationUsesNativeDisplayShapeAndRefreshRate() {
        val result = StreamNetworkTestResult(
            bandwidthMbps = 62.4,
            responseLatencyMs = 9.0,
            responseJitterMs = 2.1
        )
        val recommendation = result.recommendationFor(
            StreamDeviceDisplay(nativeWidth = 1080, nativeHeight = 2340, maxFps = 120)
        )

        assertEquals(StreamNetworkQuality.GOOD, result.quality)
        assertEquals(2340, recommendation.width)
        assertEquals(1080, recommendation.height)
        assertEquals(120, recommendation.fps)
        assertEquals(40_000, recommendation.bitrateKbps)
        assertTrue(recommendation.usesNativeResolution)
    }

    @Test
    fun recommendationKeepsNativeAspectRatioWhenBandwidthRequiresScaling() {
        val result = StreamNetworkTestResult(
            bandwidthMbps = 20.0,
            responseLatencyMs = 9.0,
            responseJitterMs = 2.1
        )
        val recommendation = result.recommendationFor(
            StreamDeviceDisplay(nativeWidth = 1080, nativeHeight = 2340, maxFps = 120)
        )

        assertEquals(1170, recommendation.width)
        assertEquals(540, recommendation.height)
        assertEquals(120, recommendation.fps)
        // 20 Mbps measured with 35% headroom leaves a 13 Mbps network ceiling.
        assertEquals(13_000, recommendation.bitrateKbps)
        assertFalse(recommendation.usesNativeResolution)
    }

    @Test
    fun highBandwidthCanUseUpToTwiceTheDefaultBitrate() {
        val result = StreamNetworkTestResult(
            bandwidthMbps = 217.0,
            responseLatencyMs = 10.0,
            responseJitterMs = 1.0
        )
        val recommendation = result.recommendationFor(
            StreamDeviceDisplay(nativeWidth = 1080, nativeHeight = 2340, maxFps = 120)
        )

        assertEquals(2340, recommendation.width)
        assertEquals(1080, recommendation.height)
        assertEquals(120, recommendation.fps)
        assertEquals(72_000, recommendation.bitrateKbps)
    }

    @Test
    fun staleResultsDoNotTriggerStartWarning() {
        val stale = StreamNetworkTestResult(
            bandwidthMbps = 20.0,
            responseLatencyMs = 30.0,
            responseJitterMs = 4.0,
            testedAtEpochMs = 1L
        )
        val recommendation = stale.recommendationFor(
            StreamDeviceDisplay(nativeWidth = 1080, nativeHeight = 2340, maxFps = 120)
        )

        assertFalse(stale.shouldWarnForBitrate(80_000, recommendation))
    }

    @Test
    fun freshResultWarnsOnlyWellAboveSafeBitrate() {
        val result = StreamNetworkTestResult(
            bandwidthMbps = 62.4,
            responseLatencyMs = 9.0,
            responseJitterMs = 2.1
        )

        val recommendation = result.recommendationFor(
            StreamDeviceDisplay(nativeWidth = 1080, nativeHeight = 2340, maxFps = 120)
        )

        assertFalse(result.shouldWarnForBitrate(45_000, recommendation))
        assertTrue(result.shouldWarnForBitrate(50_000, recommendation))
    }

}
