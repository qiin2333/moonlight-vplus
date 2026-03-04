package com.limelight

import android.app.AlertDialog
import android.view.KeyEvent
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

import com.limelight.binding.input.ControllerHandler

/**
 * Encapsulates the gyro-to-right-stick control card logic.
 */
class GyroCardController(private val game: Game) {

    fun setup(customView: View, dialog: AlertDialog) {
        val container = customView.findViewById<View>(R.id.gyroAdjustmentContainer) ?: return

        val statusText = customView.findViewById<TextView>(R.id.gyroStatusText)
        val toggleSwitch = customView.findViewById<CompoundButton>(R.id.gyroToggleSwitch)
        val activationKeyContainer = customView.findViewById<View>(R.id.gyroActivationKeyContainer)
        val activationKeyText = customView.findViewById<TextView>(R.id.gyroActivationKeyText)
        val sensSeek = customView.findViewById<SeekBar>(R.id.gyroSensitivitySeekBar)
        val sensVal = customView.findViewById<TextView>(R.id.gyroSensitivityValueText)
        val invertXSwitch = customView.findViewById<CompoundButton>(R.id.gyroInvertXSwitch)
        val invertYSwitch = customView.findViewById<CompoundButton>(R.id.gyroInvertYSwitch)

        statusText?.text = if (game.prefConfig.gyroToRightStick) "ON" else "OFF"

        toggleSwitch?.apply {
            isChecked = game.prefConfig.gyroToRightStick
            setOnCheckedChangeListener { buttonView, isChecked ->
                val ch = game.controllerHandler
                if (ch == null) {
                    Toast.makeText(game, "Failed to access controller", Toast.LENGTH_SHORT).show()
                    buttonView.isChecked = !isChecked
                    return@setOnCheckedChangeListener
                }
                ch.setGyroToRightStickEnabled(isChecked)
                statusText?.text = if (isChecked) "ON" else "OFF"
            }
        }

        // 更新显示
        activationKeyText?.let { updateActivationKeyText(it) }
        activationKeyContainer?.setOnClickListener { showActivationKeyDialog(activationKeyText) }

        if (sensSeek != null && sensVal != null) {
            // 改为"灵敏度倍数"，越高越快。映射范围：0.5x .. 3.0x（步进 0.1）
            val max = 25 // 0..25 -> +0..2.5  => 0.5..3.0
            sensSeek.max = max
            // 反推当前 multiplier 到 progress
            val mult = (if (game.prefConfig.gyroSensitivityMultiplier > 0)
                game.prefConfig.gyroSensitivityMultiplier else 1.0f).coerceIn(0.5f, 3.0f)
            val progress = Math.round((mult - 0.5f) / 0.1f)
            sensSeek.progress = progress
            sensVal.text = String.format("%.1fx", mult)

            sensSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                    val m = 0.5f + p * 0.1f
                    game.prefConfig.gyroSensitivityMultiplier = m
                    sensVal.text = String.format("%.1fx", m)
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
