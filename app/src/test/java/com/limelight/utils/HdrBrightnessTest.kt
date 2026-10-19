package com.limelight.utils

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrBrightnessTest {
    @Test
    fun completeSystemReportAlwaysWinsOverRatio() {
        for (ratio in listOf(null, 5f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
            val report = HdrBrightness.fromDisplay(400.125f, 0f, 300.25f, ratio)!!
            assertEquals(400.125f, report.maxNits, 0f)
            assertEquals(0f, report.minNits, 0f)
            assertEquals(300.25f, report.maxFullFrameNits, 0f)
            assertFalse(report.isEstimatedFromRatio)
        }
    }

    @Test
    fun missingSystemLuminanceUsesRatioAutomatically() {
        val report = HdrBrightness.fromDisplay(-1f, Float.NaN, -1f, 5f)!!
        assertEquals(1500f, report.maxNits, 0f)
        assertEquals(0.001f, report.minNits, 0f)
        assertEquals(300f, report.maxFullFrameNits, 0f)
        assertTrue(report.isEstimatedFromRatio)
        val fractionalRatio = HdrBrightness.fromDisplay(-1f, -1f, -1f, 3.25f)!!
        assertEquals(975f, fractionalRatio.maxNits, 0f)
        assertEquals(195f, fractionalRatio.maxFullFrameNits, 0f)
    }

    @Test
    fun estimatedPeakPreservesKnownBlackLevelAndFullFrame() {
        val missingPeak = HdrBrightness.fromDisplay(-1f, 0.0004f, 600.125f, 5f)!!
        assertEquals(1500f, missingPeak.maxNits, 0f)
        assertEquals(0.0004f, missingPeak.minNits, 0f)
        assertEquals(600.125f, missingPeak.maxFullFrameNits, 0f)
    }

    @Test
    fun knownFullFrameIsALowerBoundForAnEstimatedPeak() {
        val report = HdrBrightness.fromDisplay(-1f, 0f, 1200.25f, 3f)!!
        assertEquals(1200.25f, report.maxNits, 0f)
        assertEquals(1200.25f, report.maxFullFrameNits, 0f)
    }

    @Test
    fun unavailableOrInvalidRatiosLeaveUnknownCapabilitiesToTheHost() {
        for (ratio in listOf(null, -1f, 0f, 1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE, 100f)) {
            assertNull(HdrBrightness.fromDisplay(-1f, -1f, -1f, ratio))
        }
    }

    @Test
    fun validPeakNeverTriggersSecondaryFieldRepair() {
        for (ratio in listOf(null, 5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(HdrBrightness.fromDisplay(400.125f, -1f, 300.25f, ratio))
            assertNull(HdrBrightness.fromDisplay(400f, 0.001f, -1f, ratio))
            assertNull(HdrBrightness.fromDisplay(400f, 0.001f, 600f, ratio))
            assertNull(HdrBrightness.fromDisplay(1000f, 20f, 10f, ratio))
        }
    }

    @Test
    fun explicitManualTargetStillOverridesAnAutomaticEstimate() {
        val estimate = HdrBrightness.fromDisplay(-1f, 0.0004f, -1f, 5f)!!
        val manual = HdrBrightness.forSession(estimate, 1000)!!
        assertEquals(1000f, manual.maxNits, 0f)
        assertEquals(0.0004f, manual.minNits, 0f)
        assertFalse(manual.isEstimatedFromRatio)
    }

    @Test
    fun preservesFractionalNitsAndZeroBlackLevel() {
        for (minimum in listOf(0f, 0.0000004f, 0.001f, 0.01f, 0.1f)) {
            val report = HdrBrightness.create(1000.125f, minimum, 600.25f)!!
            assertEquals(1000.125f, report.maxNits, 0f)
            assertEquals(minimum, report.minNits, 0f)
            assertEquals(600.25f, report.maxFullFrameNits, 0f)
        }
    }

    @Test
    fun invalidFieldsInvalidateTheEntireReport() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            assertNull(HdrBrightness.create(invalid, 0.001f, 600f))
            assertNull(HdrBrightness.create(1000f, invalid, 600f))
            assertNull(HdrBrightness.create(1000f, 0.001f, invalid))
        }
    }

    @Test
    fun enforcesTheHostRangeAndOrderingWithoutClamping() {
        assertNull(HdrBrightness.create(0.5f, 0f, 0.5f))
        assertNull(HdrBrightness.create(10001f, 0f, 600f))
        assertNull(HdrBrightness.create(1000f, 101f, 600f))
        assertNull(HdrBrightness.create(400f, 0.001f, 600f))
        assertNull(HdrBrightness.create(1000f, 20f, 10f))
        assertNull(HdrBrightness.create(1000f, 0f, 0f))
        assertNotNull(HdrBrightness.create(10000f, 100f, 10000f))
        assertNotNull(HdrBrightness.create(1f, 0f, 1f))
    }

    @Test
    fun unknownCapabilitiesStayAbsentWithoutARatioOrOverride() {
        val report = HdrBrightness.forSession(null)
        assertNull(report)
        assertEquals("", report?.toQueryParameters().orEmpty())
    }

    @Test
    fun serializationUsesProtocolNamesAndRoundTripsFloatsInAnyLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val report = HdrBrightness.create(1000.125f, 0.0000004f, 600.25f)!!
            val fields = report.toQueryParameters().removePrefix("&").split("&")
                .associate { it.substringBefore("=") to it.substringAfter("=") }
            assertEquals(setOf("minBrightness", "maxBrightness", "maxAverageBrightness"), fields.keys)
            assertEquals(report.minNits, fields.getValue("minBrightness").toFloat(), 0f)
            assertEquals(report.maxNits, fields.getValue("maxBrightness").toFloat(), 0f)
            assertEquals(report.maxFullFrameNits, fields.getValue("maxAverageBrightness").toFloat(), 0f)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun explicitManualOverridePreservesBlackLevelAndExistingFullFramePolicy() {
        val report = HdrBrightness.create(2000f, 0.0004f, 1200f)!!
        val target = HdrBrightness.forSession(report, 1000)!!
        assertEquals(1000f, target.maxNits, 0f)
        assertEquals(0.0004f, target.minNits, 0f)
        assertEquals(200f, target.maxFullFrameNits, 0f)
        assertEquals(2000f, report.maxNits, 0f)
    }

    @Test
    fun manualTargetCanReplaceUnknownCapabilitiesWithoutARatio() {
        val target = HdrBrightness.forSession(null, 1000)!!
        assertEquals(1000f, target.maxNits, 0f)
        assertEquals(0f, target.minNits, 0f)
        assertEquals(200f, target.maxFullFrameNits, 0f)
        assertEquals(300f, HdrBrightness.forSession(null, 1)!!.maxNits, 0f)
        assertEquals(4000f, HdrBrightness.forSession(null, 10000)!!.maxNits, 0f)
    }

    @Test
    fun edidUnderreportHintFiresOnlyForImplausiblyDimImpliedSdrWhite() {
        assertNull(HdrBrightness.impliedSdrWhiteIfPeakUnderreported(400.125f, null))
        for (ratio in listOf(-1f, 0f, 1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(HdrBrightness.impliedSdrWhiteIfPeakUnderreported(400.125f, ratio))
        }
        // A claimed peak is cross-checked even when the missing black level withholds the
        // complete report from the host.
        assertEquals(80.025f, HdrBrightness.impliedSdrWhiteIfPeakUnderreported(400.125f, 5f)!!, 0.001f)
        assertEquals(99.8f, HdrBrightness.impliedSdrWhiteIfPeakUnderreported(499f, 5f)!!, 0.001f)
        assertNull(HdrBrightness.impliedSdrWhiteIfPeakUnderreported(500f, 5f))
        assertEquals(99.01f, HdrBrightness.impliedSdrWhiteIfPeakUnderreported(10000f, 101f)!!, 0.01f)
    }
}
