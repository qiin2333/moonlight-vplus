package com.limelight.binding.input.haptics

/** User-selected routing policy for host-authored game rumble. */
enum class GameRumbleMode(val preferenceValue: String) {
    COORDINATED("smart"),
    DEVICE("device"),
    CONTROLLER("controller");

    companion object {
        fun fromPreferenceValue(value: String?): GameRumbleMode =
            entries.firstOrNull { it.preferenceValue == value } ?: CONTROLLER

        fun fromLegacyFallback(enabled: Boolean): GameRumbleMode =
            if (enabled) COORDINATED else CONTROLLER
    }
}

/** Independent outputs produced from a single host rumble state. */
internal data class GameRumbleRoute(
    val controller: ControllerRumbleState?,
    val device: ControllerRumbleState?
)

/**
 * Platform-neutral game-rumble routing policy.
 *
 * A null output means that sink must not receive game rumble. Audio-derived haptics are not an
 * input to this router, which keeps that feature independent from device game rumble.
 *
 * COORDINATED is compensation-first: the controller always carries the full sustained signal
 * (weight and continuity), while the body only adds what it can render more clearly - onset
 * transients plus a small share of sustained high. Sustained low is deliberately not copied to
 * the body. The temporal split must come from RumbleEnvelopeAnalyzer; without it the body
 * stays silent rather than guessing from the instantaneous level.
 *
 * Capability gating: a body without amplitude control (BINARY tier) cannot render compensated
 * detail - distinct amplitudes degrade to on/off - so it receives nothing in coordinated mode
 * and the controller keeps the full signal. A COMPOSITION-tier body has positively confirmed
 * primitive-grade transient rendering, so the controller additionally cedes a small share of
 * high-channel transients to it; low-channel transients are never yielded.
 */
internal object GameRumbleRouter {
    // Policy gains applied to the decomposition BEFORE the single-motor fold; the fold then
    // maps the two channels to one motor. End-to-end effective gains (pinned by tests):
    // transient high 0.33, transient low 0.20, sustained high 0.099, sustained low 0.
    private const val LOW_TRANSIENT_GAIN = 0.25f
    private const val HIGH_TRANSIENT_GAIN = 1.0f
    private const val HIGH_SUSTAINED_GAIN = 0.30f

    // Share of each channel's TRANSIENT that the controller cedes to the body once the body
    // has positively confirmed composition-grade rendering. Applies only on COMPOSITION-tier
    // bodies and only to the transient residual, never to the sustained signal. Kept small
    // until on-device verification; LOW_TRANSIENT_YIELD stays at zero.
    private const val HIGH_TRANSIENT_YIELD = 0.25f
    private const val LOW_TRANSIENT_YIELD = 0f

    fun route(
        mode: GameRumbleMode,
        input: ControllerRumbleState,
        hasController: Boolean,
        hasDevice: Boolean,
        deviceTier: DeviceHapticsTier = DeviceHapticsTier.AMPLITUDE,
        decomposition: RumbleDecomposition? = null
    ): GameRumbleRoute = when (mode) {
        GameRumbleMode.COORDINATED -> when {
            hasController && hasDevice && !deviceTier.supportsGradedOutput ->
                GameRumbleRoute(controller = input, device = null)
            hasController && hasDevice -> GameRumbleRoute(
                controller = if (deviceTier.supportsComposition && decomposition != null) {
                    yieldedControllerChannels(input, decomposition)
                } else {
                    input
                },
                device = decomposition?.let(::transientCompensationChannels)
            )
            hasController -> GameRumbleRoute(controller = input, device = null)
            hasDevice -> GameRumbleRoute(controller = null, device = input)
            else -> GameRumbleRoute(controller = null, device = null)
        }
        GameRumbleMode.DEVICE -> GameRumbleRoute(
            controller = null,
            device = input.takeIf { hasDevice }
        )
        GameRumbleMode.CONTROLLER -> GameRumbleRoute(
            controller = input.takeIf { hasController },
            device = null
        )
    }

    private fun transientCompensationChannels(d: RumbleDecomposition): ControllerRumbleState =
        ControllerRumbleState(
            lowFrequency = LOW_TRANSIENT_GAIN * d.transientLow,
            highFrequency = HIGH_TRANSIENT_GAIN * d.transientHigh +
                HIGH_SUSTAINED_GAIN * d.sustainedHigh
        )

    private fun yieldedControllerChannels(
        input: ControllerRumbleState,
        d: RumbleDecomposition
    ): ControllerRumbleState = ControllerRumbleState(
        // input == B + T, so input - yield * T == B + (1 - yield) * T.
        lowFrequency = (input.lowFrequency - LOW_TRANSIENT_YIELD * d.transientLow)
            .coerceAtLeast(0f),
        highFrequency = (input.highFrequency - HIGH_TRANSIENT_YIELD * d.transientHigh)
            .coerceAtLeast(0f)
    )
}
