package com.limelight.binding.input

internal object InputDeviceSensorPolicy {
    private const val FIRST_SAFE_SDK = 33

    fun isSupported(sdkInt: Int): Boolean = sdkInt >= FIRST_SAFE_SDK

    fun shouldUse(sdkInt: Int, enabled: Boolean): Boolean =
        enabled && isSupported(sdkInt)
}
