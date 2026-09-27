package com.limelight.preferences

/** Initial microphone state applied after a stream handshake completes. */
enum class MicrophoneInitialState(val preferenceValue: String) {
    OFF("off"),
    ON("on"),
    FOLLOW_LAST("follow_last");

    fun resolve(lastState: Boolean?): Boolean = when (this) {
        OFF -> false
        ON -> true
        FOLLOW_LAST -> lastState == true
    }

    companion object {
        fun fromPreferenceValue(value: String?): MicrophoneInitialState =
            entries.firstOrNull { it.preferenceValue == value } ?: OFF
    }
}
