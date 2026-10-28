package com.limelight.nvstream

data class Ds5HapticsIrV3Lane(
    val continuousLow: Float, val continuousHigh: Float, val rawRms: Float, val rawPeak: Float
)

data class Ds5HapticsIrV3Event(
    val eventId: Int, val sourceTimeUs: Long, val leftStrength: Float, val rightStrength: Float,
    val confidence: Float, val sharpness: Float
)

/** Profile 1 contains source-normalized continuous levels and independent transient events. */
data class Ds5HapticsIrV3Frame(
    val controllerNumber: Short, val flags: Int, val profile: Int, val epoch: Int,
    val frameSequence: Int, val windowEndUs: Long, val sourceFrameCount: Int,
    val left: Ds5HapticsIrV3Lane, val right: Ds5HapticsIrV3Lane,
    val events: List<Ds5HapticsIrV3Event>
) {
    val ended: Boolean get() = flags and Ds5HapticsIrFrame.FLAG_STREAM_END != 0
}
