package com.limelight.gamemenu

import android.content.Context
import android.util.DisplayMetrics
import android.view.Display
import android.hardware.display.DisplayManager
import android.widget.Toast
import androidx.core.content.edit
import com.limelight.Game
import com.limelight.R
import com.limelight.nvstream.NvConnection
import com.limelight.preferences.CustomFrameRatesStore
import com.limelight.preferences.CustomResolutionsStore
import com.limelight.preferences.PreferenceConfiguration
import java.util.Locale
import kotlin.math.roundToInt

internal data class DisplayChoice(
    val value: String,
    val label: String,
    val selected: Boolean,
    val description: String? = null,
    val opensEditor: Boolean = false
)

internal data class DisplaySettingsDraft(
    val resolution: String,
    val frameRate: String,
    val screenMode: String,
    val customFrameRate: String = ""
) {
    fun changedFrom(current: DisplaySettingsDraft): Boolean {
        val typedFrameRate = customFrameRate.toIntOrNull()
        val frameRatePending = typedFrameRate != null &&
            typedFrameRate > 0 &&
            typedFrameRate.toString() != current.frameRate
        return resolution != current.resolution ||
            frameRate != current.frameRate ||
            frameRatePending ||
            screenMode != current.screenMode
    }
}

internal data class BitrateCardState(
    val appliedDisplay: DisplaySettingsDraft = DisplaySettingsDraft("", "", ""),
    val progress: Float,
    val currentBitrateKbps: Int,
    val abrStatus: String?,
    val adaptiveBitrate: Boolean,
    val abrMode: String,
    val resolutionLabel: String,
    val fpsLabel: String,
    val screenModeLabel: String,
    val resolutions: List<DisplayChoice>,
    val frameRates: List<DisplayChoice>,
    val screenModes: List<DisplayChoice>,
    val hapticMode: BitrateCardController.HapticMode
) {
    val selectedBitrateKbps: Int
        get() = BitrateCardController.progressToBitrateKbps(progress.roundToInt())
}

/** Owns bitrate card state and stream-side effects without depending on Android Views. */
internal class BitrateCardController(
    private val game: Game,
    private val conn: NvConnection
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
        val ABR_MODES = listOf("quality", "balanced", "lowLatency")
        private const val PREF_HAPTIC_MODE = "bitrate_seekbar_haptic_mode"
        private val SEGMENT_BOUNDARIES = setOf(0, 9, 24, 39, 49, MAX_PROGRESS)

        fun getHapticMode(context: Context): HapticMode {
            val prefs = context.getSharedPreferences("game_menu_prefs", Context.MODE_PRIVATE)
            val ordinal = prefs.getInt(PREF_HAPTIC_MODE, HapticMode.KEY_NODES.ordinal)
            return HapticMode.entries.getOrElse(ordinal) { HapticMode.KEY_NODES }
        }

        fun setHapticMode(context: Context, mode: HapticMode) {
            context.getSharedPreferences("game_menu_prefs", Context.MODE_PRIVATE)
                .edit { putInt(PREF_HAPTIC_MODE, mode.ordinal) }
        }

        fun progressToBitrateKbps(progress: Int): Int {
            return when {
                progress <= 9 -> 500 + progress * 500
                progress <= 24 -> 5000 + (progress - 9) * 1000
                progress <= 39 -> 20000 + (progress - 24) * 2000
                progress <= 49 -> 50000 + (progress - 39) * 5000
                else -> 100000 + (progress - 49) * 10000
            }
        }

        fun bitrateToProgress(kbps: Int): Int {
            return when {
                kbps <= 5000 -> ((kbps - 500) / 500).coerceIn(0, 9)
                kbps <= 20000 -> (9 + (kbps - 5000 + 500) / 1000).coerceIn(10, 24)
                kbps <= 50000 -> (24 + (kbps - 20000 + 1000) / 2000).coerceIn(25, 39)
                kbps <= 100000 -> (39 + (kbps - 50000 + 2500) / 5000).coerceIn(40, 49)
                else -> (49 + (kbps - 100000 + 5000) / 10000).coerceIn(50, MAX_PROGRESS)
            }
        }

        fun formatBitrateMbps(kbps: Int): String {
            return if (kbps % 1000 != 0) {
                String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
            } else {
                String.format(Locale.US, "%d Mbps", kbps / 1000)
            }
        }

        /** Returns null when the value cannot be written as a display resolution. */
        fun parseResolutionSelection(
            value: String,
            currentWidth: Int,
            currentHeight: Int
        ): ResolutionSelection? {
            if (value == PreferenceConfiguration.RES_NATIVE) {
                return ResolutionSelection(
                    native = true,
                    custom = false,
                    width = currentWidth,
                    height = currentHeight
                )
            }
            val dimensions = value.split("x")
            if (dimensions.size != 2) return null
            val width = dimensions[0].toIntOrNull() ?: return null
            val height = dimensions[1].toIntOrNull() ?: return null
            if (width <= 0 || height <= 0) return null
            return ResolutionSelection(
                native = false,
                custom = !PreferenceConfiguration.RESOLUTIONS.contains(value),
                width = width,
                height = height
            )
        }
    }

internal data class ResolutionSelection(
    val native: Boolean,
    val custom: Boolean,
    val width: Int,
    val height: Int
)

    private var abrListener: ((Int, String) -> Unit)? = null
    private var onStateChanged: ((BitrateCardState) -> Unit)? = null
    private var userTracking = false
    private var bitrateToast: Toast? = null
    private var state = createState(conn.currentBitrate)
    private var appliedDraft = currentDraft()
    private var draft = appliedDraft

    fun snapshot(): BitrateCardState = state

    fun displayDraft(): DisplaySettingsDraft = draft

    fun hasPendingDisplayChanges(): Boolean = draft.changedFrom(appliedDraft)

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
                        progress = bitrateToProgress(kbps).toFloat(),
                        currentBitrateKbps = kbps
                    )
                    emitState()
                }
            }
        }
        abrListener = listener
        abrService.bitrateListener = listener
    }

    fun setAdaptiveBitrate(enabled: Boolean) {
        if (state.adaptiveBitrate == enabled) return
        game.prefConfig.enableAdaptiveBitrate = enabled
        game.prefConfig.writeDisplayPreferences(game)
        if (enabled) {
            game.startAdaptiveBitrateIfEnabled()
        } else {
            game.stopAdaptiveBitrate()
        }
        state = createState(conn.currentBitrate)
        emitState()
    }

    fun setAbrMode(mode: String) {
        if (mode !in ABR_MODES || state.abrMode == mode) return
        game.prefConfig.abrMode = mode
        game.prefConfig.writeDisplayPreferences(game)
        if (game.prefConfig.enableAdaptiveBitrate) {
            game.stopAdaptiveBitrate()
            game.startAdaptiveBitrateIfEnabled()
        }
        state = createState(conn.currentBitrate)
        emitState()
    }

    fun stageResolution(value: String) {
        if (parseResolutionSelection(value, game.prefConfig.width, game.prefConfig.height) == null) return
        draft = draft.copy(resolution = value)
        emitState()
    }

    fun stageFrameRate(value: String) {
        val fps = value.toIntOrNull() ?: return
        if (fps <= 0) return
        draft = draft.copy(frameRate = fps.toString(), customFrameRate = "")
        emitState()
    }

    fun removeResolution(value: String) {
        val resolution = com.limelight.preferences.ResolutionValidator.parseResolution(value) ?: return
        val remaining = CustomResolutionsStore.load(game) - resolution
        CustomResolutionsStore.save(game, remaining)
        if (draft.resolution == value) {
            draft = draft.copy(resolution = remaining.lastOrNull()?.toString() ?: PreferenceConfiguration.RES_NATIVE)
        }
        emitState()
    }

    fun removeFrameRate(value: String) {
        val fps = value.toIntOrNull() ?: return
        val remaining = CustomFrameRatesStore.load(game) - fps
        CustomFrameRatesStore.save(game, remaining)
        if (draft.frameRate == fps.toString()) {
            draft = draft.copy(frameRate = remaining.lastOrNull()?.toString() ?: draft.frameRate)
        }
        emitState()
    }

    fun stageCustomFrameRate(value: String) {
        val digits = value.filter(Char::isDigit).take(3)
        val fps = digits.toIntOrNull()
        draft = draft.copy(customFrameRate = digits)
        if (fps != null && fps > 0 && value.endsWith("\n")) {
            CustomFrameRatesStore.add(game, fps)
            draft = draft.copy(frameRate = fps.toString(), customFrameRate = "")
        }
        emitState()
    }

    fun stageScreenMode(value: String) {
        if (value.toIntOrNull() == null || value !in setOf("2", "4", "3")) return
        draft = draft.copy(screenMode = value)
        emitState()
    }

    fun applyDisplayDraft(): Boolean {
        if (!hasPendingDisplayChanges()) return false
        val resolution = if (draft.resolution == appliedDraft.resolution) {
            null
        } else {
            parseResolutionSelection(
                draft.resolution,
                game.prefConfig.width,
                game.prefConfig.height
            ) ?: return false
        }
        val frameRate = (draft.customFrameRate.toIntOrNull()?.takeIf { it > 0 }
            ?: draft.frameRate.toIntOrNull())?.takeIf { fps ->
            fps > 0 && fps.toString() != appliedDraft.frameRate
        }
        val screenMode = draft.screenMode.toIntOrNull()?.takeIf { mode ->
            mode.toString() in setOf("2", "4", "3") && mode.toString() != appliedDraft.screenMode
        }
        resolution?.let { selection ->
            game.prefConfig.isNativeResolution = selection.native
            game.prefConfig.isCustomResolution = selection.custom
            if (!selection.native) {
                game.prefConfig.width = selection.width
                game.prefConfig.height = selection.height
            }
        }
        frameRate?.let { game.prefConfig.fps = it }
        screenMode?.let { game.prefConfig.screenCombinationMode = it }
        game.prefConfig.writeDisplayPreferences(game)
        game.changeResolution()
        appliedDraft = currentDraft()
        draft = appliedDraft
        return true
    }

    fun discardDisplayDraft() {
        draft = appliedDraft
        emitState()
    }

    fun selectResolution(value: String): Boolean {
        if (!shouldApplyDisplaySelection(value, currentResolutionValue())) return false
        val selection = parseResolutionSelection(
            value,
            game.prefConfig.width,
            game.prefConfig.height
        ) ?: return false
        game.prefConfig.isNativeResolution = selection.native
        game.prefConfig.isCustomResolution = selection.custom
        if (!selection.native) {
            game.prefConfig.width = selection.width
            game.prefConfig.height = selection.height
        }
        game.prefConfig.writeDisplayPreferences(game)
        game.changeResolution()
        return true
    }

    fun selectFrameRate(value: String) {
        val fps = value.toIntOrNull() ?: return
        if (!shouldApplyDisplaySelection(fps.toString(), game.prefConfig.fps.toString())) return
        game.prefConfig.fps = fps
        game.prefConfig.writeDisplayPreferences(game)
        game.changeResolution()
    }

    fun selectScreenMode(value: String) {
        val mode = value.toIntOrNull() ?: return
        if (!shouldApplyDisplaySelection(mode.toString(), game.prefConfig.screenCombinationMode.toString())) return
        game.prefConfig.screenCombinationMode = mode
        game.prefConfig.writeDisplayPreferences(game)
        game.changeResolution()
    }

    fun refreshDisplayChoices() {
        emitState()
    }

    private fun currentDraft(): DisplaySettingsDraft {
        return DisplaySettingsDraft(
            resolution = currentResolutionValue(),
            frameRate = game.prefConfig.fps.toString(),
            screenMode = game.prefConfig.screenCombinationMode.toString()
        )
    }

    /** Returns whether this progress change should produce a haptic tick. */
    fun previewProgress(progress: Float): Boolean {
        if (!manualBitrateChangeAllowed(state.adaptiveBitrate)) return false
        val bounded = progress.coerceIn(0f, MAX_PROGRESS.toFloat())
        val previousStep = state.progress.roundToInt()
        val currentStep = bounded.roundToInt()
        val changed = currentStep != previousStep
        userTracking = true
        state = state.copy(progress = bounded)
        emitState()
        return changed && when (state.hapticMode) {
            HapticMode.ALL -> true
            HapticMode.KEY_NODES -> currentStep in SEGMENT_BOUNDARIES
            HapticMode.NONE -> false
        }
    }

    fun applySelectedBitrate() {
        userTracking = false
        if (!manualBitrateChangeAllowed(state.adaptiveBitrate)) return
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
        userTracking = false
    }

    fun stop() {
        dispose()
        onStateChanged = null
    }

    private fun createState(kbps: Int): BitrateCardState {
        val abrService = game.adaptiveBitrateService
        val resolution = currentResolutionValue()
        val frameRate = game.prefConfig.fps.toString()
        val screenMode = game.prefConfig.screenCombinationMode.toString()
        val resolutions = resolutionChoices(resolution)
        val frameRates = frameRateChoices(frameRate)
        val screenModes = screenModeChoices(screenMode)
        return BitrateCardState(
            appliedDisplay = DisplaySettingsDraft(resolution, frameRate, screenMode),
            progress = bitrateToProgress(kbps).toFloat(),
            currentBitrateKbps = kbps,
            abrStatus = abrService?.takeIf { it.enabled }?.getStatusText(),
            adaptiveBitrate = game.prefConfig.enableAdaptiveBitrate,
            abrMode = game.prefConfig.abrMode,
            resolutionLabel = resolutions.firstOrNull { it.selected }?.label ?: resolution,
            fpsLabel = frameRates.firstOrNull { it.selected }?.label ?: "$frameRate FPS",
            screenModeLabel = screenModes.firstOrNull { it.selected }?.label ?: screenMode,
            resolutions = resolutions,
            frameRates = frameRates,
            screenModes = screenModes,
            hapticMode = getHapticMode(game)
        )
    }

    private fun currentResolutionValue(): String {
        val config = game.prefConfig
        return when {
            config.isNativeResolution -> PreferenceConfiguration.RES_NATIVE
            else -> "${config.width}x${config.height}"
        }
    }

    private fun resolutionChoices(selected: String): List<DisplayChoice> {
        val custom = CustomResolutionsStore.load(game).map { resolution ->
            val value = resolution.toString()
            DisplayChoice(
                value = value,
                label = value,
                selected = value == selected
            )
        }
        val nativeLabel = game.getString(R.string.resolution_prefix_native) +
            " (${deviceResolutionLabel()})"
        val native = DisplayChoice(
            value = PreferenceConfiguration.RES_NATIVE,
            label = nativeLabel,
            selected = selected == PreferenceConfiguration.RES_NATIVE
        )
        return custom + native
    }

    private fun deviceResolutionLabel(): String {
        val display = (game.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display?.getRealMetrics(metrics)
        val width = metrics.widthPixels.takeIf { it > 0 } ?: game.resources.displayMetrics.widthPixels
        val height = metrics.heightPixels.takeIf { it > 0 } ?: game.resources.displayMetrics.heightPixels
        return "${width}x${height}"
    }

    private fun resourceChoices(
        names: Int,
        values: Int,
        selected: String,
        descriptions: Int? = null
    ): List<DisplayChoice> {
        val labels = game.resources.getStringArray(names)
        val entries = game.resources.getStringArray(values)
        val details = descriptions?.let { game.resources.getStringArray(it) }
        return entries.mapIndexed { index, value ->
            DisplayChoice(
                value = value,
                label = labels.getOrElse(index) { value },
                selected = value == selected,
                description = details?.getOrNull(index)
            )
        }
    }

    private fun emitState() {
        state = state.copy(
            appliedDisplay = appliedDraft,
            resolutions = resolutionChoices(draft.resolution),
            frameRates = frameRateChoices(draft.frameRate),
            screenModes = screenModeChoices(draft.screenMode)
        )
        onStateChanged?.invoke(state)
    }

    private fun frameRateChoices(selected: String): List<DisplayChoice> {
        return (CustomFrameRatesStore.load(game) + 60).distinct().sorted().map { fps ->
            DisplayChoice(
                value = fps.toString(),
                label = "$fps FPS",
                selected = fps.toString() == selected
            )
        }
    }

    private fun screenModeChoices(selected: String): List<DisplayChoice> {
        return resourceChoices(
            R.array.screen_combination_mode_names,
            R.array.screen_combination_mode_values,
            selected,
            R.array.screen_combination_mode_descriptions
        ).filter { it.value in setOf("2", "4", "3") }
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
                        game.prefConfig.writeDisplayPreferences(game)
                        game.adaptiveBitrateService?.notifyManualOverride(newBitrate)
                        state = state.copy(
                            progress = bitrateToProgress(newBitrate).toFloat(),
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
                            progress = bitrateToProgress(actualBitrate).toFloat(),
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
