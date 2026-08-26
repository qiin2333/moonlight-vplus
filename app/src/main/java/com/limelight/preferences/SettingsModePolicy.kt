package com.limelight.preferences

internal enum class BackgroundStreamBehavior(
    val preferenceValue: String,
    val resumeAutomatically: Boolean,
    val keepConnected: Boolean,
    val playAudio: Boolean,
) {
    DISCONNECT("disconnect", false, false, false),
    RESUME("resume", true, false, false),
    KEEP_CONNECTED("keep_connected", true, true, false),
    KEEP_AUDIO("keep_audio", true, true, true),
}

internal object BackgroundStreamBehaviorPolicy {
    fun fromLegacy(
        resumeAutomatically: Boolean,
        keepConnected: Boolean,
        playAudio: Boolean,
    ): BackgroundStreamBehavior = when {
        playAudio -> BackgroundStreamBehavior.KEEP_AUDIO
        keepConnected -> BackgroundStreamBehavior.KEEP_CONNECTED
        resumeAutomatically -> BackgroundStreamBehavior.RESUME
        else -> BackgroundStreamBehavior.DISCONNECT
    }

    fun fromPreferenceValue(value: String?): BackgroundStreamBehavior =
        BackgroundStreamBehavior.entries.firstOrNull { it.preferenceValue == value }
            ?: BackgroundStreamBehavior.DISCONNECT
}

internal enum class QuitBehavior(val preferenceValue: String) {
    STOP_HOST_APP("stop_host_app"),
    DISCONNECT_ONLY("disconnect_only");

    companion object {
        fun fromLegacy(disconnectOnly: Boolean): QuitBehavior =
            if (disconnectOnly) DISCONNECT_ONLY else STOP_HOST_APP

        fun fromPreferenceValue(value: String?): QuitBehavior =
            entries.firstOrNull { it.preferenceValue == value } ?: STOP_HOST_APP
    }
}

internal enum class DualSenseOutputMode(
    val preferenceValue: String,
    val systemBluetooth: Boolean,
    val wirelessBridge: Boolean,
) {
    OFF("off", false, false),
    SYSTEM_BLUETOOTH("system_bluetooth", true, false),
    WIRELESS_BRIDGE("wireless_bridge", false, true),
    BOTH("both", true, true);

    companion object {
        fun fromLegacy(systemBluetooth: Boolean, wirelessBridge: Boolean): DualSenseOutputMode =
            when {
                systemBluetooth && wirelessBridge -> BOTH
                systemBluetooth -> SYSTEM_BLUETOOTH
                wirelessBridge -> WIRELESS_BRIDGE
                else -> OFF
            }

        fun fromPreferenceValue(value: String?): DualSenseOutputMode =
            entries.firstOrNull { it.preferenceValue == value } ?: OFF
    }
}
