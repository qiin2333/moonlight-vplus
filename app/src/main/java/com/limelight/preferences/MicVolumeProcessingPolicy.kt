package com.limelight.preferences

internal object MicVolumeProcessingPolicy {
    const val OFF = "off"
    const val GAIN = "gain"
    const val BALANCE = "balance"
    const val LEGACY_PROCESSING_ONLY = "legacy-processing-only"

    data class Flags(
        val processing: Boolean,
        val gain: Boolean,
        val balance: Boolean,
    )

    fun normalize(mode: String?): String = when (mode) {
        GAIN, BALANCE, LEGACY_PROCESSING_ONLY -> mode
        else -> OFF
    }

    fun flagsFor(mode: String?): Flags = when (normalize(mode)) {
        GAIN -> Flags(processing = true, gain = true, balance = false)
        BALANCE -> Flags(processing = true, gain = false, balance = true)
        LEGACY_PROCESSING_ONLY -> Flags(processing = true, gain = false, balance = false)
        else -> Flags(processing = false, gain = false, balance = false)
    }

    fun modeFor(processing: Boolean, gain: Boolean, balance: Boolean): String = when {
        !processing -> OFF
        gain -> GAIN
        balance -> BALANCE
        processing -> LEGACY_PROCESSING_ONLY
        else -> OFF
    }
}
