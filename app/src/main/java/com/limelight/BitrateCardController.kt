package com.limelight

import android.app.AlertDialog
import android.content.Context
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

    /** Haptic feedback mode for the bitrate seekbar. */
    enum class HapticMode(val label: String) {
        ALL("震动：每档"),
        KEY_NODES("震动：关键节点"),
        NONE("震动：关闭");

        fun next(): HapticMode = entries[(ordinal + 1) % entries.size]
    }

    companion object {
        private const val MAX_PROGRESS = 59
        private const val PREF_HAPTIC_MODE = "bitrate_seekbar_haptic_mode"

        /** Key-node boundary positions: 0.5, 5, 50, 100, 200 Mbps. */
        private val SEGMENT_BOUNDARIES = setOf(0, 9, 24, 39, 49, MAX_PROGRESS)

        fun getHapticMode(context: Context): HapticMode {
            val prefs = context.getSharedPreferences("game_menu_prefs", Context.MODE_PRIVATE)
            val ordinal = prefs.getInt(PREF_HAPTIC_MODE, HapticMode.KEY_NODES.ordinal)
            return HapticMode.entries.getOrElse(ordinal) { HapticMode.KEY_NODES }
        }

        fun setHapticMode(context: Context, mode: HapticMode) {
            context.getSharedPreferences("game_menu_prefs", Context.MODE_PRIVATE)
                .edit().putInt(PREF_HAPTIC_MODE, mode.ordinal).apply()
        }

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

        // 应用 ABR 状态显示（如启用）
        val abrService = game.adaptiveBitrateService
        fun renderCurrentText(kbps: Int, abrTag: String? = null) {
            val base = String.format(
                game.resources.getString(R.string.game_menu_bitrate_current), kbps / 1000
            )
            currentBitrateText.text = if (abrTag.isNullOrEmpty()) base else "$base   $abrTag"
        }
        renderCurrentText(currentBitrate, abrService?.takeIf { it.enabled }?.getStatusText())

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

        // Long-press tip icon → cycle haptic mode
        var currentHapticMode = getHapticMode(game)
        bitrateTipIcon.setOnLongClickListener {
            currentHapticMode = currentHapticMode.next()
            setHapticMode(game, currentHapticMode)
            Toast.makeText(game, currentHapticMode.label, Toast.LENGTH_SHORT).show()
            true
        }

        // Apply only on release (touch up or key debounce)
        val bitrateHandler = Handler(Looper.getMainLooper())
        val bitrateApplyRunnable = Runnable {
            val newBitrate = progressToBitrateKbps(bitrateSeekBar.progress)
            adjustBitrate(newBitrate, currentBitrateText)
            userTracking = false
        }

        bitrateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var lastProgress = bitrateSeekBar.progress

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newBitrate = progressToBitrateKbps(progress)
                    bitrateValueText.text = formatBitrateMbps(newBitrate)

                    // Haptic feedback based on mode
                    if (progress != lastProgress) {
                        val shouldVibrate = when (currentHapticMode) {
                            HapticMode.ALL -> true
                            HapticMode.KEY_NODES -> progress in SEGMENT_BOUNDARIES
                            HapticMode.NONE -> false
                        }
                        if (shouldVibrate) {
                            performHapticFeedback(seekBar)
                        }
                    }
                    lastProgress = progress
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userTracking = false
                val newBitrate = progressToBitrateKbps(seekBar.progress)
                adjustBitrate(newBitrate, currentBitrateText)
            }
        })

        bitrateSeekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    userTracking = true
                    bitrateHandler.removeCallbacks(bitrateApplyRunnable)
                    bitrateHandler.postDelayed(bitrateApplyRunnable, 300)
                    return@setOnKeyListener false
                }
            }
            false
        }

        // 订阅 ABR 码率变更，同步更新滑块与文本
        if (abrService != null) {
            val listener: (Int, String) -> Unit = { kbps, _ ->
                game.runOnUiThread {
                    if (userTracking) return@runOnUiThread // 用户拖动中不抢占 UI
                    bitrateSeekBar.progress = bitrateToProgress(kbps)
                    bitrateValueText.text = formatBitrateMbps(kbps)
                    renderCurrentText(kbps, abrService.getStatusText())
                }
            }
            abrService.bitrateListener = listener
            dialog.setOnDismissListener {
                if (abrService.bitrateListener === listener) {
                    abrService.bitrateListener = null
                }
            }
        }
    }

    private fun performHapticFeedback(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Reusable toast reference to dismiss previous one before showing new text. */
    private var bitrateToast: Toast? = null

    /** 用户当前是否在拖动滑块（含触摸 + DPad 按下连续操作的间隙）。*/
    private var userTracking: Boolean = false

    private fun showBitrateToast(message: String) {
        bitrateToast?.cancel()
        bitrateToast = Toast.makeText(game, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun adjustBitrate(bitrateKbps: Int, currentBitrateText: TextView? = null) {
        try {
            showBitrateToast("正在调整码率...")

            conn.setBitrate(bitrateKbps, object : NvConnection.BitrateAdjustmentCallback {
                override fun onSuccess(newBitrate: Int) {
                    game.runOnUiThread {
                        try {
                            // Update prefConfig with the new bitrate so it gets saved when streaming ends
                            game.prefConfig.bitrate = newBitrate

                            // 同步 ABR 基准（避免 ABR 仍按旧码率决策）
                            game.adaptiveBitrateService?.notifyManualOverride(newBitrate)

                            // Update the "current bitrate" label in the dialog
                            currentBitrateText?.text = String.format(
                                game.resources.getString(R.string.game_menu_bitrate_current),
                                newBitrate / 1000
                            )

                            val successMessage = String.format(
                                game.resources.getString(R.string.game_menu_bitrate_adjustment_success),
                                newBitrate / 1000
                            )
                            showBitrateToast(successMessage)
                        } catch (e: Exception) {
                            LimeLog.warning("Failed to show success toast: ${e.message}")
                        }
                    }
                }

                override fun onFailure(errorMessage: String) {
                    game.runOnUiThread {
                        try {
                            val actualBitrate = conn.currentBitrate
                            currentBitrateText?.text = String.format(
                                game.resources.getString(R.string.game_menu_bitrate_current),
                                actualBitrate / 1000
                            )

                            val errorMsg = game.resources.getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + errorMessage
                            showBitrateToast(errorMsg)
                        } catch (e: Exception) {
                            LimeLog.warning("Failed to show error toast: ${e.message}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            game.runOnUiThread {
                try {
                    showBitrateToast(
                        game.resources.getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + e.message
                    )
                } catch (toastException: Exception) {
                    LimeLog.warning("Failed to show error toast: ${toastException.message}")
                }
            }
        }
    }
}
