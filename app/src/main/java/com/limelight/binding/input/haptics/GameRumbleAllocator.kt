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

/** Actual output capabilities, kept separate from measured signal features. */
internal data class GameRumbleContext(
    val mode: GameRumbleMode,
    val hasController: Boolean,
    val hasDevice: Boolean,
    val deviceTier: DeviceHapticsTier = DeviceHapticsTier.AMPLITUDE
)

/** Component gains before the single-motor fold. No primitive ownership is inferred here. */
internal data class RumbleAllocationPolicy(
    val deviceLowTransient: Float = 0.25f,
    val deviceHighTransient: Float = 1f,
    val deviceHighSustained: Float = 0.30f
) {
    init {
        require(listOf(deviceLowTransient, deviceHighTransient, deviceHighSustained)
            .all { it.isFinite() && it in 0f..1f })
    }
}

/** Pure allocation stage. The compatibility policy is explicit, pending actuator calibration. */
internal object GameRumbleAllocator {
    private val compatibilityPolicy = RumbleAllocationPolicy()

    fun allocate(
        context: GameRumbleContext,
        input: ControllerRumbleState,
        features: RumbleSignalFeatures?,
        policy: RumbleAllocationPolicy = compatibilityPolicy
    ): GameRumbleRoute = route(
        context.mode, input, context.hasController, context.hasDevice,
        context.deviceTier, features?.decomposition, policy
    )

    fun route(
        mode: GameRumbleMode,
        input: ControllerRumbleState,
        hasController: Boolean,
        hasDevice: Boolean,
        deviceTier: DeviceHapticsTier = DeviceHapticsTier.AMPLITUDE,
        decomposition: RumbleDecomposition? = null,
        policy: RumbleAllocationPolicy = compatibilityPolicy
    ): GameRumbleRoute = when (mode) {
        GameRumbleMode.COORDINATED -> when {
            hasController && hasDevice && !deviceTier.supportsGradedOutput ->
                GameRumbleRoute(controller = input, device = null)
            hasController && hasDevice -> GameRumbleRoute(
                controller = input,
                device = decomposition?.let { transientCompensationChannels(it, policy) }
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

    private fun transientCompensationChannels(d: RumbleDecomposition, policy: RumbleAllocationPolicy): ControllerRumbleState =
        ControllerRumbleState(
            lowFrequency = policy.deviceLowTransient * d.transientLow,
            highFrequency = policy.deviceHighTransient * d.transientHigh +
                policy.deviceHighSustained * d.sustainedHigh
        )

}
