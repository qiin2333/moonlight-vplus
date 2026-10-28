package com.limelight.binding.input.haptics

/** Backend choice is local: one phone actuator cannot overlay a preset on a waveform. */
internal class AuthoredV3PhonePolicy {
    private var continuousUntilMs = 0L

    fun useDetails(body: ControllerRumbleState, nowMs: Long, ended: Boolean,
                   mode: GameRumbleMode, hasController: Boolean, supportsTick: Boolean): Boolean {
        if (ended) continuousUntilMs = 0
        else if (maxOf(body.lowFrequency, body.highFrequency) > 0.005f) continuousUntilMs = nowMs + 80
        return supportsTick && mode != GameRumbleMode.CONTROLLER &&
            (mode == GameRumbleMode.COORDINATED && hasController || nowMs >= continuousUntilMs)
    }
}
