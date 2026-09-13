package com.limelight.binding.input.haptics

/** One authoritative level plus its temporal split, absent for untracked diagnostic input. */
internal data class RumbleSignalFeatures(
    val input: ControllerRumbleState,
    val decomposition: RumbleDecomposition? = null
)

/** One host stream's causal history, advanced independently of rendering and device discovery. */
internal class RumbleSignalTracker {
    private var nowMs = 0L
    private val envelope = RumbleEnvelopeAnalyzer(clockMs = { nowMs })
    private var input = ControllerRumbleState.ZERO

    fun sample(value: ControllerRumbleState, timestampMs: Long): RumbleSignalFeatures {
        input = value
        return advance(timestampMs)
    }

    fun advance(timestampMs: Long): RumbleSignalFeatures {
        nowMs = timestampMs
        return RumbleSignalFeatures(input, envelope.decompose(input))
    }
}
