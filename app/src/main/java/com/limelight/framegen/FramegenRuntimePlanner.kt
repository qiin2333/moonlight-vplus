package com.limelight.framegen

import android.content.SharedPreferences
import com.limelight.LimeLog
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.FramegenSettings

data class FramegenRuntimeConfig(
    val losslessDllPath: String,
    val inputFps: Int,
    val presentationFps: Int,
    val inputHdrEnabled: Boolean,
    val inputHdrMode: Int,
    val adaptiveEnabled: Boolean,
    val allowAdaptiveWithoutDoubling: Boolean,
    val internalWidth: Int,
    val presentMode: Int,
    val slowFrameThresholdMs: Int,
    val presentQueueMax: Int
)

object FramegenRuntimePlanner {
    private const val MAX_DOUBLING_INPUT_FPS = 60

    fun shouldUse(
        prefs: SharedPreferences,
        width: Int,
        height: Int,
        inputFps: Int
    ): Boolean {
        if (!ready(prefs)) {
            return false
        }

        return (regularRequested(prefs, inputFps) || FramegenSettings.isAdaptiveEnabled(prefs)) &&
            FramegenSettings.isCaptureResolutionSupported(width, height)
    }

    fun presentationFps(prefs: SharedPreferences, inputFps: Int): Int =
        if (regularEnabled(prefs, inputFps)) {
            inputFps * 2
        } else {
            inputFps
        }

    fun configForStream(
        prefs: SharedPreferences,
        width: Int,
        height: Int,
        inputFps: Int,
        inputHdrEnabled: Boolean,
        inputHdrMode: Int
    ): FramegenRuntimeConfig? {
        if (!ready(prefs)) {
            return null
        }

        val regularEnabled = regularRequested(prefs, inputFps)
        val adaptiveEnabled = FramegenSettings.isAdaptiveEnabled(prefs)
        if (!regularEnabled && !adaptiveEnabled) {
            return null
        }

        if (!FramegenSettings.isCaptureResolutionSupported(width, height)) {
            LimeLog.warning(
                "Framegen disabled for ${width}x${height}: " +
                    "exceeds ${FramegenSettings.MAX_CAPTURE_PIXELS} pixels"
            )
            return null
        }

        val dllPath = FramegenSettings.resolveLosslessDllPath(prefs) ?: return null

        return FramegenRuntimeConfig(
            losslessDllPath = dllPath,
            inputFps = inputFps,
            presentationFps = presentationFps(prefs, inputFps),
            inputHdrEnabled = inputHdrEnabled,
            inputHdrMode = if (inputHdrEnabled) inputHdrMode else MoonBridge.HDR_MODE_SDR,
            adaptiveEnabled = adaptiveEnabled,
            allowAdaptiveWithoutDoubling = adaptiveEnabled && !regularEnabled,
            internalWidth = FramegenSettings.resolveInternalWidth(prefs),
            presentMode = if (prefs.getBoolean(FramegenSettings.PREF_PRESENT_REAL_FIRST, false)) 1 else 0,
            slowFrameThresholdMs = prefs.getInt(
                FramegenSettings.PREF_SLOW_THRESHOLD_MS,
                FramegenSettings.DEFAULT_SLOW_THRESHOLD_MS
            ),
            presentQueueMax = FramegenSettings.DEFAULT_PRESENT_QUEUE_MAX
        )
    }

    private fun ready(prefs: SharedPreferences): Boolean =
        FramegenInterceptor.isAvailable() && FramegenSettings.isReadyForUser(prefs)

    private fun regularEnabled(prefs: SharedPreferences, inputFps: Int): Boolean =
        ready(prefs) && regularRequested(prefs, inputFps)

    private fun regularRequested(prefs: SharedPreferences, inputFps: Int): Boolean =
        FramegenSettings.isUserEnabled(prefs) && inputFps in 1..MAX_DOUBLING_INPUT_FPS
}
