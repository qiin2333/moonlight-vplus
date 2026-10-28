package com.limelight.nvstream

data class Ds5HapticsIrExtendedLane(val continuousLow: Float, val continuousHigh: Float)

data class Ds5HapticsIrExtendedEvent(
    val eventId: Int, val sourceTimeUs: Long, val leftStrength: Float, val rightStrength: Float,
    val confidence: Float, val sharpness: Float
)

/** An original v2 base, with optional continuous/event enhancement negotiated separately. */
data class Ds5HapticsIrExtendedFrame(
    val base: Ds5HapticsIrFrame,
    val profile: Int, val epoch: Int, val windowEndUs: Long,
    val left: Ds5HapticsIrExtendedLane, val right: Ds5HapticsIrExtendedLane,
    val events: List<Ds5HapticsIrExtendedEvent>
) {
    val controllerNumber: Short get() = base.controllerNumber
    val flags: Int get() = base.flags
    val frameSequence: Int get() = base.sequenceNumber
    val sourceFrameCount: Int get() = base.sourceFrameCount
    val ended: Boolean get() = base.ended
}
