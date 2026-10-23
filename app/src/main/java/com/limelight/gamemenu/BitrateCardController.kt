package com.limelight.gamemenu

import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.limelight.Game
import com.limelight.R
import com.limelight.nvstream.NvConnection
import com.limelight.preferences.DisplayChoiceLimits
import com.limelight.preferences.DisplayChoiceLists
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.compactResolutionLabel
import com.limelight.ui.screenCombinationModeIconRes
import com.limelight.preferences.prettyResolution
import com.limelight.preferences.ResolutionValidator
import java.util.Locale
import kotlin.math.roundToInt

internal data class DisplayChoice(
    val value: String,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    /** 第二行小注(如 "1280×720"/"FPS"),空则瓦片单行居中。 */
    val subLabel: String? = null,
    /** 瓦片顶部图标(屏幕组合等模式类选项),空则用文字两行布局。 */
    val iconRes: Int? = null
)

internal data class DisplaySettingsDraft(
    val resolution: String,
    val frameRate: String,
    val screenMode: String
) {
    fun changedFrom(current: DisplaySettingsDraft): Boolean {
        return resolution != current.resolution ||
            frameRate != current.frameRate ||
            screenMode != current.screenMode
    }
}

internal data class BitrateCardState(
    val appliedDisplay: DisplaySettingsDraft = DisplaySettingsDraft("", "", ""),
    val displayDraft: DisplaySettingsDraft = DisplaySettingsDraft("", "", ""),
    val progress: Float,
    val currentBitrateKbps: Int,
    val maxProgress: Int,
    val maxBitrateKbps: Int,
    val adaptiveBitrate: Boolean,
    val abrMode: String,
    val resolutionLabel: String,
    val fpsLabel: String,
    val resolutions: List<DisplayChoice>,
    val frameRates: List<DisplayChoice>,
    val screenModes: List<DisplayChoice>,
    val hapticMode: BitrateCardController.HapticMode
) {
    val selectedBitrateKbps: Int
        get() = BitrateCardController.progressToBitrateKbps(progress.roundToInt(), maxProgress)
}

/** Owns bitrate card state and stream-side effects without depending on Android Views. */
internal class BitrateCardController(
    private val game: Game,
    private val conn: NvConnection,
    private val supportsExtendedBitrate: Boolean = false
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
        const val PYROWAVE_MAX_PROGRESS = 67
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
                boundedProgress <= 63 -> 200000 + (boundedProgress - MAX_PROGRESS) * 50000
                else -> 400000 + (boundedProgress - 63) * 100000
            }
        }

        fun bitrateToProgress(kbps: Int, maxProgress: Int = MAX_PROGRESS): Int {
            val unboundedProgress = when {
                kbps <= 5000 -> (kbps - 500) / 500
                kbps <= 20000 -> 9 + (kbps - 5000 + 500) / 1000
                kbps <= 50000 -> 24 + (kbps - 20000 + 1000) / 2000
                kbps <= 100000 -> 39 + (kbps - 50000 + 2500) / 5000
                kbps <= 200000 -> 49 + (kbps - 100000 + 5000) / 10000
                kbps <= 400000 -> MAX_PROGRESS + (kbps - 200000 + 25000) / 50000
                else -> 63 + (kbps - 400000 + 50000) / 100000
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
            val resolution = ResolutionValidator.parseResolution(value) ?: return null
            if (resolution.width <= 0 || resolution.height <= 0) return null
            return ResolutionSelection(
                native = false,
                custom = !PreferenceConfiguration.RESOLUTIONS.contains(value),
                width = resolution.width,
                height = resolution.height
            )
        }

        /** Prepare and save a complete combination before touching the running configuration. */
        internal fun persistDisplayDraft(
            current: PreferenceConfiguration,
            draft: DisplaySettingsDraft,
            nativeSize: Pair<Int, Int>?,
            autoAdjustBitrate: Boolean,
            persist: (PreferenceConfiguration) -> Boolean
        ): PreferenceConfiguration? {
            val currentWidth = if (current.reverseResolution) current.height else current.width
            val currentHeight = if (current.reverseResolution) current.width else current.height
            val resolution = parseResolutionSelection(
                draft.resolution,
                nativeSize?.first ?: currentWidth,
                nativeSize?.second ?: currentHeight
            ) ?: return null
            val fps = draft.frameRate.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val screenMode = draft.screenMode.toIntOrNull() ?: return null
            val next = current.copy().apply {
                isNativeResolution = resolution.native
                isCustomResolution = resolution.custom
                width = if (reverseResolution) resolution.height else resolution.width
                height = if (reverseResolution) resolution.width else resolution.height
                this.fps = fps
                screenCombinationMode = screenMode
            }
            if (autoAdjustBitrate) {
                next.bitrate = DisplayChoiceLists.recommendedBitrate(
                    "${resolution.width}x${resolution.height}",
                    draft.frameRate,
                    nativeSize
                )
            }
            return next.takeIf(persist)
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
    private val maxProgress = maxProgressFor(supportsExtendedBitrate)
    private val maxBitrateKbps = progressToBitrateKbps(maxProgress, maxProgress)

    // 声明须早于 state:构造期的 createState 会读标签表与缓存字段
    private val resolutionLabels: Map<String, String> by lazy {
        DisplayChoiceLists.labelLookup(game.resources, R.array.resolution_names, R.array.resolution_values)
    }

    // 设备能力与商店列表在一次会话内不变,缓存避免拖动滑条时逐帧重算
    private var cachedLimits: DisplayChoiceLimits? = null
    private var cachedNativeSize: Pair<Int, Int>? = null
    private var cachedStoreResolutions: List<String>? = null
    private var appliedDraft = currentDraft()
    private var draft = appliedDraft

    // 声明须晚于 draft:构造期的 createState 会带出当前草稿
    private var state = createState(conn.currentBitrate)

    fun snapshot(): BitrateCardState = state

    fun displayDraft(): DisplaySettingsDraft = draft

    fun hasPendingDisplayChanges(): Boolean = draft.changedFrom(appliedDraft)

    fun start(onStateChanged: (BitrateCardState) -> Unit) {
        dispose()
        invalidateChoiceCaches()
        this.onStateChanged = onStateChanged
        state = createState(conn.currentBitrate)
        emitState()
        bindAbrListener()
    }

    private fun bindAbrListener() {
        val service = game.adaptiveBitrateService ?: return
        val listener: (Int, String) -> Unit = { kbps, _ ->
            game.runOnUiThread {
                if (!userTracking) {
                    state = state.copy(
                        progress = bitrateToProgress(kbps, maxProgress).toFloat(),
                        currentBitrateKbps = kbps
                    )
                    emitState()
                }
            }
        }
        abrListener = listener
        service.bitrateListener = listener
    }

    private fun invalidateChoiceCaches() {
        cachedLimits = null
        cachedNativeSize = null
        cachedStoreResolutions = null
    }

    fun setAdaptiveBitrate(enabled: Boolean) {
        if (state.adaptiveBitrate == enabled) return
        game.prefConfig.enableAdaptiveBitrate = enabled
        game.prefConfig.writeDisplayPreferences(game)
        if (enabled) {
            game.startAdaptiveBitrateIfEnabled()
            bindAbrListener()
        } else {
            abrListener = null
            game.stopAdaptiveBitrate()
        }
        state = createState(conn.currentBitrate)
        emitState()
    }

    fun setAbrMode(mode: String) {
        if (mode !in game.resources.getStringArray(R.array.abr_mode_values) || state.abrMode == mode) return
        game.prefConfig.abrMode = mode
        game.prefConfig.writeDisplayPreferences(game)
        if (game.prefConfig.enableAdaptiveBitrate) {
            game.stopAdaptiveBitrate()
            game.startAdaptiveBitrateIfEnabled()
            bindAbrListener()
        }
        state = createState(conn.currentBitrate)
        emitState()
    }

    fun stageResolution(value: String) {
        if (draft.resolution == value || state.resolutions.none { it.value == value && it.enabled }) return
        draft = draft.copy(resolution = value)
        emitState()
    }

    fun stageFrameRate(value: String) {
        if (draft.frameRate == value || state.frameRates.none { it.value == value && it.enabled }) return
        draft = draft.copy(frameRate = value)
        emitState()
    }

    fun stageScreenMode(value: String) {
        if (draft.screenMode == value || state.screenModes.none { it.value == value && it.enabled }) return
        draft = draft.copy(screenMode = value)
        emitState()
    }

    fun applyDisplayDraft(): Boolean {
        if (!hasPendingDisplayChanges()) return false
        displayChoiceLimits()
        val streamSizeChanged = draft.resolution != appliedDraft.resolution ||
            draft.frameRate != appliedDraft.frameRate
        val autoAdjustBitrate = streamSizeChanged && PreferenceManager.getDefaultSharedPreferences(game)
            .getBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, true)
        val next = persistDisplayDraft(game.prefConfig, draft, cachedNativeSize, autoAdjustBitrate) {
            it.writeDisplayPreferences(game, synchronous = true)
        }
        if (next == null) {
            showBitrateToast(game.getString(R.string.config_save_failed))
            return false
        }
        // Keep the existing object: other stream components retain references to prefConfig.
        game.prefConfig.apply {
            isNativeResolution = next.isNativeResolution
            isCustomResolution = next.isCustomResolution
            width = next.width
            height = next.height
            fps = next.fps
            bitrate = next.bitrate
            screenCombinationMode = next.screenCombinationMode
        }
        appliedDraft = currentDraft()
        draft = appliedDraft
        game.changeResolution()
        return true
    }

    fun discardDisplayDraft() {
        draft = appliedDraft
        emitState()
    }

    fun refreshDisplayChoices() {
        invalidateChoiceCaches()
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
        userTracking = false
    }

    fun stop() {
        dispose()
        onStateChanged = null
    }

    private fun createState(kbps: Int): BitrateCardState {
        val resolution = currentResolutionValue()
        val frameRate = game.prefConfig.fps.toString()
        val screenMode = game.prefConfig.screenCombinationMode.toString()
        val resolutions = resolutionChoices(resolution)
        val frameRates = frameRateChoices(frameRate)
        val screenModes = screenModeChoices(screenMode)
        return BitrateCardState(
            appliedDisplay = DisplaySettingsDraft(resolution, frameRate, screenMode),
            displayDraft = draft,
            progress = bitrateToProgress(kbps, maxProgress).toFloat(),
            currentBitrateKbps = kbps,
            maxProgress = maxProgress,
            maxBitrateKbps = maxBitrateKbps,
            adaptiveBitrate = game.prefConfig.enableAdaptiveBitrate,
            abrMode = game.prefConfig.abrMode,
            resolutionLabel = resolutions.firstOrNull { it.selected }?.label ?: resolution,
            fpsLabel = frameRates.firstOrNull { it.selected }?.label ?: "$frameRate FPS",
            resolutions = resolutions,
            frameRates = frameRates,
            screenModes = screenModes,
            hapticMode = getHapticMode(game)
        )
    }

    private fun currentResolutionValue(): String {
        val config = game.prefConfig
        val width = if (config.reverseResolution) config.height else config.width
        val height = if (config.reverseResolution) config.width else config.height
        return when {
            config.isNativeResolution -> PreferenceConfiguration.RES_NATIVE
            else -> "${width}x${height}"
        }
    }

    private fun resolutionChoices(selected: String): List<DisplayChoice> {
        val limits = displayChoiceLimits()
        val showLow = PreferenceManager.getDefaultSharedPreferences(game)
            .getBoolean(PreferenceConfiguration.SHOW_LOW_RESOLUTION_PRESETS_PREF_STRING, false)
        val storeValues = cachedStoreResolutions
            ?: DisplayChoiceLists.resolutionStoreValues(game).also { cachedStoreResolutions = it }
        return DisplayChoiceLists
            .resolutionValues(storeValues, currentResolutionValue(), showLow, listOf(selected))
            .map { value ->
                val isSelected = value == selected
                when (value) {
                    PreferenceConfiguration.RES_NATIVE -> DisplayChoice(
                        value = value,
                        label = game.getString(R.string.resolution_prefix_native),
                        selected = isSelected,
                        subLabel = cachedNativeSize?.let { prettyResolution("${it.first}x${it.second}") }
                    )
                    else -> DisplayChoice(
                        value = value,
                        label = resolutionLabels[value]
                            ?: compactResolutionLabel(value),
                        selected = isSelected,
                        enabled = limits.supportsResolution(value),
                        subLabel = prettyResolution(value)
                    )
                }
            }
    }

    private fun resourceChoices(names: Int, values: Int, selected: String): List<DisplayChoice> {
        val labels = DisplayChoiceLists.labelLookup(game.resources, names, values)
        return game.resources.getStringArray(values).map { value ->
            DisplayChoice(
                value = value,
                label = labels[value] ?: value,
                selected = value == selected
            )
        }
    }

    private fun emitState() {
        state = state.copy(
            appliedDisplay = appliedDraft,
            displayDraft = draft,
            resolutions = resolutionChoices(draft.resolution),
            frameRates = frameRateChoices(draft.frameRate),
            screenModes = screenModeChoices(draft.screenMode)
        )
        onStateChanged?.invoke(state)
    }

    private fun frameRateChoices(selected: String): List<DisplayChoice> {
        val limits = displayChoiceLimits()
        val unlockFps = game.prefConfig.unlockFps
        val frameRates = DisplayChoiceLists.frameRateValues(
            game.resources.getStringArray(R.array.fps_values).toList(),
            limits.maxRefreshRate,
            listOf(game.prefConfig.fps.toString(), selected)
        )
        return frameRates.map { value ->
            DisplayChoice(
                value = value,
                label = value,
                selected = value == selected,
                enabled = limits.supportsFrameRate(value, unlockFps),
                subLabel = game.getString(R.string.fps_suffix_fps)
            )
        }
    }

    private fun displayChoiceLimits(): DisplayChoiceLimits {
        if (cachedLimits == null) {
            val display = game.externalDisplayManager?.getTargetDisplay() ?: game.windowManager.defaultDisplay
            cachedLimits = DisplayChoiceLimits.from(game, display)
            cachedNativeSize = DisplayChoiceLists.nativeSize(game, display)
        }
        return cachedLimits!!
    }

    private fun screenModeChoices(selected: String): List<DisplayChoice> {
        return resourceChoices(
            R.array.screen_combination_mode_names,
            R.array.screen_combination_mode_values,
            selected
        ).map { choice ->
            choice.copy(
                iconRes = screenCombinationModeIconRes(choice.value.toIntOrNull() ?: -1)
            )
        }
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
                            progress = bitrateToProgress(newBitrate, maxProgress).toFloat(),
                            currentBitrateKbps = newBitrate
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
