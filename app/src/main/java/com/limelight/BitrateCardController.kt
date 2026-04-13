package com.limelight

import android.app.AlertDialog
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

import com.limelight.nvstream.NvConnection

/**
 * Encapsulates the bitrate adjustment card logic shown in the Game Menu dialog.
 *
 * Segmented seekbar mapping (60 positions total):
 *   progress  0..9  → 0.5~5 Mbps,   step 0.5 Mbps (500~5000 kbps,      step 500)
 *   progress 10..24 → 6~20 Mbps,    step 1 Mbps   (6000~20000 kbps,    step 1000)
 *   progress 25..39 → 22~50 Mbps,   step 2 Mbps   (22000~50000 kbps,   step 2000)
 *   progress 40..49 → 55~100 Mbps,  step 5 Mbps   (55000~100000 kbps,  step 5000)
 *   progress 50..59 → 110~200 Mbps, step 10 Mbps  (110000~200000 kbps, step 10000)
 */
class BitrateCardController(
    private val game: Game,
    private val conn: NvConnection
) {

    companion object {
        private const val MAX_PROGRESS = 59

        /** Segment boundary positions where haptic feedback should fire: 0.5, 5, 50, 100, 200 Mbps. */
        private val SEGMENT_BOUNDARIES = setOf(0, 9, 24, 39, 49, MAX_PROGRESS)

        /** Convert seekbar progress (0..59) to bitrate in kbps. */
        fun progressToBitrateKbps(progress: Int): Int {
            return when {
                progress <= 9  -> 500 + progress * 500             // 500..5000
                progress <= 24 -> 5000 + (progress - 9) * 1000     // 6000..20000
                progress <= 39 -> 20000 + (progress - 24) * 2000   // 22000..50000
                progress <= 49 -> 50000 + (progress - 39) * 5000   // 55000..100000
                else           -> 100000 + (progress - 49) * 10000 // 110000..200000
            }
        }

        /** Convert bitrate in kbps to the nearest seekbar progress (0..59). */
        fun bitrateToProgress(kbps: Int): Int {
            return when {
                kbps <= 5000   -> ((kbps - 500) / 500).coerceIn(0, 9)
                kbps <= 20000  -> (9 + (kbps - 5000 + 500) / 1000).coerceIn(10, 24)
                kbps <= 50000  -> (24 + (kbps - 20000 + 1000) / 2000).coerceIn(25, 39)
                kbps <= 100000 -> (39 + (kbps - 50000 + 2500) / 5000).coerceIn(40, 49)
                else           -> (49 + (kbps - 100000 + 5000) / 10000).coerceIn(50, MAX_PROGRESS)
            }
        }

        /** Format bitrate kbps to a human-readable Mbps string. */
        fun formatBitrateMbps(kbps: Int): String {
            return if (kbps % 1000 != 0) {
                String.format("%.1f Mbps", kbps / 1000.0)
            } else {
                String.format("%d Mbps", kbps / 1000)
            }
        }
    }

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

        currentBitrateText.text = String.format(
            game.resources.getString(R.string.game_menu_bitrate_current), currentBitrate / 1000
        )

        // Configure segmented seekbar: 45 positions mapping to 0.5~200 Mbps
        bitrateSeekBar.max = MAX_PROGRESS
        bitrateSeekBar.progress = bitrateToProgress(currentBitrate)

        bitrateValueText.text = formatBitrateMbps(progressToBitrateKbps(bitrateSeekBar.progress))

        bitrateTipIcon.setOnClickListener {
            AlertDialog.Builder(game, R.style.AppDialogStyle)
                .setMessage(game.resources.getString(R.string.game_menu_bitrate_tip))
                .setPositiveButton("懂了", null)
                .show()
        }

        // Apply only on release (touch up or key debounce)
        val bitrateHandler = Handler(Looper.getMainLooper())
        val bitrateApplyRunnable = Runnable {
            val newBitrate = progressToBitrateKbps(bitrateSeekBar.progress)
            adjustBitrate(newBitrate, currentBitrateText)
        }

        bitrateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var lastProgress = bitrateSeekBar.progress

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newBitrate = progressToBitrateKbps(progress)
                    bitrateValueText.text = formatBitrateMbps(newBitrate)

                    // Haptic feedback at segment boundaries
                    if (progress != lastProgress && progress in SEGMENT_BOUNDARIES) {
                        performHapticFeedback(seekBar)
                    }
                    lastProgress = progress
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val newBitrate = progressToBitrateKbps(seekBar.progress)
                adjustBitrate(newBitrate, currentBitrateText)
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

    private fun performHapticFeedback(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun adjustBitrate(bitrateKbps: Int, currentBitrateText: TextView? = null) {
        try {
            Toast.makeText(game, "正在调整码率...", Toast.LENGTH_SHORT).show()

            conn.setBitrate(bitrateKbps, object : NvConnection.BitrateAdjustmentCallback {
                override fun onSuccess(newBitrate: Int) {
                    game.runOnUiThread {
                        try {
                            // Update prefConfig with the new bitrate so it gets saved when streaming ends
                            game.prefConfig.bitrate = newBitrate

                            // Update the "current bitrate" label in the dialog
                            currentBitrateText?.text = String.format(
                                game.resources.getString(R.string.game_menu_bitrate_current),
                                newBitrate / 1000
                            )

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
                            // Revert display to actual current bitrate
                            val actualBitrate = conn.currentBitrate
                            currentBitrateText?.text = String.format(
                                game.resources.getString(R.string.game_menu_bitrate_current),
                                actualBitrate / 1000
                            )

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
