package com.limelight.utils

/** One complete luminance report in nits. Unknown capabilities are represented by null. */
class HdrBrightness private constructor(
    val maxNits: Float,
    val minNits: Float,
    val maxFullFrameNits: Float,
) {
    /** Float.toString preserves precision and uses a locale-independent decimal point. */
    fun toQueryParameters(): String =
        "&minBrightness=$minNits&maxBrightness=$maxNits&maxAverageBrightness=$maxFullFrameNits"

    companion object {
        fun create(maxNits: Float, minNits: Float, maxFullFrameNits: Float): HdrBrightness? {
            if (!maxNits.isFinite() || !minNits.isFinite() || !maxFullFrameNits.isFinite() ||
                maxNits !in 1f..10000f || minNits !in 0f..100f ||
                maxFullFrameNits !in 1f..maxNits || minNits > maxFullFrameNits
            ) return null
            return HdrBrightness(maxNits, minNits, maxFullFrameNits)
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
