package com.limelight.perfhud

import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PerfHudInsightsTest {

    private fun snapshot(
        renderedFps: Float = 60f,
        receivedFps: Float = 60f,
        targetFps: Int = 60,
        loss: Float = 0f,
        rtt: Int = 8,
        hostMs: Float? = 2f,
        decodeMs: Float = 3f,
        battery: Int = 80,
        onePercentLow: Float = 55f
    ) = PerfHudSnapshot(
        width = 1920, height = 1080, targetFps = targetFps, codec = "HEVC", hdrLabel = null,
        decoderName = "c2.qti.hevc.decoder", renderedFps = renderedFps, receivedFps = receivedFps,
        framegenFps = 0f, onePercentLowFps = onePercentLow, packetLossPercent = loss, rttMs = rtt,
        rttVarianceMs = 2, bandwidth = "3.0 MB/s",
        latency = LatencyChain.estimate(rtt, hostMs, decodeMs, 4f),
        batteryLevel = battery, charging = false, drainPercentPerHour = null, clock = null,
        moonIcon = "🌔", fullMoon = false
    )

    @Test
    fun `fps verdict locates where frames are lost`() {
        assertNull(PerfHudInsights.fpsVerdict(snapshot(receivedFps = 0f)))
        assertEquals(R.string.perf_hud_fps_verdict_remote, PerfHudInsights.fpsVerdict(snapshot(receivedFps = 40f, renderedFps = 40f)))
        assertEquals(R.string.perf_hud_fps_verdict_local, PerfHudInsights.fpsVerdict(snapshot(receivedFps = 60f, renderedFps = 45f)))
        assertEquals(R.string.perf_hud_fps_verdict_surplus, PerfHudInsights.fpsVerdict(snapshot(receivedFps = 120f, renderedFps = 60f)))
        assertEquals(R.string.perf_hud_fps_verdict_ok, PerfHudInsights.fpsVerdict(snapshot()))
    }

    @Test
    fun `host shortfall wins over local rendering drop`() {
        // 主机只送了 40 帧，本机自然也只能画 40 帧：问题在上游，不该怪本机
        assertEquals(R.string.perf_hud_fps_verdict_remote, PerfHudInsights.fpsVerdict(snapshot(receivedFps = 40f, renderedFps = 30f)))
    }

    @Test
    fun `diagnosis tips follow card order and praise when healthy`() {
        assertEquals(listOf(R.string.perf_diag_all_good), PerfHudInsights.diagnosisTips(snapshot()))
        assertEquals(
            listOf(R.string.perf_diag_tip_fps, R.string.perf_diag_tip_loss, R.string.perf_diag_tip_rtt,
                R.string.perf_diag_tip_decode, R.string.perf_diag_tip_battery),
            PerfHudInsights.diagnosisTips(snapshot(renderedFps = 30f, loss = 6f, rtt = 90, decodeMs = 20f, battery = 5))
        )
    }

    @Test
    fun `live value uses severity colors and formats per locale`() {
        val bad = snapshot(rtt = 90, decodeMs = 20f)
        assertEquals(PerfHudInsights.LiveValue(PerfHudPalette.BAD, "90 ms"),
            PerfHudInsights.liveValue(PerfHudTarget.NETWORK, bad, Locale.US))
        assertEquals(PerfHudInsights.LiveValue(PerfHudPalette.BAD, "20,0 ms"),
            PerfHudInsights.liveValue(PerfHudTarget.DECODE, bad, Locale.GERMANY))
        assertEquals(PerfHudInsights.LiveValue(PerfHudPalette.OK, "60 FPS"),
            PerfHudInsights.liveValue(PerfHudTarget.FPS, snapshot(), Locale.US))
    }

    @Test
    fun `live value is blank when data is missing`() {
        assertEquals(PerfHudInsights.LiveValue(PerfHudPalette.MOON), PerfHudInsights.liveValue(PerfHudTarget.FPS, null, Locale.US))
        assertEquals(PerfHudInsights.LiveValue(PerfHudPalette.HOST),
            PerfHudInsights.liveValue(PerfHudTarget.HOST, snapshot(hostMs = null), Locale.US))
        assertEquals(PerfHudInsights.LiveValue(PerfHudPalette.OK),
            PerfHudInsights.liveValue(PerfHudTarget.ONE_PERCENT_LOW, snapshot(onePercentLow = 0f), Locale.US))
    }

    @Test
    fun `latency chain segments keep order and treat missing host as zero`() {
        val segments = LatencyChain.estimate(10, null, 3f, 4f).segments()
        assertEquals(listOf(PerfHudPalette.NET, PerfHudPalette.HOST, PerfHudPalette.DEC, PerfHudPalette.RND), segments.map { it.color })
        assertEquals(listOf(5f, 0f, 3f, 4f), segments.map { it.ms })
    }

    @Test
    fun `quiet color stays grey until something is wrong`() {
        assertEquals(PerfHudPalette.DIM, PerfHudPalette.quiet(PerfSeverity.OK))
        assertEquals(PerfHudPalette.WARN, PerfHudPalette.quiet(PerfSeverity.WARN))
        assertEquals(PerfHudPalette.BAD, PerfHudPalette.quiet(PerfSeverity.BAD))
    }
}
