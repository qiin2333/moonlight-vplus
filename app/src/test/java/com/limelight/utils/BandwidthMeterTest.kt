package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandwidthMeterTest {
    @Test
    fun bandwidthFormattingSwitchesUnitsAtOneMegabytePerSecond() {
        assertEquals("104\u00A0K\u2060/\u2060s", formatBandwidthSpeed(0.85))
        assertEquals("977\u00A0K\u2060/\u2060s", formatBandwidthSpeed(8.0))
        assertEquals("1.00\u00A0M\u2060/\u2060s", formatBandwidthSpeed(8.388608))
        assertEquals("1.47\u00A0M\u2060/\u2060s", formatBandwidthSpeed(12.34))
    }

    @Test
    fun invalidBandwidthFormattingReturnsUnavailable() {
        assertEquals("N/A", formatBandwidthSpeed(-1.0))
        assertEquals("N/A", formatBandwidthSpeed(Double.NaN))
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
    fun zeroDeltaReportsZeroImmediately() {
        val meter = BandwidthMeter()

        meter.update(0L, 1_000_000_000L)
        assertEquals(8.0, meter.update(1_000_000L, 2_000_000_000L)!!, 0.0001)
        assertEquals(0.0, meter.update(1_000_000L, 3_000_000_000L)!!, 0.0001)
    }

    @Test
    fun largeDownwardShiftIsReflectedWithinOneSample() {
        val meter = BandwidthMeter()

        meter.update(0L, 0L)
        assertEquals(60.0, meter.update(7_500_000L, 1_000_000_000L)!!, 0.0001)
        assertEquals(0.5, meter.update(7_562_500L, 2_000_000_000L)!!, 0.0001)
    }

    @Test
    fun longIntervalHoldsLastValueThenZeroReportsImmediately() {
        val meter = BandwidthMeter()

        meter.update(0L, 1_000_000_000L)
        assertEquals(8.0, meter.update(1_000_000L, 2_000_000_000L)!!, 0.0001)

        // Interval > 5s: re-baseline and keep the last value
        assertEquals(8.0, meter.update(1_000_000L, 10_000_000_000L)!!, 0.0001)
        // The next valid window reports its true rate immediately
        assertEquals(0.0, meter.update(1_000_000L, 11_000_000_000L)!!, 0.0001)
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
