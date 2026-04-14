package com.limelight.binding.audio

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice

import androidx.annotation.RequiresApi

import com.limelight.LimeLog
import com.limelight.binding.input.ControllerHandler

/**
 * Audio-driven vibration service for Android.
 *
 * Receives bass energy intensity (0-100) and low-frequency ratio (0-100) from the
 * native BassEnergyAnalyzer (via JNI callback) and routes vibration to:
 *   - Device vibrator with tiered haptic API support
 *   - Gamepad rumble with dynamic low/high motor allocation
 *
 * Haptic capability tiers (auto-detected):
 *   - ENVELOPE (API 36+): BasicEnvelopeBuilder — intensity + sharpness + duration envelope
 *   - COMPOSITION (API 31+): Primitives — THUD/CLICK with scale control
 *   - ONE_SHOT (API 26+): createOneShot — duration + amplitude
 *   - LEGACY (pre-26): simple vibrate(ms)
 *
 * Scene modes:
 *   - Game/Movie (0): Continuous low-freq vibration for explosions/gunfire/engines
 *   - Music/Rhythm (1): Short pulse vibration for beats/onsets
 *   - Auto (2): C++ layer auto-detects content type
 */
class AudioVibrationService(context: Context) {

    private var enabled = false
    private var strength = 100       // 0-100
    private var vibrationMode = MODE_AUTO
    private var sceneMode = SCENE_GAME

    // State
    private var lastIntensity = 0
    private var lastLowFreqRatio = 50
    private var isDeviceVibrating = false
    private var isGamepadRumbling = false

    // Debounce: tightened intervals matching HarmonyOS
    private var lastVibrationTime: Long = 0

    // Android vibrator & capability
    private val deviceVibrator: Vibrator?
    private val hapticLevel: Int

    // Gamepad rumble handler (optional, set externally)
    var controllerHandler: ControllerHandler? = null

    init {
        deviceVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        hapticLevel = detectHapticCapability()
        LimeLog.info("AudioVibration: haptic level = " + hapticLevelName())
    }

    private fun detectHapticCapability(): Int {
        if (deviceVibrator == null || !deviceVibrator.hasVibrator()) {
            return HAPTIC_LEGACY
        }

        // Tier 3: API 36+ BasicEnvelopeBuilder
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                if (deviceVibrator.areEnvelopeEffectsSupported()) {
                    return HAPTIC_ENVELOPE
                }
            } catch (_: Exception) {}
        }

        // Tier 2: API 31+ Composition with THUD & CLICK primitives
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val supported = deviceVibrator.arePrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_CLICK
                )
                if (supported[0] && supported[1]) {
                    return HAPTIC_COMPOSITION
                }
            } catch (_: Exception) {}
        }

        // Tier 1: API 26+ createOneShot with amplitude control
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && deviceVibrator.hasAmplitudeControl()) {
            return HAPTIC_ONE_SHOT
        }

        return HAPTIC_LEGACY
    }

    private fun hapticLevelName(): String = when (hapticLevel) {
        HAPTIC_ENVELOPE -> "ENVELOPE (API 36+)"
        HAPTIC_COMPOSITION -> "COMPOSITION (API 31+)"
        HAPTIC_ONE_SHOT -> "ONE_SHOT (API 26+)"
        else -> "LEGACY"
    }

    fun setSettings(enabled: Boolean, strength: Int, vibrationMode: String, sceneMode: Int) {
        this.enabled = enabled
        this.strength = strength.coerceIn(0, 100)
        this.vibrationMode = vibrationMode
        this.sceneMode = sceneMode

        if (!enabled) {
            stopAll()
        }
    }

    val sceneModeInt: Int
        get() = sceneMode

    /**
     * Handle bass energy from native layer.
     *
     * @param intensity Bass energy intensity (0-100)
     * @param lowFreqRatio Low-frequency energy ratio (0-100), for motor allocation
     */
    fun handleBassEnergy(intensity: Int, lowFreqRatio: Int) {
        if (!enabled) return

        if (intensity == 0) {
            if (isDeviceVibrating || isGamepadRumbling) {
                stopAll()
            }
            return
        }

        val effectiveIntensity = intensity * strength / 100
        if (effectiveIntensity < 5) {
            if (isDeviceVibrating || isGamepadRumbling) {
                stopAll()
            }
            return
        }

        // Debounce
        val now = System.currentTimeMillis()
        val minInterval = if (isMusicScene()) MIN_INTERVAL_MUSIC_MS else MIN_INTERVAL_GAME_MS
        if (now - lastVibrationTime < minInterval) {
            return
        }

        // Skip if change too small
        val changeTolerance = if (isMusicScene()) 3 else 8
        if ((isDeviceVibrating || isGamepadRumbling) &&
            Math.abs(effectiveIntensity - lastIntensity) < changeTolerance
        ) {
            return
        }

        lastIntensity = effectiveIntensity
        lastLowFreqRatio = lowFreqRatio
        lastVibrationTime = now

        // Route vibration
        val shouldDevice = shouldVibrateDevice()
        val shouldGamepad = shouldVibrateGamepad()

        if (shouldDevice) {
            triggerDeviceVibration(effectiveIntensity)
        } else if (isDeviceVibrating) {
            stopDeviceVibration()
        }

        if (shouldGamepad) {
            triggerGamepadRumble(effectiveIntensity, lowFreqRatio)
        } else if (isGamepadRumbling) {
            stopGamepadRumble()
        }
    }

    fun stop() {
        stopAll()
    }

    // ==================== Scene detection ====================

    private fun isMusicScene(): Boolean {
        return sceneMode == SCENE_MUSIC || sceneMode == SCENE_AUTO
    }

    // ==================== Routing ====================

    private fun shouldVibrateDevice(): Boolean = when (vibrationMode) {
        MODE_GAMEPAD_ONLY -> false
        MODE_DEVICE_ONLY -> true
        MODE_BOTH -> true
        else -> !hasConnectedGamepad()
    }

    private fun shouldVibrateGamepad(): Boolean = when (vibrationMode) {
        MODE_GAMEPAD_ONLY -> true
        MODE_DEVICE_ONLY -> false
        MODE_BOTH -> true
        else -> hasConnectedGamepad()
    }

    private fun hasConnectedGamepad(): Boolean {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val dev = InputDevice.getDevice(id)
            if (dev != null && (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                return true
            }
        }
        return false
    }

    // ==================== Device Vibration ====================

    private fun triggerDeviceVibration(intensity: Int) {
        if (deviceVibrator == null || !deviceVibrator.hasVibrator()) {
            return
        }

        if (isDeviceVibrating) {
            deviceVibrator.cancel()
        }

        try {
            if (isMusicScene()) {
                triggerMusicVibration(intensity)
            } else {
                triggerGameVibration(intensity)
            }
            isDeviceVibrating = true
        } catch (e: Exception) {
            LimeLog.warning("AudioVibration: " + e.message)
        }
    }

    // ==================== Game mode vibration (tiered) ====================

    private fun triggerGameVibration(intensity: Int) {
        when (hapticLevel) {
            HAPTIC_ENVELOPE -> if (Build.VERSION.SDK_INT >= 36) {
                triggerGameEnvelope(intensity)
            }
            HAPTIC_COMPOSITION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                triggerGameComposition(intensity)
            }
            HAPTIC_ONE_SHOT -> triggerGameOneShot(intensity)
            else -> vibrateSimple(50 + intensity * 250 / 100)
        }
    }

    /**
     * Game envelope: deep sustained rumble (≈300ms).
     * Sharpness 0.1-0.3 → low frequency, equivalent to HarmonyOS HD Haptic 30-50Hz.
     */
    @RequiresApi(36)
    private fun triggerGameEnvelope(intensity: Int) {
        val amp = intensity / 100f
        val sharpness = 0.1f + amp * 0.2f // 0.1-0.3: deep rumble
        try {
            val effect = VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(sharpness)
                .addControlPoint(amp, sharpness, 20)          // attack: 20ms ramp up
                .addControlPoint(amp * 0.6f, sharpness, 200)  // sustain+decay: 200ms
                .addControlPoint(0f, sharpness, 80)           // release: 80ms fade out
                .build()
            vibrateWithAttributes(effect)
        } catch (_: Exception) {
            triggerGameOneShot(intensity)
        }
    }

    /**
     * Game composition: PRIMITIVE_THUD for heavy impact feel.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun triggerGameComposition(intensity: Int) {
        val scale = (intensity / 100f).coerceAtLeast(0.1f)
        try {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
                .compose()
            vibrateWithAttributes(effect)
        } catch (_: Exception) {
            triggerGameOneShot(intensity)
        }
    }

    /**
     * Game one-shot: 50-300ms with amplitude control.
     */
    private fun triggerGameOneShot(intensity: Int) {
        val duration = 50 + intensity * 250 / 100
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = (intensity * 255 / 100).coerceAtLeast(1)
            val effect = VibrationEffect.createOneShot(duration.toLong(), amplitude)
            vibrateWithAttributes(effect)
        } else {
            vibrateSimple(duration)
        }
    }

    // ==================== Music mode vibration (tiered) ====================

    private fun triggerMusicVibration(intensity: Int) {
        when (hapticLevel) {
            HAPTIC_ENVELOPE -> if (Build.VERSION.SDK_INT >= 36) {
                triggerMusicEnvelope(intensity)
            }
            HAPTIC_COMPOSITION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                triggerMusicComposition(intensity)
            }
            HAPTIC_ONE_SHOT -> triggerMusicOneShot(intensity)
            else -> vibrateSimple(30 + intensity / 2)
        }
    }

    /**
     * Music envelope: sharp transient pulse (≈60ms).
     * Sharpness 0.4-0.7 → crisp/snappy, equivalent to HarmonyOS HD Haptic 40-60Hz.
     */
    @RequiresApi(36)
    private fun triggerMusicEnvelope(intensity: Int) {
        val amp = intensity / 100f
        val sharpness = 0.4f + amp * 0.3f // 0.4-0.7: crisp beat
        try {
            val effect = VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(sharpness)
                .addControlPoint(amp, sharpness, 5)    // instant attack: 5ms
                .addControlPoint(0f, sharpness, 55)    // quick decay: 55ms
                .build()
            vibrateWithAttributes(effect)
        } catch (_: Exception) {
            triggerMusicOneShot(intensity)
        }
    }

    /**
     * Music composition: PRIMITIVE_CLICK for crisp beat.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun triggerMusicComposition(intensity: Int) {
        val scale = (intensity / 100f).coerceAtLeast(0.1f)
        try {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                .compose()
            vibrateWithAttributes(effect)
        } catch (_: Exception) {
            triggerMusicOneShot(intensity)
        }
    }

    /**
     * Music one-shot: 30-80ms with amplitude control.
     */
    private fun triggerMusicOneShot(intensity: Int) {
        val duration = 30 + intensity / 2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = (intensity * 255 / 100).coerceAtLeast(1)
            val effect = VibrationEffect.createOneShot(duration.toLong(), amplitude)
            vibrateWithAttributes(effect)
        } else {
            vibrateSimple(duration)
        }
    }

    // ==================== Vibration helpers ====================

    private fun vibrateWithAttributes(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_MEDIA)
                .build()
            deviceVibrator!!.vibrate(effect, attrs)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            deviceVibrator!!.vibrate(effect)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateSimple(durationMs: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            deviceVibrator!!.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            deviceVibrator!!.vibrate(durationMs.toLong())
        }
    }

    // ==================== Gamepad Rumble ====================

    /**
     * Gamepad rumble with dynamic low/high motor allocation.
     * lowFreqRatio from C++ reflects actual audio frequency content:
     * - High ratio → explosion/bass → low-freq motor dominant
     * - Low ratio → crisp/high-pitched → high-freq motor dominant
     */
    private fun triggerGamepadRumble(intensity: Int, lowFreqRatio: Int) {
        val handler = controllerHandler ?: return

        val base = intensity * 65535 / 100
        // Dynamic allocation: at least 15% per motor, matching HarmonyOS
        val lowWeight = (lowFreqRatio / 100f).coerceIn(0.15f, 0.85f)
        val highWeight = 1.0f - lowWeight

        val lowFreq = (base * lowWeight).toInt().toShort()
        val highFreq = (base * highWeight).toInt().toShort()

        handler.handleRumble(0.toShort(), lowFreq, highFreq)
        isGamepadRumbling = true
    }

    private fun stopGamepadRumble() {
        controllerHandler?.handleRumble(0.toShort(), 0.toShort(), 0.toShort())
        isGamepadRumbling = false
    }

    // ==================== Stop ====================

    private fun stopAll() {
        if (isDeviceVibrating) {
            stopDeviceVibration()
        }
        if (isGamepadRumbling) {
            stopGamepadRumble()
        }
        lastIntensity = 0
    }

    private fun stopDeviceVibration() {
        deviceVibrator?.cancel()
        isDeviceVibrating = false
    }

    companion object {
        // Scene modes (must match BassEnergyAnalyzer SCENE_* constants)
        const val SCENE_GAME = 0
        const val SCENE_MUSIC = 1
        const val SCENE_AUTO = 2

        // Vibration routing modes
        const val MODE_AUTO = "auto"
        const val MODE_DEVICE_ONLY = "device"
        const val MODE_GAMEPAD_ONLY = "gamepad"
        const val MODE_BOTH = "both"

        // Haptic capability levels
        private const val HAPTIC_LEGACY = 0
        private const val HAPTIC_ONE_SHOT = 1
        private const val HAPTIC_COMPOSITION = 2
        private const val HAPTIC_ENVELOPE = 3

        private const val MIN_INTERVAL_GAME_MS: Long = 25
        private const val MIN_INTERVAL_MUSIC_MS: Long = 15
    }
}
