package com.limelight.binding.video

import java.nio.ByteBuffer
import org.jcodec.codecs.h264.H264Utils
import org.jcodec.codecs.h264.io.model.SeqParameterSet
import org.jcodec.common.model.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpsPatcherTest {
    @Test
    fun codecRestartReplaysBaselineThenHighAgain() {
        val patcher = patcher(baselineRequired = true)
        val input = highProfileSps()
        repeat(2) {
            patcher.onCodecConfigured()
            assertEquals(66, readSps(patch(patcher, input)).profileIdc)
            assertEquals(100, readSps(patcher.buildReplaySps()).profileIdc)
            patcher.onReplaySubmitted()
            assertFalse(patcher.needsBaselineSpsReplay)
            assertEquals(100, readSps(patch(patcher, input)).profileIdc)
        }
    }

    @Test
    fun restartDiscardsReplayFromPreviousConfiguration() {
        val patcher = patcher(baselineRequired = true)
        patch(patcher, highProfileSps(widthInMbs = 120))
        patcher.onCodecConfigured()
        assertThrows(IllegalStateException::class.java) { patcher.buildReplaySps() }

        patch(patcher, highProfileSps(widthInMbs = 80))
        assertEquals(79, readSps(patcher.buildReplaySps()).picWidthInMbsMinus1)
    }

    @Test
    fun buildingReplayDoesNotConsumeItBeforeQueueSuccess() {
        val patcher = patcher(baselineRequired = true)
        patch(patcher, highProfileSps())
        patcher.buildReplaySps()
        assertTrue(patcher.needsBaselineSpsReplay)
        assertEquals(100, readSps(patcher.buildReplaySps()).profileIdc)
        patcher.onReplaySubmitted()
        assertFalse(patcher.needsBaselineSpsReplay)
        assertThrows(IllegalStateException::class.java) { patcher.buildReplaySps() }
    }

    @Test
    fun otherDecodersKeepHighProfileAcrossRestart() {
        val patcher = patcher(baselineRequired = false)
        repeat(2) {
            patcher.onCodecConfigured()
            assertFalse(patcher.needsBaselineSpsReplay)
            assertEquals(100, readSps(patch(patcher, highProfileSps())).profileIdc)
        }
    }

    private fun patcher(baselineRequired: Boolean) =
        SpsPatcher(false, false, false, true, false, baselineRequired)

    private fun patch(patcher: SpsPatcher, input: ByteArray) =
        patcher.patchSps(input, input.size, true, 1920, 1080, 60)

    private fun highProfileSps(widthInMbs: Int = 120): ByteArray {
        val sps = SeqParameterSet().apply {
            profileIdc = 100
            levelIdc = 42
            chromaFormatIdc = ColorSpace.YUV420J
            picOrderCntType = 2
            numRefFrames = 1
            picWidthInMbsMinus1 = widthInMbs - 1
            picHeightInMapUnitsMinus1 = 67
            frameMbsOnlyFlag = true
            direct8x8InferenceFlag = true
        }
        val payload = H264Utils.writeSPS(sps, 128)
        return byteArrayOf(0, 0, 0, 1, 0x67) + ByteArray(payload.remaining()).also(payload::get)
    }

    private fun readSps(nalu: ByteArray): SeqParameterSet =
        H264Utils.readSPS(ByteBuffer.wrap(nalu).apply { position(5) })
}
