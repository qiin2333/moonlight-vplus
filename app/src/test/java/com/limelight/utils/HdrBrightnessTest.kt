package com.limelight.utils

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HdrBrightnessTest {
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
    fun unknownCapabilitiesStayAbsentInAutomaticMode() {
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
    fun onlyAnExplicitManualTargetCanReplaceUnknownCapabilities() {
        val target = HdrBrightness.forSession(null, 1000)!!
        assertEquals(1000f, target.maxNits, 0f)
        assertEquals(0f, target.minNits, 0f)
        assertEquals(200f, target.maxFullFrameNits, 0f)
        assertEquals(300f, HdrBrightness.forSession(null, 1)!!.maxNits, 0f)
        assertEquals(4000f, HdrBrightness.forSession(null, 10000)!!.maxNits, 0f)
    }
}
