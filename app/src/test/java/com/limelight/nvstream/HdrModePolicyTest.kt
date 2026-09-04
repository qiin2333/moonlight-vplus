package com.limelight.nvstream

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrModePolicyTest {
    @Test
    fun hdr10PlusIsPqAndUsesHdr10OnTheWire() {
        assertTrue(HdrModePolicy.isHdr10PlusMode(MoonBridge.HDR_MODE_HDR10_PLUS))
        assertTrue(HdrModePolicy.isPqMode(MoonBridge.HDR_MODE_HDR10_PLUS))
        assertEquals(
            MoonBridge.HDR_MODE_HDR10,
            HdrModePolicy.toProtocolMode(MoonBridge.HDR_MODE_HDR10_PLUS),
        )
    }

    @Test
    fun staticHdr10RemainsPqWithoutChangingItsWireValue() {
        assertFalse(HdrModePolicy.isHdr10PlusMode(MoonBridge.HDR_MODE_HDR10))
        assertTrue(HdrModePolicy.isPqMode(MoonBridge.HDR_MODE_HDR10))
        assertEquals(
            MoonBridge.HDR_MODE_HDR10,
            HdrModePolicy.toProtocolMode(MoonBridge.HDR_MODE_HDR10),
        )
    }

    @Test
    fun hlgRemainsDistinctFromPq() {
        assertFalse(HdrModePolicy.isPqMode(MoonBridge.HDR_MODE_HLG))
        assertEquals(
            MoonBridge.HDR_MODE_HLG,
            HdrModePolicy.toProtocolMode(MoonBridge.HDR_MODE_HLG),
        )
    }

    @Test
    fun unknownModeCannotEscapeIntoTheNativeProtocol() {
        assertFalse(HdrModePolicy.isPqMode(99))
        assertEquals(MoonBridge.HDR_MODE_SDR, HdrModePolicy.toProtocolMode(99))
    }

    @Test
    fun dolbyVision84RidesHlgOnTheWireAndStaysOutOfPqClassification() {
        assertTrue(HdrModePolicy.isDolbyVisionMode(MoonBridge.HDR_MODE_DOLBY_VISION_84))
        assertTrue(HdrModePolicy.isDolbyVisionHlgMode(MoonBridge.HDR_MODE_DOLBY_VISION_84))
        // The 8.4 base layer is HLG, not PQ: this drives the host's
        // dynamicRangeMode=2 (and with it the 8.4-only negotiation rule).
        assertFalse(HdrModePolicy.isPqMode(MoonBridge.HDR_MODE_DOLBY_VISION_84))
        assertEquals(
            MoonBridge.HDR_MODE_HLG,
            HdrModePolicy.toProtocolMode(MoonBridge.HDR_MODE_DOLBY_VISION_84),
        )

        // 8.1 stays a PQ selection.
        assertTrue(HdrModePolicy.isDolbyVisionMode(MoonBridge.HDR_MODE_DOLBY_VISION))
        assertFalse(HdrModePolicy.isDolbyVisionHlgMode(MoonBridge.HDR_MODE_DOLBY_VISION))
        assertTrue(HdrModePolicy.isPqMode(MoonBridge.HDR_MODE_DOLBY_VISION))
    }

    @Test
    fun dolbyVision84SharesTheRequestGatesWith81() {
        val base = mutableMapOf(
            "hdrEnabled" to true,
            "displaySupportsDolbyVision" to true,
            "decoderSupportsDolbyVision" to true,
            "framegenRequested" to false,
        )
        fun request(hdrMode: Int) = HdrModePolicy.shouldRequestDolbyVision(
            hdrEnabled = base["hdrEnabled"] == true,
            hdrMode = hdrMode,
            displaySupportsDolbyVision = base["displaySupportsDolbyVision"] == true,
            decoderSupportsDolbyVision = base["decoderSupportsDolbyVision"] == true,
            framegenRequested = base["framegenRequested"] == true,
        )

        assertTrue(request(MoonBridge.HDR_MODE_DOLBY_VISION))
        assertTrue(request(MoonBridge.HDR_MODE_DOLBY_VISION_84))

        base["framegenRequested"] = true
        assertFalse(request(MoonBridge.HDR_MODE_DOLBY_VISION_84))
    }

    @Test
    fun hdr10PlusRequestRequiresExplicitModeDisplaySupportAndDirectRendering() {
        assertTrue(
            HdrModePolicy.shouldRequestHdr10Plus(
                hdrEnabled = true,
                hdrMode = MoonBridge.HDR_MODE_HDR10_PLUS,
                displaySupportsHdr10Plus = true,
                framegenRequested = false,
            ),
        )
        assertFalse(
            HdrModePolicy.shouldRequestHdr10Plus(
                hdrEnabled = true,
                hdrMode = MoonBridge.HDR_MODE_HDR10,
                displaySupportsHdr10Plus = true,
                framegenRequested = false,
            ),
        )
        assertFalse(
            HdrModePolicy.shouldRequestHdr10Plus(
                hdrEnabled = true,
                hdrMode = MoonBridge.HDR_MODE_HDR10_PLUS,
                displaySupportsHdr10Plus = true,
                framegenRequested = true,
            ),
        )
        assertFalse(
            HdrModePolicy.shouldRequestHdr10Plus(
                hdrEnabled = true,
                hdrMode = MoonBridge.HDR_MODE_HDR10_PLUS,
                displaySupportsHdr10Plus = false,
                framegenRequested = false,
            ),
        )
    }
}
