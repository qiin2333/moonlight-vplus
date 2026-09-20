package com.limelight.gamemenu

import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.haptics.HapticAvailability
import com.limelight.binding.input.haptics.HapticEvidence

internal data class WaveformRouteCardState(
    val id: Int, val label: String, val status: String, val canTest: Boolean, val testing: Boolean
)

internal data class WaveformHapticsCardState(
    val routes: List<WaveformRouteCardState> = emptyList()
)

/**
 * Owns the Game Menu waveform-haptics card state.
 *
 * The card only surfaces while at least one waveform route exists; devices without
 * waveform support never see it. Status rows and channel tests concern the USB
 * output transport, which is why this lives apart from the audio-haptics card.
 */
internal class WaveformHapticsCardController(private val game: Game) {
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            if (onStateChanged == null) return
            val routes = readRoutes()
            if (state.routes != routes) {
                state = WaveformHapticsCardState(routes)
                emitState()
            }
            statusHandler.postDelayed(this, if (routes.any { it.testing }) 100 else 1000)
        }
    }
    private var onStateChanged: ((WaveformHapticsCardState) -> Unit)? = null
    private var state = WaveformHapticsCardState()

    fun snapshot(): WaveformHapticsCardState = state

    fun start(onStateChanged: (WaveformHapticsCardState) -> Unit) {
        this.onStateChanged = onStateChanged
        state = WaveformHapticsCardState(readRoutes())
        emitState()
        statusHandler.removeCallbacks(statusPoll)
        statusHandler.post(statusPoll)
    }

    fun dispose() {
        statusHandler.removeCallbacks(statusPoll)
        runCatching { game.controllerHandler.cancelWaveformTests() }
        onStateChanged = null
    }

    fun toggleWaveformTest(routeId: Int, cancel: Boolean) {
        game.controllerHandler.testWaveformChannels(routeId, cancel)
        statusHandler.removeCallbacks(statusPoll)
        statusHandler.post(statusPoll)
    }

    private fun readRoutes(): List<WaveformRouteCardState> =
        runCatching { game.controllerHandler.waveformHapticsRoutes() }.getOrDefault(emptyList()).map { view ->
            val capability = view.route.capability
            val statusResource = when (capability.availability) {
                HapticAvailability.READY -> R.string.waveform_status_ready
                HapticAvailability.NEEDS_VALIDATION -> R.string.waveform_status_validation
                HapticAvailability.NEEDS_PERMISSION -> R.string.waveform_status_permission
                HapticAvailability.NEEDS_ASSOCIATION -> R.string.waveform_status_association
                HapticAvailability.INITIALIZING -> R.string.waveform_status_initializing
                HapticAvailability.BUSY -> R.string.waveform_status_busy
                HapticAvailability.DISCONNECTED -> R.string.waveform_status_disconnected
                else -> R.string.waveform_status_failed
            }
            val status = game.getString(statusResource) +
                if (capability.evidence == HapticEvidence.EXPERIMENTAL_PROTOCOL)
                    " · " + game.getString(R.string.waveform_experimental) else ""
            val label = view.route.device.name + (view.player?.let {
                " · " + game.getString(R.string.waveform_player, it + 1)
            } ?: "")
            WaveformRouteCardState(view.route.id, label, status, view.canTest, view.testing)
        }

    private fun emitState() {
        onStateChanged?.invoke(state)
    }
}
