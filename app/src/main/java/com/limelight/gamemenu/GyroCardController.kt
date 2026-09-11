package com.limelight.gamemenu

import android.app.AlertDialog
import android.view.KeyEvent
import android.widget.Toast
import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.GyroAssistantMode
import com.limelight.utils.AppDialogStyler

internal data class GyroCardState(
    val enabled: Boolean,
    val mouseMode: Boolean,
    val activationKeyLabel: String,
    val sensitivity: Float,
    val invertX: Boolean,
    val invertY: Boolean
)

/** Owns gyroscope preferences and controller-side effects without depending on Android Views. */
internal class GyroCardController(private val game: Game) {
    private var onStateChanged: ((GyroCardState) -> Unit)? = null
    private var state = readState()

    fun snapshot(): GyroCardState = state

    fun start(onStateChanged: (GyroCardState) -> Unit) {
        this.onStateChanged = onStateChanged
        state = readState()
        emitState()
    }

    fun setEnabled(enabled: Boolean) {
        val mode = when {
            !enabled -> GyroAssistantMode.OFF
            state.mouseMode -> GyroAssistantMode.MOUSE
            else -> GyroAssistantMode.RIGHT_STICK
        }
        game.controllerHandler.setGyroAssistantMode(mode)
        state = state.copy(enabled = enabled)
        game.prefConfig.writePreferences(game)
        emitState()
    }

    fun setMouseMode(enabled: Boolean) {
        if (!state.enabled) {
            state = state.copy(mouseMode = enabled)
            emitState()
            return
        }

        val handler = game.controllerHandler
        when {
            enabled -> {
                handler.setGyroAssistantMode(GyroAssistantMode.MOUSE)
                state = state.copy(mouseMode = true)
            }
            handler.hasAnyController() -> {
                handler.setGyroAssistantMode(GyroAssistantMode.RIGHT_STICK)
                state = state.copy(mouseMode = false)
            }
            else -> {
                handler.setGyroAssistantMode(GyroAssistantMode.OFF)
                state = state.copy(enabled = false, mouseMode = false)
                Toast.makeText(game, game.getString(R.string.gyro_no_controller_detected), Toast.LENGTH_SHORT).show()
            }
        }
        game.prefConfig.writePreferences(game)
        emitState()
    }

    fun showActivationKeyDialog(onShown: (AlertDialog) -> Unit = {}) {
        val items = arrayOf<CharSequence>(
            game.getString(R.string.gyro_activation_always),
            game.getString(R.string.gyro_activation_left_trigger),
            game.getString(R.string.gyro_activation_right_trigger)
        )
        val checked = when (game.prefConfig.gyroActivationKeyCode) {
            ControllerHandler.GYRO_ACTIVATION_ALWAYS -> 0
            KeyEvent.KEYCODE_BUTTON_R2 -> 2
            else -> 1
        }
        val dialog = AlertDialog.Builder(game, R.style.AppDialogStyle)
            .setTitle(R.string.gyro_activation_method)
            .setSingleChoiceItems(items, checked) { choiceDialog, which ->
                game.prefConfig.gyroActivationKeyCode = when (which) {
                    0 -> ControllerHandler.GYRO_ACTIVATION_ALWAYS
                    1 -> KeyEvent.KEYCODE_BUTTON_L2
                    else -> KeyEvent.KEYCODE_BUTTON_R2
                }
                game.prefConfig.writePreferences(game)
                state = state.copy(activationKeyLabel = items[which].toString())
                emitState()
                choiceDialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_button_cancel, null)
            .create()
        dialog.show()
        AppDialogStyler.applySystemChoiceList(dialog, game)
        onShown(dialog)
    }

    fun previewSensitivity(sensitivity: Float) {
        val bounded = sensitivity.coerceIn(0.5f, 10.0f)
        game.prefConfig.gyroSensitivityMultiplier = bounded
        state = state.copy(sensitivity = bounded)
        emitState()
    }

    fun persistSensitivity() {
        game.prefConfig.writePreferences(game)
    }

    fun setInvertX(enabled: Boolean) {
        game.prefConfig.gyroInvertXAxis = enabled
        game.prefConfig.writePreferences(game)
        state = state.copy(invertX = enabled)
        emitState()
    }

    fun setInvertY(enabled: Boolean) {
        game.prefConfig.gyroInvertYAxis = enabled
        game.prefConfig.writePreferences(game)
        state = state.copy(invertY = enabled)
        emitState()
    }

    fun dispose() {
        onStateChanged = null
    }

    private fun readState(): GyroCardState {
        val prefs = game.prefConfig
        val mode = GyroAssistantMode.from(prefs)
        return GyroCardState(
            enabled = mode != GyroAssistantMode.OFF,
            mouseMode = mode == GyroAssistantMode.MOUSE,
            activationKeyLabel = when (prefs.gyroActivationKeyCode) {
                ControllerHandler.GYRO_ACTIVATION_ALWAYS -> game.getString(R.string.gyro_activation_always)
                KeyEvent.KEYCODE_BUTTON_R2 -> game.getString(R.string.gyro_activation_right_trigger)
                else -> game.getString(R.string.gyro_activation_left_trigger)
            },
            sensitivity = (if (prefs.gyroSensitivityMultiplier > 0) {
                prefs.gyroSensitivityMultiplier
            } else {
                1.0f
            }).coerceIn(0.5f, 10.0f),
            invertX = prefs.gyroInvertXAxis,
            invertY = prefs.gyroInvertYAxis
        )
    }

    private fun emitState() {
        onStateChanged?.invoke(state)
    }
}
