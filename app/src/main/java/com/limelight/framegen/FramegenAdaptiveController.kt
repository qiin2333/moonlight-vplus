package com.limelight.framegen

import com.limelight.LimeLog
import com.limelight.binding.video.PerformanceInfo
import kotlin.math.max

class FramegenAdaptiveController {

    data class Config(
        val inputFps: Int,
        val presentationFps: Int,
        val adaptiveEnabled: Boolean,
        val allowAdaptiveWithoutDoubling: Boolean,
        val internalWidth: Int,
        val presentMode: Int,
        val slowFrameThresholdMs: Int,
        val presentQueueMax: Int
    )

    private enum class Mode {
        NORMAL,
        LOSS_SMOOTH,
        DEVICE_LIMITED
    }

    private var config: Config? = null
    private var mode = Mode.NORMAL
    private var lossWindows = 0
    private var fastLossHoldWindows = 0
    private var lossRecoveryWindows = 0
    private var deviceWindows = 0
    private var stableWindows = 0

    @Volatile
    var activePresentationFps: Int = 0
        private set

    fun configure(config: Config) {
        this.config = config
        mode = Mode.NORMAL
        lossWindows = 0
        fastLossHoldWindows = 0
        lossRecoveryWindows = 0
        deviceWindows = 0
        stableWindows = 0
        activePresentationFps = config.presentationFps
    }

    fun reset() {
        config = null
        mode = Mode.NORMAL
        lossWindows = 0
        fastLossHoldWindows = 0
        lossRecoveryWindows = 0
        deviceWindows = 0
        stableWindows = 0
        activePresentationFps = 0
    }

    fun onPerformanceInfo(performanceInfo: PerformanceInfo) {
        val currentConfig = config ?: return
        if (!currentConfig.adaptiveEnabled ||
            (!currentConfig.allowAdaptiveWithoutDoubling && currentConfig.presentationFps <= currentConfig.inputFps) ||
            currentConfig.inputFps <= 0
        ) {
            return
        }

        val targetMode = chooseMode(currentConfig, performanceInfo)
        if (targetMode != mode) {
            applyMode(targetMode, describe(performanceInfo), force = false)
        }
    }

    fun onFrameLossEvent(framesLost: Int, frameNumber: Int) {
        val currentConfig = config ?: return
        if (!currentConfig.adaptiveEnabled ||
            (!currentConfig.allowAdaptiveWithoutDoubling && currentConfig.presentationFps <= currentConfig.inputFps) ||
            currentConfig.inputFps <= 0
        ) {
            return
        }

        if (mode != Mode.LOSS_SMOOTH) {
            fastLossHoldWindows = FAST_LOSS_HOLD_WINDOWS
            stableWindows = 0
        }
        lossRecoveryWindows = max(lossRecoveryWindows, FAST_LOSS_RECOVERY_WINDOWS)
        deviceWindows = 0
        applyMode(
            Mode.LOSS_SMOOTH,
            "fastLoss frames=$framesLost frame=$frameNumber",
            force = false
        )
    }

    private fun chooseMode(config: Config, performanceInfo: PerformanceInfo): Mode {
        val inputFps = config.inputFps.toFloat()
        val renderedRatio = performanceInfo.renderedFps.ratioTo(inputFps)
        val receivedRatio = performanceInfo.receivedFps.ratioTo(inputFps)
        val rttMs = (performanceInfo.rttInfo shr 32).toInt().coerceAtLeast(0)
        val localWorkMs = performanceInfo.decodeTimeMs + performanceInfo.renderingLatencyMs
        val outputBudgetMs = 1000f / config.presentationFps.toFloat()

        val fastLossActive = fastLossHoldWindows > 0
        val metricNetworkPressure =
            performanceInfo.lostFrameRate >= LOSS_MILD_PERCENT ||
                (rttMs >= RTT_HIGH_MS && receivedRatio > 0f && receivedRatio < LOSS_RECEIVED_RATIO) ||
                (receivedRatio > 0f && receivedRatio < LOSS_RECEIVED_RATIO && renderedRatio >= receivedRatio * 0.9f)
        val networkPressure = metricNetworkPressure || fastLossActive

        if (metricNetworkPressure) {
            lossRecoveryWindows = LOSS_RECOVERY_WINDOWS
        } else if (lossRecoveryWindows > 0) {
            lossRecoveryWindows -= 1
        }

        val localWorkPressure =
            localWorkMs > max(DEVICE_MIN_WORK_MS, outputBudgetMs * DEVICE_WORK_BUDGET_MULTIPLIER)
        val localFpsPressure = renderedRatio > 0f && renderedRatio < DEVICE_RENDERED_RATIO
        val devicePressure = localWorkPressure || (!networkPressure && lossRecoveryWindows == 0 && localFpsPressure)

        if (devicePressure && performanceInfo.lostFrameRate < LOSS_SEVERE_PERCENT) {
            deviceWindows += 1
        } else {
            deviceWindows = 0
        }

        if (!devicePressure && networkPressure) {
            lossWindows += 1
        } else {
            lossWindows = 0
        }

        if (!devicePressure && !networkPressure && performanceInfo.lostFrameRate < LOSS_STABLE_PERCENT &&
            (renderedRatio == 0f || renderedRatio >= STABLE_RENDERED_RATIO)
        ) {
            stableWindows += 1
        } else {
            stableWindows = 0
        }

        val targetMode = when {
            fastLossActive -> Mode.LOSS_SMOOTH
            deviceWindows >= DEVICE_ENTER_WINDOWS -> Mode.DEVICE_LIMITED
            performanceInfo.lostFrameRate >= LOSS_SEVERE_PERCENT -> Mode.LOSS_SMOOTH
            lossWindows >= LOSS_ENTER_WINDOWS -> Mode.LOSS_SMOOTH
            stableWindows >= STABLE_EXIT_WINDOWS -> Mode.NORMAL
            else -> mode
        }
        if (fastLossHoldWindows > 0) {
            fastLossHoldWindows -= 1
        }
        return targetMode
    }

    private fun applyMode(nextMode: Mode, reason: String, force: Boolean) {
        val currentConfig = config ?: return
        if (!force && nextMode == mode) return

        val targetFps = when (nextMode) {
            Mode.DEVICE_LIMITED -> currentConfig.inputFps
            Mode.NORMAL,
            Mode.LOSS_SMOOTH -> currentConfig.presentationFps
        }
        val internalWidth = when (nextMode) {
            Mode.DEVICE_LIMITED -> minOf(currentConfig.internalWidth, DEVICE_LIMITED_INTERNAL_WIDTH)
            Mode.NORMAL,
            Mode.LOSS_SMOOTH -> currentConfig.internalWidth
        }
        val presentMode = when (nextMode) {
            Mode.LOSS_SMOOTH -> 1
            Mode.NORMAL,
            Mode.DEVICE_LIMITED -> currentConfig.presentMode
        }
        val slowFrameThresholdMs = when (nextMode) {
            Mode.LOSS_SMOOTH -> max(currentConfig.slowFrameThresholdMs, LOSS_SLOW_THRESHOLD_MS)
            Mode.NORMAL,
            Mode.DEVICE_LIMITED -> currentConfig.slowFrameThresholdMs
        }
        val presentQueueMax = when (nextMode) {
            Mode.LOSS_SMOOTH -> max(currentConfig.presentQueueMax, LOSS_PRESENT_QUEUE_MAX)
            Mode.DEVICE_LIMITED -> 1
            Mode.NORMAL -> currentConfig.presentQueueMax
        }

        FramegenInterceptor.configureOutputFrameRate(targetFps)
        FramegenInterceptor.configureTuning(
            internalWidth,
            presentMode,
            slowFrameThresholdMs,
            presentQueueMax,
            currentConfig.allowAdaptiveWithoutDoubling
        )

        val previousMode = mode
        mode = nextMode
        activePresentationFps = targetFps
        if (!force || previousMode != nextMode) {
            LimeLog.info(
                "Framegen adaptive $previousMode -> $nextMode " +
                    "fps=$targetFps width=$internalWidth mode=$presentMode " +
                    "slowMs=$slowFrameThresholdMs queue=$presentQueueMax ($reason)"
            )
        }
    }

    private fun describe(performanceInfo: PerformanceInfo): String =
        "loss=%.1f%% rtt=%dms recv=%.1f render=%.1f decode=%.1f renderMs=%.1f low=%.1f".format(
            performanceInfo.lostFrameRate,
            (performanceInfo.rttInfo shr 32).toInt().coerceAtLeast(0),
            performanceInfo.receivedFps,
            performanceInfo.renderedFps,
            performanceInfo.decodeTimeMs,
            performanceInfo.renderingLatencyMs,
            performanceInfo.onePercentLowFps
        )

    private fun Float.ratioTo(denominator: Float): Float =
        if (this > 0f && denominator > 0f) this / denominator else 0f

    companion object {
        private const val LOSS_MILD_PERCENT = 0.75f
        private const val LOSS_SEVERE_PERCENT = 4.0f
        private const val LOSS_STABLE_PERCENT = 0.25f
        private const val LOSS_RECEIVED_RATIO = 0.94f
        private const val RTT_HIGH_MS = 80
        private const val LOSS_ENTER_WINDOWS = 2
        private const val FAST_LOSS_HOLD_WINDOWS = 2
        private const val FAST_LOSS_RECOVERY_WINDOWS = 2
        private const val LOSS_RECOVERY_WINDOWS = 3
        private const val LOSS_PRESENT_QUEUE_MAX = 3
        private const val LOSS_SLOW_THRESHOLD_MS = 24

        private const val DEVICE_RENDERED_RATIO = 0.82f
        private const val DEVICE_MIN_WORK_MS = 15f
        private const val DEVICE_WORK_BUDGET_MULTIPLIER = 1.8f
        private const val DEVICE_ENTER_WINDOWS = 2
        private const val DEVICE_LIMITED_INTERNAL_WIDTH = 864

        private const val STABLE_RENDERED_RATIO = 0.95f
        private const val STABLE_EXIT_WINDOWS = 3
    }
}
