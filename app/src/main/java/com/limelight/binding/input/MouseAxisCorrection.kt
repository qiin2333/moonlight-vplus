package com.limelight.binding.input

enum class MouseAxisCorrection(val preferenceValue: String) {
    OFF("off"),
    ROTATE_90("90"),
    ROTATE_180("180"),
    ROTATE_270("270");

    fun apply(deltaX: Float, deltaY: Float): Pair<Float, Float> = when (this) {
        OFF -> deltaX to deltaY
        ROTATE_90 -> -deltaY to deltaX
        ROTATE_180 -> -deltaX to -deltaY
        ROTATE_270 -> deltaY to -deltaX
    }

    companion object {
        fun fromPreferenceValue(value: String?): MouseAxisCorrection =
            entries.firstOrNull { it.preferenceValue == value } ?: OFF
    }
}
