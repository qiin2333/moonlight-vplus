package com.limelight

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

import com.limelight.nvstream.NvConnection

/**
 * Encapsulates the bitrate adjustment card logic shown in the Game Menu dialog.
 */
class BitrateCardController(
    private val game: Game,
    private val conn: NvConnection
) {

    fun setup(customView: View, dialog: AlertDialog) {
        val bitrateContainer = customView.findViewById<View>(R.id.bitrateAdjustmentContainer)
        val bitrateSeekBar = customView.findViewById<SeekBar>(R.id.bitrateSeekBar)
        val currentBitrateText = customView.findViewById<TextView>(R.id.currentBitrateText)
        val bitrateValueText = customView.findViewById<TextView>(R.id.bitrateValueText)
        val bitrateTipIcon = customView.findViewById<ImageView>(R.id.bitrateTipIcon)

        if (bitrateContainer == null || bitrateSeekBar == null ||
            currentBitrateText == null || bitrateValueText == null || bitrateTipIcon == null
        ) {
            return
        }

        val currentBitrate = conn.currentBitrate
        val currentBitrateMbps = currentBitrate / 1000

        currentBitrateText.text = String.format(
            game.resources.getString(R.string.game_menu_bitrate_current), currentBitrateMbps
        )

        // Configure seekbar range: 500 kbps .. 200000 kbps (step 100)
        bitrateSeekBar.max = 1995
        bitrateSeekBar.progress = (currentBitrate - 500) / 100

        bitrateValueText.text = String.format("%d Mbps", currentBitrateMbps)

        bitrateTipIcon.setOnClickListener {
            AlertDialog.Builder(game, R.style.AppDialogStyle)
                .setMessage(game.resources.getString(R.string.game_menu_bitrate_tip))
                .setPositiveButton("懂了", null)
                .show()
        }

        // Debounced apply
        val bitrateHandler = Handler(Looper.getMainLooper())
        val bitrateApplyRunnable = Runnable {
            val newBitrate = bitrateSeekBar.progress * 100 + 500
            adjustBitrate(newBitrate)
        }

        bitrateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newBitrate = progress * 100 + 500
                    val newBitrateMbps = newBitrate / 1000
                    bitrateValueText.text = String.format("%d Mbps", newBitrateMbps)

                    bitrateHandler.removeCallbacks(bitrateApplyRunnable)
                    bitrateHandler.postDelayed(bitrateApplyRunnable, 500)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                bitrateHandler.removeCallbacks(bitrateApplyRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                bitrateHandler.removeCallbacks(bitrateApplyRunnable)
                val newBitrate = seekBar.progress * 100 + 500
                adjustBitrate(newBitrate)
            }
        })

        bitrateSeekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    bitrateHandler.removeCallbacks(bitrateApplyRunnable)
                    bitrateHandler.postDelayed(bitrateApplyRunnable, 300)
                    return@setOnKeyListener false
                }
            }
            false
        }
    }

    private fun adjustBitrate(bitrateKbps: Int) {
        try {
            Toast.makeText(game, "正在调整码率...", Toast.LENGTH_SHORT).show()

            conn.setBitrate(bitrateKbps, object : NvConnection.BitrateAdjustmentCallback {
                override fun onSuccess(newBitrate: Int) {
                    game.runOnUiThread {
                        try {
                            // Update prefConfig with the new bitrate so it gets saved when streaming ends
                            game.prefConfig.bitrate = newBitrate

                            val successMessage = String.format(
                                game.resources.getString(R.string.game_menu_bitrate_adjustment_success),
                                newBitrate / 1000
                            )
                            Toast.makeText(game, successMessage, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            LimeLog.warning("Failed to show success toast: ${e.message}")
                        }
                    }
                }

                override fun onFailure(errorMessage: String) {
                    game.runOnUiThread {
                        try {
                            val errorMsg = game.resources.getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + errorMessage
                            Toast.makeText(game, errorMsg, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            LimeLog.warning("Failed to show error toast: ${e.message}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            game.runOnUiThread {
                try {
                    Toast.makeText(
                        game,
                        game.resources.getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + e.message,
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (toastException: Exception) {
                    LimeLog.warning("Failed to show error toast: ${toastException.message}")
                }
            }
        }
    }
}
