package com.limelight

import android.app.AlertDialog
import android.view.KeyEvent
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

import com.limelight.binding.input.ControllerHandler

/**
 * Encapsulates the gyro control card logic (right-stick mode and mouse mode).
 */
class GyroCardController(private val game: Game) {

    fun setup(customView: View, dialog: AlertDialog) {
        val container = customView.findViewById<View>(R.id.gyroAdjustmentContainer) ?: return

        val statusText = customView.findViewById<TextView>(R.id.gyroStatusText)
        val toggleSwitch = customView.findViewById<CompoundButton>(R.id.gyroToggleSwitch)
        val mouseModeSwitch = customView.findViewById<CompoundButton>(R.id.gyroMouseModeSwitch)
        val activationKeyContainer = customView.findViewById<View>(R.id.gyroActivationKeyContainer)
        val activationKeyText = customView.findViewById<TextView>(R.id.gyroActivationKeyText)
        val sensSeek = customView.findViewById<SeekBar>(R.id.gyroSensitivitySeekBar)
        val sensVal = customView.findViewById<TextView>(R.id.gyroSensitivityValueText)
        val invertXSwitch = customView.findViewById<CompoundButton>(R.id.gyroInvertXSwitch)
        val invertYSwitch = customView.findViewById<CompoundButton>(R.id.gyroInvertYSwitch)

        val isAnyGyroOn = game.prefConfig.gyroToRightStick || game.prefConfig.gyroToMouse
        statusText?.text = if (isAnyGyroOn) "ON" else "OFF"
        mouseModeSwitch?.isChecked = game.prefConfig.gyroToMouse

        toggleSwitch?.apply {
            isChecked = isAnyGyroOn
            setOnCheckedChangeListener { buttonView, isChecked ->
                val ch = game.controllerHandler
                val mouseMode = mouseModeSwitch?.isChecked ?: false
                if (isChecked) {
                    if (mouseMode) ch.setGyroToMouseEnabled(true)
                    else ch.setGyroToRightStickEnabled(true)
                } else {
                    ch.setGyroToRightStickEnabled(false)
                    ch.setGyroToMouseEnabled(false)
                }
                statusText?.text = if (isChecked) "ON" else "OFF"
            }
        }

        mouseModeSwitch?.setOnCheckedChangeListener { buttonView, isChecked ->
            val gyroOn = toggleSwitch?.isChecked ?: false
            if (!gyroOn) return@setOnCheckedChangeListener

            val ch = game.controllerHandler

            if (isChecked) {
                ch.setGyroToMouseEnabled(true)
            } else {
                // 关闭鼠标模式时，检查是否有物理手柄或虚拟手柄
                // 只有在有手柄的情况下才启用手柄模式，否则完全关闭陀螺仪
                val hasController = ch.hasAnyController()
                if (hasController) {
                    ch.setGyroToRightStickEnabled(true)
                } else {
                    // 没有手柄，完全关闭陀螺仪
                    ch.setGyroToRightStickEnabled(false)
                    ch.setGyroToMouseEnabled(false)
                    toggleSwitch?.isChecked = false
                    statusText?.text = "OFF"
                    Toast.makeText(game, game.getString(R.string.gyro_no_controller_detected), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 更新显示
        activationKeyText?.let { updateActivationKeyText(it) }
        activationKeyContainer?.setOnClickListener { showActivationKeyDialog(activationKeyText) }

        if (sensSeek != null && sensVal != null) {
            // 灵敏度倍数，范围：0.5x .. 3.0x（步进 0.1）
            val max = 25
            sensSeek.max = max
            val mult = (if (game.prefConfig.gyroSensitivityMultiplier > 0)
                game.prefConfig.gyroSensitivityMultiplier else 1.0f).coerceIn(0.5f, 3.0f)
            val progress = Math.round((mult - 0.5f) / 0.1f)
            sensSeek.progress = progress
            sensVal.text = String.format(Locale.US, "%.1fx", mult)

            sensSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                    val m = 0.5f + p * 0.1f
                    game.prefConfig.gyroSensitivityMultiplier = m
                    sensVal.text = String.format(Locale.US, "%.1fx", m)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    game.prefConfig.writePreferences(game)
                }
            })
        }

        invertXSwitch?.apply {
            isChecked = game.prefConfig.gyroInvertXAxis
            setOnCheckedChangeListener { _, isChecked ->
                game.prefConfig.gyroInvertXAxis = isChecked
                game.prefConfig.writePreferences(game)
            }
        }

        invertYSwitch?.apply {
            isChecked = game.prefConfig.gyroInvertYAxis
            setOnCheckedChangeListener { _, isChecked ->
                game.prefConfig.gyroInvertYAxis = isChecked
                game.prefConfig.writePreferences(game)
            }
        }
    }

    private fun showActivationKeyDialog(activationKeyText: TextView?) {
        val items = arrayOf<CharSequence>(
            game.getString(R.string.gyro_activation_always),
            "LT (L2)",
            "RT (R2)"
        )
        val checked = when (game.prefConfig.gyroActivationKeyCode) {
            ControllerHandler.GYRO_ACTIVATION_ALWAYS -> 0
            KeyEvent.KEYCODE_BUTTON_R2 -> 2
            else -> 1
        }
        AlertDialog.Builder(game, R.style.AppDialogStyle)
            .setTitle(R.string.gyro_activation_method)
            .setSingleChoiceItems(items, checked) { d, which ->
                game.prefConfig.gyroActivationKeyCode = when (which) {
                    0 -> ControllerHandler.GYRO_ACTIVATION_ALWAYS
                    1 -> KeyEvent.KEYCODE_BUTTON_L2
                    else -> KeyEvent.KEYCODE_BUTTON_R2
                }
                game.prefConfig.writePreferences(game)
                activationKeyText?.text = items[which]
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_button_cancel, null)
            .show()
    }

    private fun updateActivationKeyText(activationKeyText: TextView) {
        val label = when (game.prefConfig.gyroActivationKeyCode) {
            ControllerHandler.GYRO_ACTIVATION_ALWAYS -> game.getString(R.string.gyro_activation_always)
            KeyEvent.KEYCODE_BUTTON_R2 -> "RT (R2)"
            else -> "LT (L2)"
        }
        activationKeyText.text = label
    }
}
