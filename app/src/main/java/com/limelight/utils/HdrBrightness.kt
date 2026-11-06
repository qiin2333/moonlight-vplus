package com.limelight.utils

/** One complete luminance report in nits. Unknown capabilities are represented by null. */
class HdrBrightness private constructor(
    val maxNits: Float,
    val minNits: Float,
    val maxFullFrameNits: Float,
    val isEstimatedFromRatio: Boolean = false,
) {
    /** Float.toString preserves precision and uses a locale-independent decimal point. */
    fun toQueryParameters(): String =
        "&minBrightness=$minNits&maxBrightness=$maxNits&maxAverageBrightness=$maxFullFrameNits"

    companion object {
        private const val ASSUMED_SDR_WHITE_NITS = 300f
        private const val IMPLAUSIBLE_SDR_WHITE_NITS = 100f

        /** A ratio is usable only when finite and greater than 1 (1 means SDR-equal, no headroom). */
        fun usableHdrSdrRatio(ratio: Float?): Float? =
            ratio?.takeIf { it.isFinite() && it > 1f }

        /**
         * The SDR white implied by the driver-claimed EDID peak [edidPeakNits] and
         * [bestHdrSdrRatio], or null when the two are consistent. An implied SDR white below
         * [IMPLAUSIBLE_SDR_WHITE_NITS] means both cannot be true, so the driver peak is likely
         * underreported. Takes the raw peak so the hint still fires when the complete report is
         * withheld from the host over a missing black level. Diagnostic only — never alters a
         * report.
         */
        fun impliedSdrWhiteIfPeakUnderreported(edidPeakNits: Float, bestHdrSdrRatio: Float?): Float? {
            val ratio = usableHdrSdrRatio(bestHdrSdrRatio) ?: return null
            return (edidPeakNits / ratio).takeIf { it < IMPLAUSIBLE_SDR_WHITE_NITS }
        }

        fun create(maxNits: Float, minNits: Float, maxFullFrameNits: Float): HdrBrightness? {
            if (!maxNits.isFinite() || !minNits.isFinite() || !maxFullFrameNits.isFinite() ||
                maxNits !in 1f..10000f || minNits !in 0f..100f ||
                maxFullFrameNits !in 1f..maxNits || minNits > maxFullFrameNits
            ) return null
            return HdrBrightness(maxNits, minNits, maxFullFrameNits)
        }

        fun fromDisplay(
            maxNits: Float, minNits: Float, maxFullFrameNits: Float,
            hdrSdrRatio: Float?,
        ): HdrBrightness? {
            if (maxNits.isFinite() && maxNits in 1f..10000f) {
                return create(maxNits, minNits, maxFullFrameNits)
            }
            val ratio = usableHdrSdrRatio(hdrSdrRatio) ?: return null
            val estimatedPeak = ASSUMED_SDR_WHITE_NITS * ratio
            if (!estimatedPeak.isFinite() || estimatedPeak !in 1f..10000f) return null

            val knownFullFrame = maxFullFrameNits.takeIf { it.isFinite() && it in 1f..10000f }
            val peak = maxOf(estimatedPeak, knownFullFrame ?: 0f)
            val minimum = minNits.takeIf { it.isFinite() && it in 0f..100f } ?: 0.001f
            val fullFrame = knownFullFrame ?: maxOf(minimum, peak * 0.2f)
            // Preserve known fields; contradictory system values still fall back to the host.
            create(peak, minimum, fullFrame) ?: return null
            return HdrBrightness(peak, minimum, fullFrame, isEstimatedFromRatio = true)
        }

        fun forSession(reported: HdrBrightness?, peakOverride: Int? = null): HdrBrightness? {
            if (peakOverride == null) return reported
            // Preserve the explicit manual target policy, never use it as an automatic report.
            val peak = peakOverride.coerceIn(300, 4000).toFloat()
            val minimum = reported?.minNits ?: 0f
            val fullFrame = (peak * 0.2f).coerceIn(100f, 1000f)
            return create(peak, minimum, fullFrame)
        }
    }
}
