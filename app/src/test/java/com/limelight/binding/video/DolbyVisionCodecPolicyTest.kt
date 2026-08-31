package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionCodecPolicyTest {
    @Test
    fun `dolby vision leaves low latency and dataspace to codec`() {
        assertFalse(
            DolbyVisionCodecPolicy.shouldApplyLowLatencyOptions(
                DolbyVisionCodecPolicy.MIME_TYPE,
            ),
        )
        assertFalse(
            DolbyVisionCodecPolicy.shouldForceSurfaceDataSpace(
                DolbyVisionCodecPolicy.MIME_TYPE,
            ),
        )
        assertFalse(
            DolbyVisionCodecPolicy.shouldAttachHdrStaticInfo(
                DolbyVisionCodecPolicy.MIME_TYPE,
            ),
        )
    }

    @Test
    fun `ordinary hdr codecs retain moonlight tuning`() {
        assertTrue(DolbyVisionCodecPolicy.shouldApplyLowLatencyOptions("video/hevc"))
        assertTrue(DolbyVisionCodecPolicy.shouldForceSurfaceDataSpace("video/hevc"))
        assertTrue(DolbyVisionCodecPolicy.shouldAttachHdrStaticInfo("video/hevc"))
        assertTrue(DolbyVisionCodecPolicy.shouldApplyLowLatencyOptions("video/av01"))
        assertTrue(DolbyVisionCodecPolicy.shouldForceSurfaceDataSpace("video/av01"))
        assertTrue(DolbyVisionCodecPolicy.shouldAttachHdrStaticInfo("video/av01"))
    }

    @Test
    fun `2560 by 1600 at 60 fps uses a complete profile 81 uhd60 record`() {
        val level = DolbyVisionCodecPolicy.selectSignalLevel(2560, 1600, 60)
        val record = DolbyVisionCodecPolicy.buildProfile81ConfigurationRecord(level)

        assertEquals("UHD60", level.label)
        assertEquals(9, level.recordValue)
        assertEquals(256, level.codecValue)
        assertEquals(24, record.size)
        assertEquals(8, (record[2].toInt() and 0xFF) shr 1)
        assertEquals(
            9,
            ((record[2].toInt() and 0x1) shl 5) or
                ((record[3].toInt() and 0xFF) shr 3),
        )
        assertEquals(1, (record[3].toInt() shr 2) and 0x1)
        assertEquals(0, (record[3].toInt() shr 1) and 0x1)
        assertEquals(1, record[3].toInt() and 0x1)
        assertEquals(1, (record[4].toInt() and 0xFF) shr 4)
        assertTrue(record.drop(5).all { it == 0.toByte() })
    }

    @Test
    fun `4k 30 fps maps to android uhd30 level 64`() {
        val level = DolbyVisionCodecPolicy.selectSignalLevel(3840, 2160, 30)

        assertEquals(7, level.recordValue)
        assertEquals(64, level.codecValue)
    }
}
