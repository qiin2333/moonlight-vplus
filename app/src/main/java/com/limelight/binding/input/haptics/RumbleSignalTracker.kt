package com.limelight.binding.input.haptics

/**
 * Measured level features, not game events or physical carrier frequencies.
 * Deltas and changedAtMs describe the last change, identified by revision; they are not
 * consumable events. A replay retains that revision even as the decomposition converges.
 */
internal data class RumbleSignalFeatures(
    val input: ControllerRumbleState,
    val decomposition: RumbleDecomposition,
    val revision: Long,
    val observedAtMs: Long,
    val changedAtMs: Long?,
    val lowDelta: Float,
    val highDelta: Float
)

/** One stream's causal history. Rendering ticks never manufacture a new signal change. */
internal class RumbleSignalTracker {
    private var nowMs = 0L
    private val envelope = RumbleEnvelopeAnalyzer(clockMs = { nowMs })
    private var input = ControllerRumbleState.ZERO
    private var revision = 0L
    private var changedAtMs: Long? = null
    private var lowDelta = 0f
    private var highDelta = 0f

    fun sample(value: ControllerRumbleState, timestampMs: Long): RumbleSignalFeatures {
        if (value != input) {
            lowDelta = value.lowFrequency - input.lowFrequency
            highDelta = value.highFrequency - input.highFrequency
            changedAtMs = timestampMs
            revision++
        }
        input = value
        return advance(timestampMs)
    }

    fun advance(timestampMs: Long): RumbleSignalFeatures {
        nowMs = timestampMs
        return RumbleSignalFeatures(
            input, envelope.decompose(input), revision, timestampMs,
            changedAtMs, lowDelta, highDelta
        )
    }
}
