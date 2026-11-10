package com.limelight.perfhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfHudModelTest {

    private fun snapshot(
        renderedFps: Float = 120f,
        receivedFps: Float = 120f,
        targetFps: Int = 120,
        loss: Float = 0f,
        rtt: Int = 8,
        decodeMs: Float = 3f,
        battery: Int = 80,
        charging: Boolean = false,
        fullMoon: Boolean = false
    ) = PerfHudSnapshot(
        width = 2560, height = 1440, targetFps = targetFps, codec = "HEVC", hdrLabel = null,
        decoderName = "c2.qti.hevc.decoder", renderedFps = renderedFps, receivedFps = receivedFps,
        framegenFps = 0f, onePercentLowFps = 0f, packetLossPercent = loss, rttMs = rtt,
        rttVarianceMs = 1, bandwidth = "40 Mbps",
        latency = LatencyChain.estimate(rtt, 2f, decodeMs, 4f),
        batteryLevel = battery, charging = charging, drainPercentPerHour = null, clock = null,
        moonIcon = "🌕", fullMoon = fullMoon
    )

    @Test
    fun `density cycles mini standard detailed and classic stays put`() {
        assertEquals(PerfHudDensity.STANDARD, PerfHudDensity.MINI.next())
        assertEquals(PerfHudDensity.DETAILED, PerfHudDensity.STANDARD.next())
        assertEquals(PerfHudDensity.MINI, PerfHudDensity.DETAILED.next())
        assertEquals(PerfHudDensity.CLASSIC, PerfHudDensity.CLASSIC.next())
        assertFalse(PerfHudDensity.CLASSIC.isHud)
    }

    @Test
    fun `unknown preference falls back to standard`() {
        assertEquals(PerfHudDensity.DETAILED, PerfHudDensity.fromPreference("detailed"))
        assertEquals(PerfHudDensity.CLASSIC, PerfHudDensity.fromPreference("classic"))
        assertEquals(PerfHudDensity.STANDARD, PerfHudDensity.fromPreference(null))
        assertEquals(PerfHudDensity.STANDARD, PerfHudDensity.fromPreference("bogus"))
    }

    @Test
    fun `thresholds match legacy hot decode and loss colors`() {
        assertEquals(PerfSeverity.BAD, PerfHudThresholds.decode(15f, 60))
        assertEquals(PerfSeverity.WARN, PerfHudThresholds.decode(9f, 120))
        assertEquals(PerfSeverity.OK, PerfHudThresholds.decode(9f, 60))
        assertEquals(PerfSeverity.OK, PerfHudThresholds.packetLoss(0.5f))
        assertEquals(PerfSeverity.WARN, PerfHudThresholds.packetLoss(1f))
        assertEquals(PerfSeverity.BAD, PerfHudThresholds.packetLoss(5f))
        assertEquals(PerfSeverity.WARN, PerfHudThresholds.rtt(40))
        assertEquals(PerfSeverity.BAD, PerfHudThresholds.rtt(80))
    }

    @Test
    fun `fps severity is relative to target`() {
        assertEquals(PerfSeverity.OK, PerfHudThresholds.fps(58f, 60))
        assertEquals(PerfSeverity.WARN, PerfHudThresholds.fps(54f, 60))
        assertEquals(PerfSeverity.BAD, PerfHudThresholds.fps(40f, 60))
        assertEquals(PerfSeverity.OK, PerfHudThresholds.fps(0f, 60))
        assertEquals(PerfSeverity.OK, PerfHudThresholds.onePercentLow(0f, 60))
    }

    @Test
    fun `battery is never a problem while charging`() {
        assertEquals(PerfSeverity.BAD, PerfHudThresholds.battery(5, false))
        assertEquals(PerfSeverity.OK, PerfHudThresholds.battery(5, true))
        assertEquals(PerfSeverity.WARN, PerfHudThresholds.battery(20, false))
        assertEquals(PerfSeverity.OK, PerfHudThresholds.battery(-1, false))
    }

    @Test
    fun `latency chain uses half rtt and sums segments`() {
        val chain = LatencyChain.estimate(20, null, 3f, 5f)
        assertEquals(10f, chain.networkMs, 0.001f)
        assertNull(chain.hostMs)
        assertEquals(18f, chain.totalMs, 0.001f)
        assertEquals(20f, LatencyChain.estimate(20, 2f, 3f, 5f).totalMs, 0.001f)
        assertEquals(0f, LatencyChain.estimate(-4, -1f, -1f, -1f).totalMs, 0.001f)
    }

    @Test
    fun `float ring keeps newest values in order`() {
        val ring = FloatRing(3)
        listOf(1f, 2f, 3f, 4f, 5f).forEach(ring::add)
        assertEquals(3, ring.size)
        assertEquals(3f, ring[0], 0f)
        assertEquals(5f, ring[2], 0f)
        assertEquals(5f, ring.max(), 0f)
        ring.clear()
        assertEquals(0, ring.size)
    }

    @Test
    fun `drain rate needs five minutes off charger`() {
        assertNull(PerfHudSnapshot.drainPercentPerHour(80, 79, 60_000L, false))
        assertNull(PerfHudSnapshot.drainPercentPerHour(80, 70, 30 * 60_000L, true))
        assertNull(PerfHudSnapshot.drainPercentPerHour(-1, 70, 30 * 60_000L, false))
        assertEquals(20f, PerfHudSnapshot.drainPercentPerHour(80, 70, 30 * 60_000L, false)!!, 0.001f)
        assertEquals(0f, PerfHudSnapshot.drainPercentPerHour(70, 72, 30 * 60_000L, false)!!, 0f)
    }

    @Test
    fun `mood priority puts hot decode first and full moon last`() {
        assertEquals(PerfMood.WARMING_UP, snapshot(renderedFps = 0f, receivedFps = 0f).mood)
        assertEquals(PerfMood.HOT_DECODE, snapshot(decodeMs = 16f, loss = 10f).mood)
        assertEquals(PerfMood.LOSSY, snapshot(loss = 2f, renderedFps = 30f).mood)
        assertEquals(PerfMood.FPS_DROP, snapshot(renderedFps = 90f, rtt = 100).mood)
        assertEquals(PerfMood.LAGGY_NETWORK, snapshot(rtt = 100, battery = 5).mood)
        assertEquals(PerfMood.LOW_BATTERY, snapshot(battery = 5, fullMoon = true).mood)
        assertEquals(PerfMood.FULL_MOON_SMOOTH, snapshot(fullMoon = true).mood)
        assertEquals(PerfMood.SMOOTH, snapshot().mood)
    }

    @Test
    fun `overall severity ignores battery`() {
        assertEquals(PerfSeverity.OK, snapshot(battery = 3).overallSeverity)
        assertEquals(PerfSeverity.WARN, snapshot(rtt = 50).overallSeverity)
        assertEquals(PerfSeverity.BAD, snapshot(rtt = 50, loss = 6f).overallSeverity)
        assertTrue(snapshot(decodeMs = 15f).isDecodeHot)
    }

    @Test
    fun `layout policy docks in portrait and follows orientation preference otherwise`() {
        val p = PerfHudLayoutPolicy
        assertEquals(PerfHudLayout.CAPSULE, p.resolve(PerfHudDensity.MINI, true, false, true))
        assertEquals(PerfHudLayout.DOCK, p.resolve(PerfHudDensity.STANDARD, true, false, true))
        assertEquals(PerfHudLayout.DOCK, p.resolve(PerfHudDensity.DETAILED, true, true, true))
        assertEquals(PerfHudLayout.BAR, p.resolve(PerfHudDensity.STANDARD, true, false, false))
        assertEquals(PerfHudLayout.COLUMN, p.resolve(PerfHudDensity.STANDARD, true, true, false))
        assertEquals(PerfHudLayout.BAR, p.resolve(PerfHudDensity.STANDARD, false, false, true))
        assertEquals(PerfHudLayout.COLUMN, p.resolve(PerfHudDensity.STANDARD, false, true, false))
        assertEquals(PerfHudLayout.PANEL, p.resolve(PerfHudDensity.DETAILED, false, false, true))
        assertEquals(PerfHudLayout.PANEL, p.resolve(PerfHudDensity.DETAILED, true, false, false))
    }
}
