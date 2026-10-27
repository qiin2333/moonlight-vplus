package com.limelight.gamemenu

import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.limelight.DisplayReconnectSettings
import com.limelight.Game
import com.limelight.R
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.preferences.DisplayChoiceLimits
import com.limelight.preferences.DisplayChoiceLists
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.compactResolutionLabel
import com.limelight.preferences.prettyResolution
import com.limelight.preferences.ResolutionValidator
import java.util.Locale
import kotlin.math.roundToInt

internal data class DisplayChoice(
    val value: String,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    /** 选择面板中可选的补充说明，如实际分辨率或 FPS 单位。 */
    val subLabel: String? = null,
    val emphasized: Boolean = false
)

internal data class DisplaySettingsDraft(
    val resolution: String,
    val frameRate: String,
    val screenMode: String
) {
    fun changedFrom(current: DisplaySettingsDraft): Boolean = this != current
}

internal data class DisplayScaleState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val friendlyName: String = "",
    val displayName: String = "",
    val deviceId: String = "",
    val currentPercent: Int? = null,
    val recommendedPercent: Int? = null,
    val supportedPercents: List<Int> = emptyList(),
    val errorCode: String? = null,
    val pendingPercent: Int? = null
)

internal data class BitrateCardState(
    val progress: Float,
    val maxProgress: Int,
    val maxBitrateKbps: Int,
    val adaptiveBitrate: Boolean,
    val abrMode: String,
    val resolutionLabel: String,
    val fpsLabel: String,
    val resolutions: List<DisplayChoice>,
    val frameRates: List<DisplayChoice>,
    val screenModes: List<DisplayChoice>,
    val hapticMode: BitrateCardController.HapticMode,
    val displayScale: DisplayScaleState = DisplayScaleState(),
    val screenModeLabel: String = screenModes.firstOrNull { it.selected }?.label ?: "—",
    val bitrateUpdating: Boolean = false
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

        /** Resolve an explicit stream target without ever substituting another display. */
        internal fun resolveDisplayScale(
            displays: List<NvHTTP.DisplayInfo>,
            targetDeviceId: String?,
            loadOptions: (String?, String?) -> NvHTTP.DisplayScaleInfo
        ): NvHTTP.DisplayScaleInfo {
            val explicitTarget = targetDeviceId?.takeIf { it.isNotBlank() }
            val target = if (explicitTarget == null) {
                displays.firstOrNull { it.isPrimary } ?: displays.firstOrNull()
            } else {
                displays.firstOrNull {
                    displayIdentityMatches(explicitTarget, it.guid, it.displayName, it.friendlyName)
                }
            }
            val catalogScale = target?.let {
                NvHTTP.DisplayScaleInfo(
                    displayName = it.displayName,
                    deviceId = it.guid,
                    friendlyName = it.friendlyName,
                    isPrimary = it.isPrimary,
                    currentScalePercent = it.currentScalePercent,
                    recommendedScalePercent = it.recommendedScalePercent,
                    supportedScalePercents = it.supportedScalePercents,
                    scaleSetSupported = it.scaleSetSupported
                )
            }
            if (catalogScale?.isAvailable == true) return catalogScale
            val resolved = loadOptions(
                target?.displayName?.takeIf { it.isNotBlank() },
                target?.guid?.takeIf { it.isNotBlank() } ?: explicitTarget
            )
            val expectedIdentities = listOfNotNull(target?.guid, target?.displayName, explicitTarget)
                .filter { it.isNotBlank() }
            if (explicitTarget != null && expectedIdentities.none {
                    displayIdentityMatches(it, resolved.deviceId, resolved.displayName, resolved.friendlyName)
                }) {
                throw NvHTTP.DisplayScaleException(404, "display_not_found", "Display unavailable")
            }
            return resolved
        }

        private fun displayIdentityMatches(target: String, vararg identities: String): Boolean =
            identities.any { it.equals(target, ignoreCase = true) }

        internal fun displayScaleErrorCode(error: Exception): String? {
            val businessCode = when (error) {
                is NvHTTP.DisplayScaleException -> error.errorCode
                is HostHttpResponseException -> error.getSunshineErrorCode()
                else -> null
            }?.takeIf { it.isNotBlank() }
            if (businessCode != null) return businessCode
            val statusCode = when (error) {
                is NvHTTP.DisplayScaleException -> error.statusCode
                is HostHttpResponseException -> error.getErrorCode()
                else -> null
            }
            // Missing optional endpoints on older hosts do not need a retry affordance.
            return if (statusCode == 404 || statusCode == 501) null else "request_failed"
        }
    }

    internal data class ResolutionSelection(
        val native: Boolean,
        val custom: Boolean,
        val width: Int,
        val height: Int
    )

    private var abrListener: ((Int, String) -> Unit)? = null
    private var abrListenerGeneration = 0L
    private var onStateChanged: ((BitrateCardState) -> Unit)? = null
    private var menuGeneration = 0L
    private var userTracking = false
    private var bitrateToast: Toast? = null
    private val bitrateRequests = BitrateAdjustmentQueue()
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

    private var displayScaleState = DisplayScaleState()
    private var displayScaleThread: Thread? = null
    /** Interrupting OkHttp is best effort, so reject stale responses by generation. */
    @Volatile
    private var displayScaleRequestGeneration = 0L
    // 声明须晚于 draft:构造期的 createState 会带出当前草稿
    private var state = createState(conn.currentBitrate)

    fun snapshot(): BitrateCardState = state

    fun start(onStateChanged: (BitrateCardState) -> Unit): Long {
        dispose()
        val generation = ++menuGeneration
        invalidateChoiceCaches()
        this.onStateChanged = onStateChanged
        appliedDraft = currentDraft()
        draft = appliedDraft
        state = createState(conn.currentBitrate)
        bindAbrListener()
        refreshDisplayScale()
        return generation
    }

    private fun bindAbrListener() {
        val service = game.adaptiveBitrateService ?: return
        val generation = ++abrListenerGeneration
        val listener: (Int, String) -> Unit = { kbps, _ ->
            game.runOnUiThread {
                if (generation == abrListenerGeneration && game.conn === conn &&
                    game.adaptiveBitrateService === service &&
                    !userTracking && !bitrateRequests.isBusy
                ) {
                    state = state.copy(
                        progress = bitrateToProgress(kbps, maxProgress).toFloat()
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
            unbindAbrListener()
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
            unbindAbrListener()
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
        if (!draft.changedFrom(appliedDraft)) return false
        val limits = displayChoiceLimits()
        val frameRateChanged = draft.frameRate != appliedDraft.frameRate
        val streamSizeChanged = draft.resolution != appliedDraft.resolution ||
            frameRateChanged
        val autoAdjustBitrate = streamSizeChanged && PreferenceManager.getDefaultSharedPreferences(game)
            .getBoolean(PreferenceConfiguration.AUTO_ADJUST_BITRATE_PREF_STRING, true)
        val next = persistDisplayDraft(game.prefConfig, draft, cachedNativeSize, autoAdjustBitrate) {
            it.writeDisplayPreferences(game, synchronous = true)
        }
        if (next == null) {
            discardDisplayDraft()
            showBitrateToast(game.getString(R.string.config_save_failed))
            return false
        }
        val warnAboutFrameRate = frameRateChanged && limits.exceedsRefreshRate(draft.frameRate)
        // Keep the existing object: other stream components retain references to prefConfig.
        DisplayReconnectSettings.capture(next).applyTo(game.prefConfig)
        appliedDraft = currentDraft()
        draft = appliedDraft
        if (warnAboutFrameRate) {
            Toast.makeText(game, R.string.display_frame_rate_above_refresh_rate, Toast.LENGTH_LONG).show()
        } else {
            showBitrateToast(game.getString(R.string.display_settings_reconnecting))
        }
        game.changeResolution()
        return true
    }

    private fun discardDisplayDraft() {
        draft = appliedDraft
        emitState()
    }

    fun refreshDisplayScale() {
        val requestGeneration = ++displayScaleRequestGeneration
        displayScaleThread?.interrupt()
        displayScaleState = displayScaleState.copy(
            loading = true,
            errorCode = null,
            pendingPercent = null
        )
        emitState()
        val targetDeviceId = conn.displayName()
        displayScaleThread = Thread {
            try {
                val http = conn.createNvHttp()
                val catalog = http.getDisplays()
                val resolved = resolveDisplayScale(catalog.displays, targetDeviceId, http::getDisplayScaleOptions)
                game.runOnUiThread {
                    if (requestGeneration != displayScaleRequestGeneration) return@runOnUiThread
                    displayScaleState = DisplayScaleState(
                        available = resolved.isAvailable,
                        friendlyName = resolved.friendlyName,
                        displayName = resolved.displayName,
                        deviceId = resolved.deviceId,
                        currentPercent = resolved.currentScalePercent,
                        recommendedPercent = resolved.recommendedScalePercent,
                        supportedPercents = resolved.supportedScalePercents,
                        loading = false
                    )
                    emitState()
                }
            } catch (e: Exception) {
                if (e is InterruptedException) return@Thread
                val errorCode = displayScaleErrorCode(e)
                game.runOnUiThread {
                    if (requestGeneration != displayScaleRequestGeneration) return@runOnUiThread
                    displayScaleState = displayScaleState.copy(
                        available = false,
                        loading = false,
                        errorCode = errorCode
                    )
                    emitState()
                }
            }
        }.also { it.name = "display-scale-load"; it.start() }
    }

    fun setDisplayScale(percent: Int) {
        val current = displayScaleState
        if (!current.available || current.loading || percent !in current.supportedPercents ||
            percent == current.currentPercent
        ) return
        val requestGeneration = ++displayScaleRequestGeneration
        displayScaleState = current.copy(loading = true, pendingPercent = percent, errorCode = null)
        emitState()
        displayScaleThread?.interrupt()
        displayScaleThread = Thread {
            try {
                val http = conn.createNvHttp()
                val result = http.setDisplayScale(
                    current.displayName.takeIf { it.isNotBlank() },
                    current.deviceId.takeIf { it.isNotBlank() },
                    percent
                )
                val refreshed = runCatching {
                    http.getDisplayScaleOptions(
                        result.displayName.takeIf { it.isNotBlank() },
                        result.deviceId.takeIf { it.isNotBlank() }
                    )
                }.getOrNull()
                game.runOnUiThread {
                    if (requestGeneration != displayScaleRequestGeneration) return@runOnUiThread
                    displayScaleState = displayScaleState.copy(
                        available = refreshed?.isAvailable ?: current.available,
                        friendlyName = refreshed?.friendlyName ?: displayScaleState.friendlyName,
                        displayName = refreshed?.displayName ?: result.displayName,
                        deviceId = refreshed?.deviceId ?: result.deviceId,
                        currentPercent = refreshed?.currentScalePercent ?: result.currentScalePercent ?: percent,
                        recommendedPercent = refreshed?.recommendedScalePercent ?: displayScaleState.recommendedPercent,
                        supportedPercents = refreshed?.supportedScalePercents?.ifEmpty {
                            result.supportedScalePercents.ifEmpty { current.supportedPercents }
                        } ?: result.supportedScalePercents.ifEmpty { current.supportedPercents },
                        loading = false,
                        pendingPercent = null
                    )
                    emitState()
                    val message = when {
                        result.restartRequired -> game.getString(R.string.display_scale_restart_required)
                        !result.effectiveImmediately -> game.getString(R.string.display_scale_applied_later)
                        else -> game.getString(R.string.display_scale_applied, result.currentScalePercent ?: percent)
                    }
                    showBitrateToast(message)
                }
            } catch (e: Exception) {
                if (e is InterruptedException) return@Thread
                game.runOnUiThread {
                    if (requestGeneration != displayScaleRequestGeneration) return@runOnUiThread
                    displayScaleState = displayScaleState.copy(
                        loading = false,
                        pendingPercent = null,
                        errorCode = displayScaleErrorCode(e)
                    )
                    emitState()
                    showBitrateToast(game.getString(R.string.display_scale_apply_failed))
                }
            }
        }.also { it.name = "display-scale-set"; it.start() }
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
        return changed && when (state.hapticMode) {
            HapticMode.ALL -> true
            HapticMode.KEY_NODES -> currentStep in SEGMENT_BOUNDARIES || currentStep == maxProgress
            HapticMode.NONE -> false
        }
    }

    fun applySelectedBitrate() {
        userTracking = false
        val request = bitrateRequests.submit(state.selectedBitrateKbps)
        emitState()
        request?.let { adjustBitrate(it) }
    }

    fun cycleHapticMode() {
        val mode = state.hapticMode.next()
        setHapticMode(game, mode)
        state = state.copy(hapticMode = mode)
        emitState()
        Toast.makeText(game, mode.label(game), Toast.LENGTH_SHORT).show()
    }

    private fun unbindAbrListener() {
        ++abrListenerGeneration
        val listener = abrListener
        if (listener != null && game.adaptiveBitrateService?.bitrateListener === listener) {
            game.adaptiveBitrateService?.bitrateListener = null
        }
        abrListener = null
    }

    private fun dispose() {
        unbindAbrListener()
        userTracking = false
    }

    fun stop(expectedMenuGeneration: Long? = null) {
        // Dialog dismissal can be delivered after a replacement menu has subscribed.
        if (expectedMenuGeneration != null && expectedMenuGeneration != menuGeneration) return
        dispose()
        ++displayScaleRequestGeneration
        displayScaleThread?.interrupt()
        displayScaleThread = null
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
            progress = bitrateToProgress(
                bitrateRequests.latestTargetKbps ?: kbps,
                maxProgress
            ).toFloat(),
            maxProgress = maxProgress,
            maxBitrateKbps = maxBitrateKbps,
            adaptiveBitrate = game.prefConfig.enableAdaptiveBitrate,
            abrMode = game.prefConfig.abrMode,
            resolutionLabel = resolutions.firstOrNull { it.selected }?.label ?: resolution,
            fpsLabel = frameRates.firstOrNull { it.selected }?.label ?: "$frameRate FPS",
            resolutions = resolutions,
            frameRates = frameRates,
            screenModes = screenModes,
            screenModeLabel = screenModeLabel(screenMode),
            hapticMode = getHapticMode(game),
            displayScale = displayScaleState,
            bitrateUpdating = bitrateRequests.isBusy
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
        val storeValues = cachedStoreResolutions
            ?: DisplayChoiceLists.resolutionStoreValues(game).also { cachedStoreResolutions = it }
        return DisplayChoiceLists
            .resolutionValues(storeValues, currentResolutionValue(), listOf(selected))
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
                        subLabel = if (value !in storeValues) {
                            game.getString(R.string.display_resolution_current_not_saved)
                        } else {
                            prettyResolution(value)
                        }
                    )
                }
            }
    }

    private fun screenModeLabels(): Map<String, String> = DisplayChoiceLists.labelLookup(
        game.resources,
        R.array.screen_combination_mode_names,
        R.array.screen_combination_mode_values
    )

    private fun screenModeChoices(selected: String): List<DisplayChoice> {
        val labels = screenModeLabels()
        // In-stream actions change topology; startup policies stay in the full settings page.
        return listOf("2", "4", "3").map { value ->
            DisplayChoice(
                value = value,
                label = labels[value] ?: value,
                selected = value == selected
            )
        }
    }

    private fun screenModeLabel(selected: String): String = screenModeLabels()[selected] ?: selected

    private fun emitState() {
        state = state.copy(
            resolutions = resolutionChoices(draft.resolution),
            frameRates = frameRateChoices(draft.frameRate),
            screenModes = screenModeChoices(draft.screenMode),
            screenModeLabel = screenModeLabel(draft.screenMode),
            displayScale = displayScaleState,
            bitrateUpdating = bitrateRequests.isBusy
        )
        onStateChanged?.invoke(state)
    }

    private fun frameRateChoices(selected: String): List<DisplayChoice> {
        val limits = displayChoiceLimits()
        val frameRates = DisplayChoiceLists.frameRateValues(
            game.resources.getStringArray(R.array.fps_values).toList(),
            limits.maxRefreshRate,
            listOf(game.prefConfig.fps.toString(), selected)
        )
        return frameRates.map { value ->
            val emphasized = limits.exceedsRefreshRate(value)
            DisplayChoice(
                value = value,
                label = if (emphasized) {
                    "$value ${game.getString(R.string.display_frame_rate_above_refresh_rate_marker)}"
                } else {
                    value
                },
                selected = value == selected,
                subLabel = game.getString(R.string.fps_suffix_fps),
                emphasized = emphasized
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

    private fun showBitrateToast(message: String) {
        bitrateToast?.cancel()
        bitrateToast = Toast.makeText(game, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun adjustBitrate(request: BitrateAdjustmentQueue.Request, showProgress: Boolean = true) {
        try {
            if (showProgress && onStateChanged != null) {
                showBitrateToast(game.getString(R.string.toast_adjusting_bitrate))
            }
            conn.setBitrate(request.bitrateKbps, object : NvConnection.BitrateAdjustmentCallback {
                override fun onSuccess(newBitrate: Int) {
                    game.runOnUiThread {
                        completeBitrateAdjustment(request, newBitrate)
                    }
                }

                override fun onFailure(errorMessage: String) {
                    game.runOnUiThread {
                        completeBitrateAdjustment(request, conn.currentBitrate, errorMessage)
                    }
                }
            })
        } catch (e: Exception) {
            game.runOnUiThread {
                completeBitrateAdjustment(request, conn.currentBitrate, e.message.orEmpty())
            }
        }
    }

    private fun completeBitrateAdjustment(
        request: BitrateAdjustmentQueue.Request,
        confirmedBitrateKbps: Int,
        errorMessage: String? = null
    ) {
        if (game.conn !== conn || game.isFinishing || game.isDestroyed) {
            bitrateRequests.discard()
            return
        }
        val completion = bitrateRequests.complete(
            request, confirmedBitrateKbps.takeIf { errorMessage == null }, userTracking
        ) ?: return
        if (errorMessage == null) {
            game.prefConfig.bitrate = confirmedBitrateKbps
            game.prefConfig.writeDisplayPreferences(game)
            game.adaptiveBitrateService?.notifyManualOverride(confirmedBitrateKbps)
        }
        state = state.copy(
            progress = if (completion.settleSelection) {
                bitrateToProgress(confirmedBitrateKbps, maxProgress).toFloat()
            } else {
                state.progress
            }
        )
        emitState()
        completion.nextRequest?.let { adjustBitrate(it, showProgress = false) }
        if (completion.reportResult && onStateChanged != null) {
            showBitrateToast(
                if (errorMessage == null) {
                    game.getString(
                        R.string.game_menu_bitrate_adjustment_success,
                        confirmedBitrateKbps / 1000
                    )
                } else {
                    game.getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + errorMessage
                }
            )
        }
    }
}
