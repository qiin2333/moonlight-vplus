package com.limelight.binding.video

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Static mastering/content-light metadata delivered by Sunshine's existing
 * SS_HDR_METADATA control message. This is presentation metadata, not the
 * PyroWave bitstream color_metadata contract.
 */
internal data class PyrowaveHdrMetadata(
    val displayPrimaries: List<Point>,
    val whitePoint: Point,
    val maxDisplayLuminance: Float,
    val minDisplayLuminance: Float,
    val maxContentLightLevel: Float,
    val maxFrameAverageLightLevel: Float,
    val maxFullFrameLuminance: Float,
) {
    data class Point(val x: Float, val y: Float)

    fun isValidForHdr10(): Boolean {
        val points = displayPrimaries + whitePoint
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x <= 0f || it.y <= 0f || it.x > 1f || it.y > 1f }) {
            return false
        }
        if (!maxDisplayLuminance.isFinite() || maxDisplayLuminance <= 0f ||
            !minDisplayLuminance.isFinite() || minDisplayLuminance < 0f ||
            minDisplayLuminance > maxDisplayLuminance ||
            !maxContentLightLevel.isFinite() || maxContentLightLevel < 0f ||
            !maxFrameAverageLightLevel.isFinite() || maxFrameAverageLightLevel < 0f ||
            !maxFullFrameLuminance.isFinite() || maxFullFrameLuminance < 0f) {
            return false
        }
        return true
    }

    companion object {
        const val SS_HDR_METADATA_SIZE = 26

        /** Older/partial control messages may carry an all-zero metadata block. */
        fun isAbsent(bytes: ByteArray?): Boolean =
            bytes == null || bytes.size < SS_HDR_METADATA_SIZE || bytes.all { it.toInt() == 0 }

        /** Parse the little-endian common-c payload without inventing defaults. */
        fun fromSunshineBytes(bytes: ByteArray?): PyrowaveHdrMetadata? {
            if (bytes == null || bytes.size < SS_HDR_METADATA_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val fields = IntArray(13) { buffer.short.toInt() and 0xffff }
            if (fields.take(8).any { it > 50000 }) return null

            val primaries = (0 until 3).map { index ->
                Point(fields[index * 2] / 50000f, fields[index * 2 + 1] / 50000f)
            }
            val metadata = PyrowaveHdrMetadata(
                displayPrimaries = primaries,
                whitePoint = Point(fields[6] / 50000f, fields[7] / 50000f),
                maxDisplayLuminance = fields[8].toFloat(),
                minDisplayLuminance = fields[9] / 10000f,
                maxContentLightLevel = fields[10].toFloat(),
                maxFrameAverageLightLevel = fields[11].toFloat(),
                maxFullFrameLuminance = fields[12].toFloat(),
            )
            return metadata.takeIf { it.isValidForHdr10() }
        }
    }
}
