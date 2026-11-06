package com.limelight.binding.input.haptics

import com.limelight.nvstream.HostGamepadSelection

/** A connection-scoped choice; legacy never advertises IR and keeps the host rumble path. */
enum class Ds5SimulationMode(val preferenceValue: String) {
    // Keep the existing saved value so older test installations retain their selection.
    LEGACY("legacy"), IR_ENHANCED("ir_v3");

    fun allowsNativeDeviceHaptics(output: GameRumbleMode, strength: Int,
                                 host: HostGamepadSelection): Boolean =
        requestsIr(nativePcm = false, controlOnly = false, host = host) &&
            output != GameRumbleMode.CONTROLLER && strength > 0

    fun requestsIr(nativePcm: Boolean, controlOnly: Boolean, host: HostGamepadSelection): Boolean =
        this == IR_ENHANCED && !nativePcm && !controlOnly &&
            host != HostGamepadSelection.XBOX && host != HostGamepadSelection.DS4

    fun sessionOptions(nativePcm: Boolean, controlOnly: Boolean,
                       host: HostGamepadSelection): Ds5HapticsSessionOptions =
        Ds5HapticsSessionOptions(nativePcm, requestsIr(nativePcm, controlOnly, host))

    companion object {
        fun fromPreference(value: String?): Ds5SimulationMode =
            if (value == "ir_enhanced") IR_ENHANCED else
                entries.firstOrNull { it.preferenceValue == value } ?: LEGACY
    }
}

data class Ds5HapticsSessionOptions(val nativePcm: Boolean, val authoredIr: Boolean)
