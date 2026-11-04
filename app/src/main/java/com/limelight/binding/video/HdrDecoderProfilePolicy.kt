package com.limelight.binding.video

/** Pure policy for ordered HDR decoder profile configuration and compatibility fallback. */
internal object HdrDecoderProfilePolicy {
    /** Main10-only decoders are a valid compatibility path for static HDR10. */
    fun isStaticHdr10Compatible(
        profile: Int,
        main10Profile: Int,
        hdr10Profile: Int,
        hdr10PlusProfile: Int,
    ): Boolean = profile == main10Profile ||
        profile == hdr10Profile ||
        profile == hdr10PlusProfile

    fun buildCandidates(
        isTenBit: Boolean,
        isPqHdr: Boolean,
        hdr10PlusEligible: Boolean,
        hdr10Advertised: Boolean,
        hdr10PlusProfile: Int,
        hdr10Profile: Int,
    ): List<Int?> {
        if (!isTenBit || !isPqHdr) {
            return listOf(null)
        }

        val candidates = mutableListOf<Int?>()
        if (hdr10PlusEligible) {
            candidates += hdr10PlusProfile
        }
        if (hdr10Advertised) {
            candidates += hdr10Profile
        }
        // Preserve the historical profile-less MediaCodec configuration as the final fallback.
        candidates += null
        return candidates.distinct()
    }
}
