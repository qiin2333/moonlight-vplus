package com.limelight.binding.input.haptics

/** User-selected routing policy for host-authored game rumble. */
enum class GameRumbleMode(val preferenceValue: String) {
    SMART("smart"),
    DEVICE("device"),
    CONTROLLER("controller");

    companion object {
        fun fromPreferenceValue(value: String?): GameRumbleMode =
            entries.firstOrNull { it.preferenceValue == value } ?: CONTROLLER

        fun fromLegacyFallback(enabled: Boolean): GameRumbleMode =
            if (enabled) SMART else CONTROLLER
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
 */
internal object GameRumbleRouter {
    private const val SMART_CONTROLLER_HIGH_GAIN = 0.45f
    private const val SMART_DEVICE_LOW_GAIN = 0.20f

    fun route(
        mode: GameRumbleMode,
        input: ControllerRumbleState,
        hasController: Boolean,
        hasDevice: Boolean
    ): GameRumbleRoute = when (mode) {
        GameRumbleMode.SMART -> when {
            hasController && hasDevice -> GameRumbleRoute(
                controller = ControllerRumbleState(
                    lowFrequency = input.lowFrequency,
                    highFrequency = input.highFrequency * SMART_CONTROLLER_HIGH_GAIN
                ),
                device = ControllerRumbleState(
                    lowFrequency = input.lowFrequency * SMART_DEVICE_LOW_GAIN,
                    highFrequency = input.highFrequency
                )
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
