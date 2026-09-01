package com.limelight.binding.input

/**
 * Describes why controller 0's gyroscope needs to be sampled.
 *
 * Host motion reporting and local gyro assistants are independent consumers. Keeping
 * their demand separate prevents an assistant toggle or input-device reconfiguration
 * from accidentally cancelling the host's native motion request.
 */
internal class ControllerGyroDemandState(
    private val assistantReportRateHz: Short = 120
) {
    @Volatile
    var hostReportRateHz: Short = 0
        private set

    @Volatile
    var assistantEnabled: Boolean = false
        private set

    val shouldSample: Boolean
        get() = hostReportRateHz.toInt() != 0 || assistantEnabled

    val effectiveReportRateHz: Short
        get() = hostReportRateHz.takeIf { it.toInt() != 0 } ?: assistantReportRateHz

    fun updateHostReportRate(reportRateHz: Short) {
        hostReportRateHz = reportRateHz
    }

    fun updateAssistantEnabled(enabled: Boolean) {
        assistantEnabled = enabled
    }

    fun clear() {
        hostReportRateHz = 0
        assistantEnabled = false
    }
}
