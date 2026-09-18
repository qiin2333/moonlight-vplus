package com.limelight.binding.input.haptics

/** Tracks queued state, not acknowledgements. Access under the arrival metadata lock. */
internal class ControllerPcmReadiness {
    private val sent = BooleanArray(16)

    fun removed(player: Int) { sent[player] = false }

    fun update(player: Int, allocated: Boolean, ready: Boolean, send: (Boolean) -> Boolean) {
        if (!allocated) {
            removed(player)
        } else if (sent[player] != ready && send(ready)) {
            sent[player] = ready
        }
    }
}
