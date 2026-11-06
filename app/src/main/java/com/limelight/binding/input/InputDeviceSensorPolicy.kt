package com.limelight.binding.input

internal object InputDeviceSensorPolicy {
    private const val FIRST_SUPPORTED_SDK = 31
    private const val FIRST_STABLE_SDK = 33

    /** API 31/32 are opt-in only because framework hotplug handling can crash. */
    fun isSupported(sdkInt: Int): Boolean = sdkInt >= FIRST_SUPPORTED_SDK

    fun isStable(sdkInt: Int): Boolean = sdkInt >= FIRST_STABLE_SDK

    /** Preserve the historical default below Android 12; only 12/12L are opt-in by default. */
    fun defaultEnabled(sdkInt: Int): Boolean = !isSupported(sdkInt) || isStable(sdkInt)

    fun shouldUse(sdkInt: Int, enabled: Boolean): Boolean =
        enabled && isSupported(sdkInt)
}
