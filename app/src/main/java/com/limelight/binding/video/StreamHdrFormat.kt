package com.limelight.binding.video

/** Dynamic-range format currently observed on the decoded input stream. */
enum class StreamHdrFormat(val displayName: String) {
    SDR("SDR"),
    HDR10("HDR10"),
    HDR10_PLUS("HDR10+"),
    HLG("HLG"),
    ;

    val isHdr: Boolean
        get() = this != SDR
}

/** Pure policy that keeps capability/configuration distinct from observed stream state. */
internal object StreamHdrFormatPolicy {
    fun resolve(
        hdrEnabled: Boolean,
        hdrStateKnown: Boolean,
        isTenBitStream: Boolean,
        isPqHdr: Boolean,
        isHlg: Boolean,
        hdr10PlusConfigured: Boolean,
        hdr10PlusMetadataObserved: Boolean,
    ): StreamHdrFormat {
        val observedHdr10Plus = isTenBitStream &&
            isPqHdr &&
            hdr10PlusConfigured &&
            hdr10PlusMetadataObserved
        // Dynamic metadata is sufficient proof of HDR10+ when the initial host state callback was
        // missed. An explicit host disable always wins over previously observed metadata.
        val effectiveHdrEnabled = hdrEnabled || (!hdrStateKnown && observedHdr10Plus)

        if (!effectiveHdrEnabled || !isTenBitStream) {
            return StreamHdrFormat.SDR
        }
        if (isHlg) {
            return StreamHdrFormat.HLG
        }
        if (!isPqHdr) {
            return StreamHdrFormat.SDR
        }
        return if (observedHdr10Plus) {
            StreamHdrFormat.HDR10_PLUS
        } else {
            StreamHdrFormat.HDR10
        }
    }
}

/** Defines which host HDR transitions begin a new dynamic-metadata observation epoch. */
internal object HdrObservationEpochPolicy {
    fun shouldReset(previousEnabled: Boolean?, enabled: Boolean): Boolean =
        previousEnabled != enabled && !(previousEnabled == null && enabled)
}
