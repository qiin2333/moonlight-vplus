package com.limelight.binding.video

/** Stream HDR state; mapped variants describe application output, not vendor-pipeline acceptance. */
enum class StreamHdrFormat(val displayName: String) {
    SDR("SDR"),
    HDR10("HDR10"),
    HDR10_PLUS("HDR10+"),
    HLG("HLG"),
    DOLBY_VISION("DV"),
    PYROWAVE_HDR10_PLUS_PQ("HDR10+ → PQ"),
    PYROWAVE_VIVID_PQ("Vivid → PQ"),
    PYROWAVE_VIVID_HLG("Vivid → HLG"),
    PYROWAVE_DV81_PQ("DV 8.1 → PQ"),
    PYROWAVE_DV84_HLG("DV 8.4 → HLG"),
    ;

    val isHdr: Boolean
        get() = this != SDR

    /** Clarifies the observable boundary used by detailed diagnostics. */
    val diagnosticName: String
        get() = when (this) {
            HDR10_PLUS -> "HDR10+ (metadata observed)"
            DOLBY_VISION -> "Dolby Vision (negotiated)"
            PYROWAVE_HDR10_PLUS_PQ, PYROWAVE_VIVID_PQ, PYROWAVE_VIVID_HLG,
            PYROWAVE_DV81_PQ, PYROWAVE_DV84_HLG -> "$displayName (application-mapped)"
            else -> displayName
        }
}

internal object PyrowaveHdrFormatPolicy {
    fun resolve(hdrEnabled: Boolean, hdrMode: Int, appliedDynamicFormat: Int,
                hdrStateKnown: Boolean = true): StreamHdrFormat {
        // This policy is queried only after successful PyroWave presentation,
        // whose native color-contract check already verified the base transfer.
        if ((hdrStateKnown && !hdrEnabled) || hdrMode == 0) return StreamHdrFormat.SDR
        return when (appliedDynamicFormat) {
            1 -> StreamHdrFormat.PYROWAVE_HDR10_PLUS_PQ
            2 -> StreamHdrFormat.PYROWAVE_VIVID_PQ
            3 -> StreamHdrFormat.PYROWAVE_VIVID_HLG
            4 -> StreamHdrFormat.PYROWAVE_DV81_PQ
            5 -> StreamHdrFormat.PYROWAVE_DV84_HLG
            else -> if (hdrMode == 2) StreamHdrFormat.HLG else StreamHdrFormat.HDR10
        }
    }
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
        dolbyVisionNegotiated: Boolean = false,
        dolbyVisionNegotiatedHlg: Boolean = false,
    ): StreamHdrFormat {
        val observedHdr10Plus = isTenBitStream &&
            isPqHdr &&
            hdr10PlusConfigured &&
            hdr10PlusMetadataObserved
        // Decoder-output metadata is sufficient evidence that the stream carries HDR10+ when the
        // initial host callback was missed. It cannot prove that a vendor display pipeline accepts
        // the metadata. An explicit host disable always wins over stale observations.
        val effectiveHdrEnabled = hdrEnabled || (!hdrStateKnown && observedHdr10Plus)

        if (!effectiveHdrEnabled || !isTenBitStream) {
            return StreamHdrFormat.SDR
        }
        if (dolbyVisionNegotiatedHlg) {
            // Profile 8.4 rides an HLG base layer; without this check the HLG
            // branch below would mislabel the session as plain HLG.
            return StreamHdrFormat.DOLBY_VISION
        }
        if (isHlg) {
            return StreamHdrFormat.HLG
        }
        if (!isPqHdr) {
            return StreamHdrFormat.SDR
        }
        if (dolbyVisionNegotiated) {
            // Negotiation-level evidence (X-SS-Dynamic-HDR), distinct from the
            // decoder-observed evidence behind HDR10+. The stream still carries
            // the HDR10+ SEI alongside the RPU (dual carry); DV is what the
            // host actually selected for this session.
            return StreamHdrFormat.DOLBY_VISION
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
