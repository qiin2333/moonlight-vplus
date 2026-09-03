package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandwidthMeterTest {
    @Test
    fun bandwidthFormattingSwitchesUnitsAtOneMbps() {
        assertEquals("850 Kbps", formatBandwidthMbps(0.85))
        assertEquals("999 Kbps", formatBandwidthMbps(0.9996))
        assertEquals("1.0 Mbps", formatBandwidthMbps(1.0))
        assertEquals("12.3 Mbps", formatBandwidthMbps(12.34))
    }

    @Test
    fun invalidBandwidthFormattingReturnsUnavailable() {
        assertEquals("N/A", formatBandwidthMbps(-1.0))
        assertEquals("N/A", formatBandwidthMbps(Double.NaN))
    }

    @Test
    fun firstSampleOnlyEstablishesBaseline() {
        val meter = BandwidthMeter()

        assertNull(meter.update(1_000_000L, 1_000_000_000L))
        assertEquals(8.0, meter.update(2_000_000L, 2_000_000_000L)!!, 0.0001)
    }

    @Test
    fun shortIntervalsKeepTheBaselineForAValidLaterSample() {
        val meter = BandwidthMeter()

        assertNull(meter.update(0L, 0L))
        assertNull(meter.update(100_000L, 100_000_000L))
        assertNull(meter.update(200_000L, 200_000_000L))
        assertEquals(8.0, meter.update(1_000_000L, 1_000_000_000L)!!, 0.0001)
    }

    @Test
    fun shortZeroBurstsHoldTheLastValue() {
        val meter = BandwidthMeter()

        meter.update(0L, 1_000_000_000L)
        assertEquals(8.0, meter.update(1_000_000L, 2_000_000_000L)!!, 0.0001)
        assertEquals(8.0, meter.update(1_000_000L, 3_000_000_000L)!!, 0.0001)
        assertEquals(8.0, meter.update(1_000_000L, 4_000_000_000L)!!, 0.0001)
        assertEquals(0.0, meter.update(1_000_000L, 5_000_000_000L)!!, 0.0001)
    }

    @Test
    fun longIntervalRestartsTheIdleHoldWindow() {
        val meter = BandwidthMeter()

        meter.update(0L, 1_000_000_000L)
        assertEquals(8.0, meter.update(1_000_000L, 2_000_000_000L)!!, 0.0001)
        assertEquals(8.0, meter.update(1_000_000L, 3_000_000_000L)!!, 0.0001)
        assertEquals(8.0, meter.update(1_000_000L, 4_000_000_000L)!!, 0.0001)

        assertEquals(8.0, meter.update(1_000_000L, 10_000_000_000L)!!, 0.0001)
        assertEquals(8.0, meter.update(1_000_000L, 11_000_000_000L)!!, 0.0001)
        assertEquals(8.0, meter.update(1_000_000L, 12_000_000_000L)!!, 0.0001)
        assertEquals(0.0, meter.update(1_000_000L, 13_000_000_000L)!!, 0.0001)
    }

    @Test
    fun counterResetReestablishesBaseline() {
        val meter = BandwidthMeter()

        meter.update(2_000_000L, 1_000_000_000L)
        assertEquals(8.0, meter.update(3_000_000L, 2_000_000_000L)!!, 0.0001)
        assertNull(meter.update(500_000L, 3_000_000_000L))
        assertEquals(4.0, meter.update(1_000_000L, 4_000_000_000L)!!, 0.0001)
    }
}
