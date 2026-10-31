package com.limelight.nvstream

/** Authored actuator features, before conversion to ordinary two-motor rumble. */
data class Ds5HapticsIrLane(
    val rms: Float,
    val peak: Float,
    val transient: Float,
    val lowBandRatio: Float,
    val zeroCrossingRate: Float
)

data class Ds5HapticsIrFrame(
    val controllerNumber: Short,
    val flags: Int,
    val sequenceNumber: Int,
    val presentationTimeUs: Long,
    val sourceFrameCount: Int,
    val left: Ds5HapticsIrLane,
    val right: Ds5HapticsIrLane
) {
    val ended: Boolean get() = flags and FLAG_STREAM_END != 0
    val silent: Boolean get() = flags and FLAG_SILENT != 0

    companion object {
        const val FLAG_DISCONTINUITY = 0x01
        const val FLAG_STREAM_END = 0x04
        const val FLAG_SILENT = 0x08
    }
}
