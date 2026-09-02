package com.limelight.binding.input

internal const val CONTROLLER_DEADZONE_PREFERENCE_KEY = "seekbar_deadzone"

internal fun controllerStickDeadzoneRadius(deadzonePercentage: Int): Double {
    return deadzonePercentage.coerceAtLeast(0).toDouble() / 100.0
}

internal fun isZeroControllerDeadzone(preferenceKey: String?, value: Int): Boolean {
    return preferenceKey == CONTROLLER_DEADZONE_PREFERENCE_KEY && value == 0
}
