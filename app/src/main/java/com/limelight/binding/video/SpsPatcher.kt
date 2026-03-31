package com.limelight.binding.video

import android.os.Build
import com.limelight.LimeLog
import org.jcodec.codecs.h264.H264Utils
import org.jcodec.codecs.h264.io.model.SeqParameterSet
import org.jcodec.codecs.h264.io.model.VUIParameters
import java.nio.ByteBuffer

/**
 * Handles H.264 SPS (Sequence Parameter Set) parsing, patching, and reconstruction.
 *
 * Extracts device-specific SPS workarounds from the main decoder renderer:
 * - Level IDC patching based on resolution/frame rate
 * - Reference frame count fixup (fixes OMAPs, Exynos 4, etc.)
 * - VUI parameter adjustments (bitstream restrictions, color info)
 * - Constrained High Profile flag patching
 * - Baseline SPS hack for legacy decoders
 */
internal class SpsPatcher(
    private val constrainedHighProfile: Boolean,
    private val needsSpsBitstreamFixup: Boolean,
    private val isExynos4: Boolean,
    private val hasHevcDecoder: Boolean,
    private val hasAv1Decoder: Boolean,
) {
    /**
     * Result of patching an H.264 SPS NALU.
     * @param patchedNalu The complete patched NALU including start sequence header byte
     * @param savedSps Non-null if baseline hack was applied and a replay is needed
     */
    class PatchResult(
        val patchedNalu: ByteArray,
        val savedSps: SeqParameterSet?,
    )

    /**
     * Parses, patches, and reconstructs an H.264 SPS NALU.
     *
     * @param decodeUnitData Raw NALU data including Annex B start code
     * @param decodeUnitLength Length of valid data in the array
     * @param refFrameInvalidationActive Whether RFI is enabled (skips some patches)
     * @param initialWidth Stream width in pixels
     * @param initialHeight Stream height in pixels
     * @param refreshRate Stream frame rate
     * @param needsBaselineSpsHack Whether the decoder needs the baseline profile hack
     * @return Patch result containing the reconstructed NALU and optional saved SPS
     */
    fun patchSps(
        decodeUnitData: ByteArray,
        decodeUnitLength: Int,
        refFrameInvalidationActive: Boolean,
        initialWidth: Int,
        initialHeight: Int,
        refreshRate: Int,
        needsBaselineSpsHack: Boolean,
    ): PatchResult {
        val startSeqLen = if (decodeUnitData[2] == 0x01.toByte()) 3 else 4

        // Parse the SPS (H264Utils safely handles Annex B NALUs with escape sequences)
        val spsBuf = ByteBuffer.wrap(decodeUnitData)
        spsBuf.position(startSeqLen + 1) // Skip start code + NAL header
        val sps = H264Utils.readSPS(spsBuf)

        // Patch level IDC based on resolution (reduces decoder buffering)
        if (!refFrameInvalidationActive) {
            patchLevelIdc(sps, initialWidth, initialHeight, refreshRate)
        }

        // Fix reference frame count (helps OMAP4, Exynos 4, and most other decoders)
        if (!refFrameInvalidationActive) {
            LimeLog.info("Patching num_ref_frames in SPS")
            sps.numRefFrames = 1
        }

        // Remove VUI extensions on old devices without HEVC/AV1 support
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
            sps.vuiParams != null && !hasHevcDecoder && !hasAv1Decoder
        ) {
            sps.vuiParams.videoSignalTypePresentFlag = false
            sps.vuiParams.colourDescriptionPresentFlag = false
            sps.vuiParams.chromaLocInfoPresentFlag = false
        }

        // Patch bitstream restrictions
        patchBitstreamRestrictions(sps)

        // Apply baseline hack if needed
        var savedSps: SeqParameterSet? = null
        if (needsBaselineSpsHack) {
            LimeLog.info("Hacking SPS to baseline")
            sps.profileIdc = 66
            savedSps = sps
        }

        // Patch constraint flags for Constrained High Profile
        patchConstraintFlags(sps)

        // Serialize the patched SPS back to Annex B format
        val escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength)
        val naluBuffer = ByteArray(startSeqLen + 1 + escapedNalu.limit())
        System.arraycopy(decodeUnitData, 0, naluBuffer, 0, startSeqLen + 1)
        escapedNalu.get(naluBuffer, startSeqLen + 1, escapedNalu.limit())

        return PatchResult(naluBuffer, savedSps)
    }

    /**
     * Builds a replay SPS NALU for the baseline hack (switches back to High profile).
     */
    fun buildReplaySps(savedSps: SeqParameterSet): ByteArray {
        // Switch back to High profile
        savedSps.profileIdc = 100
        patchConstraintFlags(savedSps)

        val escapedNalu = H264Utils.writeSPS(savedSps, 128)
        // Annex B header + NAL type 0x67 (SPS)
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67)
        val result = ByteArray(header.size + escapedNalu.limit())
        System.arraycopy(header, 0, result, 0, header.size)
        escapedNalu.get(result, header.size, escapedNalu.limit())
        return result
    }

    private fun patchLevelIdc(
        sps: SeqParameterSet, width: Int, height: Int, fps: Int
    ) {
        when {
            width <= 720 && height <= 480 && fps <= 60 -> {
                LimeLog.info("Patching level_idc to 31")
                sps.levelIdc = 31
            }
            width <= 1280 && height <= 720 && fps <= 60 -> {
                LimeLog.info("Patching level_idc to 32")
                sps.levelIdc = 32
            }
            width <= 1920 && height <= 1080 && fps <= 60 -> {
                LimeLog.info("Patching level_idc to 42")
                sps.levelIdc = 42
            }
            // else: leave the profile alone (currently 5.0)
        }
    }

    private fun patchConstraintFlags(sps: SeqParameterSet) {
        if (sps.profileIdc == 100 && constrainedHighProfile) {
            LimeLog.info("Setting constraint set flags for constrained high profile")
            sps.constraintSet4Flag = true
            sps.constraintSet5Flag = true
        } else {
            sps.constraintSet4Flag = false
            sps.constraintSet5Flag = false
        }
    }

    private fun patchBitstreamRestrictions(sps: SeqParameterSet) {
        if (needsSpsBitstreamFixup || isExynos4 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (sps.vuiParams == null) {
                LimeLog.info("Adding VUI parameters")
                sps.vuiParams = VUIParameters()
            }

            if (sps.vuiParams.bitstreamRestriction == null) {
                LimeLog.info("Adding bitstream restrictions")
                sps.vuiParams.bitstreamRestriction = VUIParameters.BitstreamRestriction().apply {
                    motionVectorsOverPicBoundariesFlag = true
                    maxBytesPerPicDenom = 2
                    maxBitsPerMbDenom = 1
                    log2MaxMvLengthHorizontal = 16
                    log2MaxMvLengthVertical = 16
                    numReorderFrames = 0
                }
            } else {
                LimeLog.info("Patching bitstream restrictions")
            }

            sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2
                sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1
            }
        } else if (sps.vuiParams != null) {
            sps.vuiParams.bitstreamRestriction = null
        }
    }
}
