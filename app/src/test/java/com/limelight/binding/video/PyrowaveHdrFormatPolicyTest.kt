package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PyrowaveHdrFormatPolicyTest {
    @Test
    fun lateHostCallbackDoesNotMislabelSuccessfullyPresentedHdrAsSdr() {
        assertEquals(StreamHdrFormat.PYROWAVE_DV81_PQ,
            PyrowaveHdrFormatPolicy.resolve(false, 1, 4, hdrStateKnown = false))
        assertEquals(StreamHdrFormat.HLG,
            PyrowaveHdrFormatPolicy.resolve(false, 2, 0, hdrStateKnown = false))
        assertEquals(StreamHdrFormat.SDR, PyrowaveHdrFormatPolicy.resolve(false, 1, 4, hdrStateKnown = true))
    }

    @Test
    fun baseHdrIsReportedUntilDynamicMetadataWasActuallyApplied() {
        assertEquals(StreamHdrFormat.HDR10, PyrowaveHdrFormatPolicy.resolve(true, 1, 0))
        assertEquals(StreamHdrFormat.HLG, PyrowaveHdrFormatPolicy.resolve(true, 2, 0))
        assertEquals(StreamHdrFormat.SDR, PyrowaveHdrFormatPolicy.resolve(false, 1, 4))
    }

    @Test
    fun applicationMappingCannotBeReportedAsNativeDolbyVision() {
        val formats = listOf(StreamHdrFormat.PYROWAVE_HDR10_PLUS_PQ, StreamHdrFormat.PYROWAVE_VIVID_PQ,
            StreamHdrFormat.PYROWAVE_VIVID_HLG, StreamHdrFormat.PYROWAVE_DV81_PQ, StreamHdrFormat.PYROWAVE_DV84_HLG)
        for ((index, format) in formats.withIndex()) {
            assertEquals(format, PyrowaveHdrFormatPolicy.resolve(true, if (index == 2 || index == 4) 2 else 1, index + 1))
            assertTrue(format.diagnosticName.contains("application-mapped"))
        }
    }
}
