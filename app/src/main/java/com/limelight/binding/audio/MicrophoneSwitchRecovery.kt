package com.limelight.binding.audio

/** Default stays default; an ignored explicit preference must not become the recovery target. */
internal fun microphoneRestoreInputId(preferredId: Int?, actualId: Int?): Int? =
    if (preferredId == null) null else actualId

/** Decisions run on the capture thread; native operations are performed by the recorder owner. */
internal class MicrophoneSwitchRecovery(
    targetId: Int?,
    previousActualId: Int?,
    private val restoreId: Int?,
    accepted: Boolean,
    nowMs: Long
) {
    enum class Action { WAIT, SUCCEEDED, FAILED, RESTORE, RECREATE, STOP }
    private enum class Phase { TARGET, REJECTED, RESTORED, RECREATED }

    private var phase = if (accepted) Phase.TARGET else Phase.REJECTED
    private var verification = MicrophoneRouteVerification(if (accepted) targetId else previousActualId, nowMs)
    private var hasData = false
    private var terminal: Action? = null

    fun observe(nowMs: Long, routedId: Int?, bytesRead: Int): Action {
        terminal?.let { return it }
        hasData = hasData || bytesRead > 0
        return when (verification.observe(nowMs, routedId, bytesRead)) {
            MicrophoneRouteVerification.Result.WAITING -> Action.WAIT
            MicrophoneRouteVerification.Result.READY -> {
                (if (phase == Phase.TARGET) Action.SUCCEEDED else Action.FAILED).also { terminal = it }
            }
            MicrophoneRouteVerification.Result.FAILED -> {
                when {
                    phase == Phase.RECREATED -> Action.STOP.also { terminal = it }
                    bytesRead < 0 || !hasData || phase == Phase.RESTORED -> recreate(nowMs)
                    else -> {
                        phase = Phase.RESTORED
                        recoveryStarted(nowMs)
                        Action.RESTORE
                    }
                }
            }
        }
    }

    fun recreate(nowMs: Long): Action {
        terminal?.let { return it }
        if (phase == Phase.RECREATED) return Action.STOP.also { terminal = it }
        phase = Phase.RECREATED
        recoveryStarted(nowMs)
        return Action.RECREATE
    }

    fun recoveryStarted(nowMs: Long) {
        verification = MicrophoneRouteVerification(restoreId, nowMs)
        hasData = false
    }
}
