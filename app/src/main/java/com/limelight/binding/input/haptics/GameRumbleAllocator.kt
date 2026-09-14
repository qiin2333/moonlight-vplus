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
    val device: ControllerRumbleState?,
    /** Recompute this plan as its temporal compensation converges, even without new input. */
    val needsAdvance: Boolean = false
)

/** Actual output capabilities, kept separate from measured signal features. */
internal data class GameRumbleContext(
    val mode: GameRumbleMode,
    val hasController: Boolean,
    val hasDevice: Boolean,
    val deviceTier: DeviceHapticsTier = DeviceHapticsTier.AMPLITUDE
)

/** Pure allocation stage. Compatibility gains remain local until actuator calibration. */
internal object GameRumbleAllocator {
    private const val DEVICE_LOW_TRANSIENT = 0.25f
    private const val DEVICE_HIGH_TRANSIENT = 1f
    private const val DEVICE_HIGH_SUSTAINED = 0.30f

    fun allocate(context: GameRumbleContext, signal: RumbleSignalFeatures): GameRumbleRoute {
        val input = signal.input
        val decomposition = signal.decomposition
        val hasController = context.hasController
        val hasDevice = context.hasDevice
        return when (context.mode) {
            GameRumbleMode.COORDINATED -> when {
                hasController && hasDevice && !context.deviceTier.supportsGradedOutput ->
                    GameRumbleRoute(controller = input, device = null)
                hasController && hasDevice -> GameRumbleRoute(
                    controller = input,
                    device = decomposition?.let(::transientCompensationChannels),
                    needsAdvance = decomposition?.hasUnsettledTransients == true
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
    }

    private fun transientCompensationChannels(d: RumbleDecomposition): ControllerRumbleState =
        ControllerRumbleState(
            lowFrequency = DEVICE_LOW_TRANSIENT * d.transientLow,
            highFrequency = DEVICE_HIGH_TRANSIENT * d.transientHigh +
                DEVICE_HIGH_SUSTAINED * d.sustainedHigh
        )

}
