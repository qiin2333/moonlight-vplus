package com.limelight.gamemenu

import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import com.limelight.Game
import com.limelight.R
import com.limelight.nvstream.NvConnection
import java.util.Locale
import kotlin.math.roundToInt

internal data class BitrateCardState(
    val progress: Float,
    val currentBitrateKbps: Int,
    val abrStatus: String?,
    val hapticMode: BitrateCardController.HapticMode,
    val maxProgress: Int,
    val maxBitrateKbps: Int
) {
    val selectedBitrateKbps: Int
        get() = BitrateCardController.progressToBitrateKbps(progress.roundToInt(), maxProgress)
}

/** Owns bitrate card state and stream-side effects without depending on Android Views. */
internal class BitrateCardController(
    private val game: Game,
    private val conn: NvConnection,
    supportsExtendedBitrate: Boolean = false
) {
    enum class HapticMode {
        ALL,
        KEY_NODES,
        NONE;

        fun next(): HapticMode = entries[(ordinal + 1) % entries.size]

        fun label(context: Context): String = context.getString(
            when (this) {
                ALL -> R.string.bitrate_haptic_mode_all
                KEY_NODES -> R.string.bitrate_haptic_mode_key_nodes
                NONE -> R.string.bitrate_haptic_mode_none
            }
        )
    }

    companion object {
        const val MAX_PROGRESS = 59
        const val PYROWAVE_MAX_PROGRESS = 83
        private const val PREF_HAPTIC_MODE = "bitrate_seekbar_haptic_mode"
        private val SEGMENT_BOUNDARIES = setOf(0, 9, 24, 39, 49, MAX_PROGRESS)

        fun maxProgressFor(supportsExtendedBitrate: Boolean): Int {
            return if (supportsExtendedBitrate) PYROWAVE_MAX_PROGRESS else MAX_PROGRESS
        }

        fun getHapticMode(context: Context): HapticMode {
            val prefs = context.getSharedPreferences("game_menu_prefs", Context.MODE_PRIVATE)
            val ordinal = prefs.getInt(PREF_HAPTIC_MODE, HapticMode.KEY_NODES.ordinal)
            return HapticMode.entries.getOrElse(ordinal) { HapticMode.KEY_NODES }
        }

        fun setHapticMode(context: Context, mode: HapticMode) {
            context.getSharedPreferences("game_menu_prefs", Context.MODE_PRIVATE)
                .edit { putInt(PREF_HAPTIC_MODE, mode.ordinal) }
        }

        fun progressToBitrateKbps(progress: Int, maxProgress: Int = MAX_PROGRESS): Int {
            val boundedProgress = progress.coerceIn(0, maxProgress)
            return when {
                boundedProgress <= 9 -> 500 + boundedProgress * 500
                boundedProgress <= 24 -> 5000 + (boundedProgress - 9) * 1000
                boundedProgress <= 39 -> 20000 + (boundedProgress - 24) * 2000
                boundedProgress <= 49 -> 50000 + (boundedProgress - 39) * 5000
                boundedProgress <= MAX_PROGRESS -> 100000 + (boundedProgress - 49) * 10000
                else -> 200000 + (boundedProgress - MAX_PROGRESS) * 25000
            }
        }

        fun bitrateToProgress(kbps: Int, maxProgress: Int = MAX_PROGRESS): Int {
            val unboundedProgress = when {
                kbps <= 5000 -> (kbps - 500) / 500
                kbps <= 20000 -> 9 + (kbps - 5000 + 500) / 1000
                kbps <= 50000 -> 24 + (kbps - 20000 + 1000) / 2000
                kbps <= 100000 -> 39 + (kbps - 50000 + 2500) / 5000
                kbps <= 200000 -> 49 + (kbps - 100000 + 5000) / 10000
                else -> MAX_PROGRESS + (kbps - 200000 + 12500) / 25000
            }
            return unboundedProgress.coerceIn(0, maxProgress)
        }

        fun formatBitrateMbps(kbps: Int): String {
            return if (kbps % 1000 != 0) {
                String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
            } else {
                String.format(Locale.US, "%d Mbps", kbps / 1000)
            }
        }
    }

    private var abrListener: ((Int, String) -> Unit)? = null
    private var onStateChanged: ((BitrateCardState) -> Unit)? = null
    private var userTracking = false
    private var bitrateToast: Toast? = null
    private val maxProgress = maxProgressFor(supportsExtendedBitrate)
    private val maxBitrateKbps = progressToBitrateKbps(maxProgress, maxProgress)
    private var state = createState(conn.currentBitrate)

    fun snapshot(): BitrateCardState = state

    fun start(onStateChanged: (BitrateCardState) -> Unit) {
        dispose()
        this.onStateChanged = onStateChanged
        state = createState(conn.currentBitrate)
        emitState()

        val abrService = game.adaptiveBitrateService ?: return
        val listener: (Int, String) -> Unit = { kbps, _ ->
            game.runOnUiThread {
                if (!userTracking) {
                    state = state.copy(
                        progress = bitrateToProgress(kbps, maxProgress).toFloat(),
                        currentBitrateKbps = kbps,
                        abrStatus = abrService.getStatusText()
                    )
                    emitState()
                }
            }
        }
        abrListener = listener
        abrService.bitrateListener = listener
    }

    /** Returns whether this progress change should produce a haptic tick. */
    fun previewProgress(progress: Float): Boolean {
        val bounded = progress.coerceIn(0f, maxProgress.toFloat())
        val previousStep = state.progress.roundToInt()
        val currentStep = bounded.roundToInt()
        val changed = currentStep != previousStep
        userTracking = true
        state = state.copy(progress = bounded)
        emitState()
        val keyNodes = SEGMENT_BOUNDARIES + maxProgress
        return changed && when (state.hapticMode) {
            HapticMode.ALL -> true
            HapticMode.KEY_NODES -> currentStep in keyNodes
            HapticMode.NONE -> false
        }
    }

    fun applySelectedBitrate() {
        userTracking = false
        adjustBitrate(state.selectedBitrateKbps)
    }

    fun cycleHapticMode() {
        val mode = state.hapticMode.next()
        setHapticMode(game, mode)
        state = state.copy(hapticMode = mode)
        emitState()
        Toast.makeText(game, mode.label(game), Toast.LENGTH_SHORT).show()
    }

    fun dispose() {
        val listener = abrListener
        if (listener != null && game.adaptiveBitrateService?.bitrateListener === listener) {
            game.adaptiveBitrateService?.bitrateListener = null
        }
        abrListener = null
        onStateChanged = null
        userTracking = false
    }

    private fun createState(kbps: Int): BitrateCardState {
        val abrService = game.adaptiveBitrateService
        return BitrateCardState(
            progress = bitrateToProgress(kbps, maxProgress).toFloat(),
            currentBitrateKbps = kbps,
            abrStatus = abrService?.takeIf { it.enabled }?.getStatusText(),
            hapticMode = getHapticMode(game),
            maxProgress = maxProgress,
            maxBitrateKbps = maxBitrateKbps
        )
    }

    private fun emitState() {
        onStateChanged?.invoke(state)
    }

    private fun showBitrateToast(message: String) {
        bitrateToast?.cancel()
        bitrateToast = Toast.makeText(game, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun adjustBitrate(bitrateKbps: Int) {
        try {
            showBitrateToast(game.getString(R.string.toast_adjusting_bitrate))
            conn.setBitrate(bitrateKbps, object : NvConnection.BitrateAdjustmentCallback {
                override fun onSuccess(newBitrate: Int) {
                    game.runOnUiThread {
                        game.prefConfig.bitrate = newBitrate
                        game.adaptiveBitrateService?.notifyManualOverride(newBitrate)
                        state = state.copy(
                            progress = bitrateToProgress(newBitrate, maxProgress).toFloat(),
                            currentBitrateKbps = newBitrate,
                            abrStatus = game.adaptiveBitrateService?.takeIf { it.enabled }?.getStatusText()
                        )
                        emitState()
                        showBitrateToast(
                            game.getString(R.string.game_menu_bitrate_adjustment_success, newBitrate / 1000)
                        )
                    }
                }

                override fun onFailure(errorMessage: String) {
                    game.runOnUiThread {
                        val actualBitrate = conn.currentBitrate
                        state = state.copy(
                            progress = bitrateToProgress(actualBitrate, maxProgress).toFloat(),
                            currentBitrateKbps = actualBitrate
                        )
                        emitState()
                        showBitrateToast(
                            game.getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + errorMessage
                        )
                    }
                }
            })
        } catch (e: Exception) {
            game.runOnUiThread {
                showBitrateToast(
                    game.getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + e.message
                )
            }
        }
    }
}
