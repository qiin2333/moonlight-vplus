package com.limelight.binding.input.haptics

import com.limelight.nvstream.HostGamepadSelection

/** A connection-scoped choice; legacy never advertises IR and keeps the host rumble path. */
enum class Ds5SimulationMode(val preferenceValue: String) {
    LEGACY("legacy"), IR_V3("ir_v3");

    fun requestsIr(nativePcm: Boolean, controlOnly: Boolean, host: HostGamepadSelection): Boolean =
        this == IR_V3 && !nativePcm && !controlOnly &&
            host != HostGamepadSelection.XBOX && host != HostGamepadSelection.DS4

    fun sessionOptions(nativePcm: Boolean, controlOnly: Boolean,
                       host: HostGamepadSelection): Ds5HapticsSessionOptions =
        Ds5HapticsSessionOptions(nativePcm, requestsIr(nativePcm, controlOnly, host))

    companion object {
        fun fromPreference(value: String?): Ds5SimulationMode =
            entries.firstOrNull { it.preferenceValue == value } ?: LEGACY
    }
}

data class Ds5HapticsSessionOptions(val nativePcm: Boolean, val authoredIr: Boolean)
