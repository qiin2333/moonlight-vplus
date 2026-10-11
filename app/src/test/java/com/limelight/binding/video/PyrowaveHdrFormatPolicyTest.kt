package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Test

class PyrowaveHdrFormatPolicyTest {
    @Test
    fun lateHostCallbackDoesNotMislabelSuccessfullyPresentedHdrAsSdr() {
        assertEquals(StreamHdrFormat.PYROWAVE_VIVID_PQ,
            PyrowaveHdrFormatPolicy.resolve(false, 1, 2, hdrStateKnown = false))
        assertEquals(StreamHdrFormat.PYROWAVE_VIVID_HLG,
            PyrowaveHdrFormatPolicy.resolve(false, 2, 3, hdrStateKnown = false))
        assertEquals(StreamHdrFormat.HDR10_PLUS,
            PyrowaveHdrFormatPolicy.resolve(false, 1, 1, hdrStateKnown = false))
        assertEquals(StreamHdrFormat.DOLBY_VISION,
            PyrowaveHdrFormatPolicy.resolve(false, 1, 4, hdrStateKnown = false))
        assertEquals(StreamHdrFormat.SDR, PyrowaveHdrFormatPolicy.resolve(false, 1, 2, hdrStateKnown = true))
    }

    @Test
    fun baseHdrIsReportedUntilDynamicMetadataWasActuallyApplied() {
        assertEquals(StreamHdrFormat.HDR10, PyrowaveHdrFormatPolicy.resolve(true, 1, 0))
        assertEquals(StreamHdrFormat.HLG, PyrowaveHdrFormatPolicy.resolve(true, 2, 0))
        assertEquals(StreamHdrFormat.SDR, PyrowaveHdrFormatPolicy.resolve(false, 1, 4))
    }

    @Test
    fun dynamicFormatsAreReportedAfterApplicationOrNativeMapping() {
        assertEquals(StreamHdrFormat.PYROWAVE_VIVID_PQ,
            PyrowaveHdrFormatPolicy.resolve(true, 1, 2))
        assertEquals(StreamHdrFormat.PYROWAVE_VIVID_HLG,
            PyrowaveHdrFormatPolicy.resolve(true, 2, 3))
        assertEquals(StreamHdrFormat.PYROWAVE_VIVID_PQ.diagnosticName, "Vivid")
        assertEquals(StreamHdrFormat.PYROWAVE_VIVID_HLG.diagnosticName, "Vivid")

        assertEquals(StreamHdrFormat.HDR10_PLUS, PyrowaveHdrFormatPolicy.resolve(true, 1, 1))
        assertEquals(StreamHdrFormat.DOLBY_VISION, PyrowaveHdrFormatPolicy.resolve(true, 1, 4))
        assertEquals(StreamHdrFormat.DOLBY_VISION, PyrowaveHdrFormatPolicy.resolve(true, 2, 5))
    }
}
