package com.limelight.gamemenu

import android.hardware.usb.UsbManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.limelight.Game
import com.limelight.binding.input.driver.UsbWaveformBackends
import com.limelight.binding.input.haptics.GameRumbleMode
import com.limelight.nvstream.HostGamepadSelection
import com.limelight.preferences.SensaStrengthPreferences

internal data class SensaHapticsCardState(
    val requestedEnabled: Boolean = false,
    val appliedEnabled: Boolean? = null,
    val mode: String = SensaStrengthPreferences.HAPTIC_OR_RUMBLE,
    val strength: Float = 100f,
    val frequency: Float = 100f,
    val authoredPcmRequested: Boolean = false,
    val hostGamepad: HostGamepadSelection = HostGamepadSelection.AUTOMATIC,
    val controllerOutputEnabled: Boolean = true,
    val waveformControllerPresent: Boolean = false
) {
    val pending: Boolean get() = appliedEnabled == null || appliedEnabled != requestedEnabled
    // Reconnecting only helps if the next launch can actually request PCM.
    val pendingRestart: Boolean get() = requestedEnabled &&
        mode != SensaStrengthPreferences.RUMBLE_ONLY && !authoredPcmRequested &&
        hostGamepad.requestsAuthoredPcm(waveformControllerPresent, controllerOutputEnabled)
}

internal interface SensaHapticsCardAccess {
    fun readState(): SensaHapticsCardState
    fun setEnabled(enabled: Boolean)
    fun setMode(mode: String)
    fun setStrength(value: Float)
    fun setFrequency(value: Float)
}

internal interface SensaHapticsCardScheduler {
    fun postDelayed(action: Runnable, delayMs: Long)
    fun remove(action: Runnable)
}

/** Owns the state while the menu is open; hidden cards do not poll the service. */
internal class SensaHapticsCardController(
    private val access: SensaHapticsCardAccess,
    private val scheduler: SensaHapticsCardScheduler
) {
    constructor(game: Game) : this(GameSensaHapticsCardAccess(game), HandlerSensaHapticsCardScheduler())

    private var listener: ((SensaHapticsCardState) -> Unit)? = null
    private var visible = false
    private var state = access.readState()
    private val poll = Runnable { refreshAndSchedule() }

    fun snapshot(): SensaHapticsCardState = state

    fun start(visible: Boolean = true, onStateChanged: (SensaHapticsCardState) -> Unit) {
        scheduler.remove(poll)
        this.visible = visible
        listener = onStateChanged
        state = access.readState()
        listener?.invoke(state)
        schedule()
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        scheduler.remove(poll)
        if (visible && listener != null) refreshAndSchedule()
    }

    fun dispose() {
        scheduler.remove(poll)
        listener = null
        visible = false
    }

    fun setEnabled(enabled: Boolean) {
        access.setEnabled(enabled)
        refreshAndSchedule()
    }

    fun setMode(mode: String) {
        access.setMode(mode)
        refreshAndSchedule()
    }

    fun setStrength(value: Float) {
        access.setStrength(value)
        refreshAndSchedule()
    }

    fun setFrequency(value: Float) {
        access.setFrequency(value)
        refreshAndSchedule()
    }

    private fun refreshAndSchedule() {
        scheduler.remove(poll)
        if (listener == null) return
        val next = access.readState()
        if (next != state) {
            state = next
            listener?.invoke(state)
        }
        schedule()
    }

    private fun schedule() {
        if (visible && listener != null) {
            scheduler.postDelayed(poll, if (state.pending) 100 else 1000)
        }
    }
}

private class HandlerSensaHapticsCardScheduler : SensaHapticsCardScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun postDelayed(action: Runnable, delayMs: Long) { handler.postDelayed(action, delayMs) }
    override fun remove(action: Runnable) { handler.removeCallbacks(action) }
}

private class GameSensaHapticsCardAccess(private val game: Game) : SensaHapticsCardAccess {
    override fun setEnabled(enabled: Boolean) = game.setSensaHapticsEnabled(enabled)

    override fun setMode(mode: String) {
        SensaStrengthPreferences.setMode(game, mode)
        game.controllerHandler.refreshHapticRumbleSettings()
    }

    override fun setStrength(value: Float) {
        SensaStrengthPreferences.setStrength(game, value.toInt())
        game.controllerHandler.previewHapticTuning()
    }

    override fun setFrequency(value: Float) {
        SensaStrengthPreferences.setFrequency(game, value.toInt())
        game.controllerHandler.previewHapticTuning()
    }

    override fun readState(): SensaHapticsCardState {
        val enabled = SensaStrengthPreferences.enabled(game)
        return SensaHapticsCardState(
            requestedEnabled = enabled,
            appliedEnabled = game.appliedSensaHaptics(),
            mode = SensaStrengthPreferences.mode(game),
            strength = (SensaStrengthPreferences.read(game) * 100).toFloat(),
            frequency = SensaStrengthPreferences.frequency(game).toFloat(),
            authoredPcmRequested = game.authoredPcmHapticsRequested,
            hostGamepad = game.prefConfig.hostGamepadSelection,
            controllerOutputEnabled = game.prefConfig.gameRumbleMode != GameRumbleMode.DEVICE,
            waveformControllerPresent = UsbWaveformBackends.hasEligibleController(
                game.getSystemService(Context.USB_SERVICE) as? UsbManager,
                game.prefConfig.allowExperimentalHaptics, sensaEnabled = enabled)
        )
    }
}
