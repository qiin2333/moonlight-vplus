package com.limelight.binding.audio;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.VibrationAttributes;
import android.view.InputDevice;

import androidx.annotation.RequiresApi;

import com.limelight.LimeLog;
import com.limelight.binding.input.ControllerHandler;

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
public class AudioVibrationService {

    // Scene modes (must match BassEnergyAnalyzer SCENE_* constants)
    public static final int SCENE_GAME = 0;
    public static final int SCENE_MUSIC = 1;
    public static final int SCENE_AUTO = 2;

    // Vibration routing modes
    public static final String MODE_AUTO = "auto";
    public static final String MODE_DEVICE_ONLY = "device";
    public static final String MODE_GAMEPAD_ONLY = "gamepad";
    public static final String MODE_BOTH = "both";

    // Haptic capability levels
    private static final int HAPTIC_LEGACY = 0;
    private static final int HAPTIC_ONE_SHOT = 1;
    private static final int HAPTIC_COMPOSITION = 2;
    private static final int HAPTIC_ENVELOPE = 3;

    private boolean enabled = false;
    private int strength = 100;       // 0-100
    private String vibrationMode = MODE_AUTO;
    private int sceneMode = SCENE_GAME;

    // State
    private int lastIntensity = 0;
    private int lastLowFreqRatio = 50;
    private boolean isDeviceVibrating = false;
    private boolean isGamepadRumbling = false;

    // Debounce: tightened intervals matching HarmonyOS
    private long lastVibrationTime = 0;
    private static final long MIN_INTERVAL_GAME_MS = 25;
    private static final long MIN_INTERVAL_MUSIC_MS = 15;

    // Android vibrator & capability
    private final Vibrator deviceVibrator;
    private final int hapticLevel;

    // Gamepad rumble handler (optional, set externally)
    private ControllerHandler controllerHandler;

    public AudioVibrationService(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            deviceVibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            deviceVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        hapticLevel = detectHapticCapability();
        LimeLog.info("AudioVibration: haptic level = " + hapticLevelName());
    }

    private int detectHapticCapability() {
        if (deviceVibrator == null || !deviceVibrator.hasVibrator()) {
            return HAPTIC_LEGACY;
        }

        // Tier 3: API 36+ BasicEnvelopeBuilder
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                if (deviceVibrator.areEnvelopeEffectsSupported()) {
                    return HAPTIC_ENVELOPE;
                }
            } catch (Exception ignored) {}
        }

        // Tier 2: API 31+ Composition with THUD & CLICK primitives
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                boolean[] supported = deviceVibrator.arePrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_THUD,
                        VibrationEffect.Composition.PRIMITIVE_CLICK);
                if (supported[0] && supported[1]) {
                    return HAPTIC_COMPOSITION;
                }
            } catch (Exception ignored) {}
        }

        // Tier 1: API 26+ createOneShot with amplitude control
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && deviceVibrator.hasAmplitudeControl()) {
            return HAPTIC_ONE_SHOT;
        }

        return HAPTIC_LEGACY;
    }

    private String hapticLevelName() {
        switch (hapticLevel) {
            case HAPTIC_ENVELOPE: return "ENVELOPE (API 36+)";
            case HAPTIC_COMPOSITION: return "COMPOSITION (API 31+)";
            case HAPTIC_ONE_SHOT: return "ONE_SHOT (API 26+)";
            default: return "LEGACY";
        }
    }

    public void setControllerHandler(ControllerHandler handler) {
        this.controllerHandler = handler;
    }

    public void setSettings(boolean enabled, int strength, String vibrationMode, int sceneMode) {
        this.enabled = enabled;
        this.strength = Math.max(0, Math.min(100, strength));
        this.vibrationMode = vibrationMode;
        this.sceneMode = sceneMode;

        if (!enabled) {
            stopAll();
        }
    }

    public int getSceneModeInt() {
        return sceneMode;
    }

    /**
     * Handle bass energy from native layer.
     *
     * @param intensity Bass energy intensity (0-100)
     * @param lowFreqRatio Low-frequency energy ratio (0-100), for motor allocation
     */
    public void handleBassEnergy(int intensity, int lowFreqRatio) {
        if (!enabled) return;

        if (intensity == 0) {
            if (isDeviceVibrating || isGamepadRumbling) {
                stopAll();
            }
            return;
        }

        int effectiveIntensity = intensity * strength / 100;
        if (effectiveIntensity < 5) {
            if (isDeviceVibrating || isGamepadRumbling) {
                stopAll();
            }
            return;
        }

        // Debounce
        long now = System.currentTimeMillis();
        long minInterval = isMusicScene() ? MIN_INTERVAL_MUSIC_MS : MIN_INTERVAL_GAME_MS;
        if (now - lastVibrationTime < minInterval) {
            return;
        }

        // Skip if change too small
        int changeTolerance = isMusicScene() ? 3 : 8;
        if ((isDeviceVibrating || isGamepadRumbling) &&
                Math.abs(effectiveIntensity - lastIntensity) < changeTolerance) {
            return;
        }

        lastIntensity = effectiveIntensity;
        lastLowFreqRatio = lowFreqRatio;
        lastVibrationTime = now;

        // Route vibration
        boolean shouldDevice = shouldVibrateDevice();
        boolean shouldGamepad = shouldVibrateGamepad();

        if (shouldDevice) {
            triggerDeviceVibration(effectiveIntensity);
        } else if (isDeviceVibrating) {
            stopDeviceVibration();
        }

        if (shouldGamepad) {
            triggerGamepadRumble(effectiveIntensity, lowFreqRatio);
        } else if (isGamepadRumbling) {
            stopGamepadRumble();
        }
    }

    public void stop() {
        stopAll();
    }

    // ==================== Scene detection ====================

    private boolean isMusicScene() {
        return sceneMode == SCENE_MUSIC || sceneMode == SCENE_AUTO;
    }

    // ==================== Routing ====================

    private boolean shouldVibrateDevice() {
        switch (vibrationMode) {
            case MODE_GAMEPAD_ONLY: return false;
            case MODE_DEVICE_ONLY: return true;
            case MODE_BOTH: return true;
            case MODE_AUTO:
            default:
                return !hasConnectedGamepad();
        }
    }

    private boolean shouldVibrateGamepad() {
        switch (vibrationMode) {
            case MODE_GAMEPAD_ONLY: return true;
            case MODE_DEVICE_ONLY: return false;
            case MODE_BOTH: return true;
            case MODE_AUTO:
            default:
                return hasConnectedGamepad();
        }
    }

    private boolean hasConnectedGamepad() {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int id : deviceIds) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null && (dev.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                return true;
            }
        }
        return false;
    }

    // ==================== Device Vibration ====================

    private void triggerDeviceVibration(int intensity) {
        if (deviceVibrator == null || !deviceVibrator.hasVibrator()) {
            return;
        }

        if (isDeviceVibrating) {
            deviceVibrator.cancel();
        }

        try {
            if (isMusicScene()) {
                triggerMusicVibration(intensity);
            } else {
                triggerGameVibration(intensity);
            }
            isDeviceVibrating = true;
        } catch (Exception e) {
            LimeLog.warning("AudioVibration: " + e.getMessage());
        }
    }

    // ==================== Game mode vibration (tiered) ====================

    private void triggerGameVibration(int intensity) {
        switch (hapticLevel) {
            case HAPTIC_ENVELOPE:
                if (Build.VERSION.SDK_INT >= 36) {
                    triggerGameEnvelope(intensity);
                }
                break;
            case HAPTIC_COMPOSITION:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    triggerGameComposition(intensity);
                }
                break;
            case HAPTIC_ONE_SHOT:
                triggerGameOneShot(intensity);
                break;
            default:
                vibrateSimple(50 + intensity * 250 / 100);
        }
    }

    /**
     * Game envelope: deep sustained rumble (≈300ms).
     * Sharpness 0.1-0.3 → low frequency, equivalent to HarmonyOS HD Haptic 30-50Hz.
     */
    @RequiresApi(36)
    private void triggerGameEnvelope(int intensity) {
        float amp = intensity / 100f;
        float sharpness = 0.1f + amp * 0.2f; // 0.1-0.3: deep rumble
        try {
            VibrationEffect effect = new VibrationEffect.BasicEnvelopeBuilder()
                    .setInitialSharpness(sharpness)
                    .addControlPoint(amp, sharpness, 20)          // attack: 20ms ramp up
                    .addControlPoint(amp * 0.6f, sharpness, 200)  // sustain+decay: 200ms
                    .addControlPoint(0f, sharpness, 80)           // release: 80ms fade out
                    .build();
            vibrateWithAttributes(effect);
        } catch (Exception e) {
            triggerGameOneShot(intensity);
        }
    }

    /**
     * Game composition: PRIMITIVE_THUD for heavy impact feel.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private void triggerGameComposition(int intensity) {
        float scale = Math.max(0.1f, intensity / 100f);
        try {
            VibrationEffect effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
                    .compose();
            vibrateWithAttributes(effect);
        } catch (Exception e) {
            triggerGameOneShot(intensity);
        }
    }

    /**
     * Game one-shot: 50-300ms with amplitude control.
     */
    private void triggerGameOneShot(int intensity) {
        int duration = 50 + intensity * 250 / 100;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int amplitude = Math.max(1, intensity * 255 / 100);
            VibrationEffect effect = VibrationEffect.createOneShot(duration, amplitude);
            vibrateWithAttributes(effect);
        } else {
            vibrateSimple(duration);
        }
    }

    // ==================== Music mode vibration (tiered) ====================

    private void triggerMusicVibration(int intensity) {
        switch (hapticLevel) {
            case HAPTIC_ENVELOPE:
                if (Build.VERSION.SDK_INT >= 36) {
                    triggerMusicEnvelope(intensity);
                }
                break;
            case HAPTIC_COMPOSITION:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    triggerMusicComposition(intensity);
                }
                break;
            case HAPTIC_ONE_SHOT:
                triggerMusicOneShot(intensity);
                break;
            default:
                vibrateSimple(30 + intensity / 2);
        }
    }

    /**
     * Music envelope: sharp transient pulse (≈60ms).
     * Sharpness 0.4-0.7 → crisp/snappy, equivalent to HarmonyOS HD Haptic 40-60Hz.
     */
    @RequiresApi(36)
    private void triggerMusicEnvelope(int intensity) {
        float amp = intensity / 100f;
        float sharpness = 0.4f + amp * 0.3f; // 0.4-0.7: crisp beat
        try {
            VibrationEffect effect = new VibrationEffect.BasicEnvelopeBuilder()
                    .setInitialSharpness(sharpness)
                    .addControlPoint(amp, sharpness, 5)    // instant attack: 5ms
                    .addControlPoint(0f, sharpness, 55)    // quick decay: 55ms
                    .build();
            vibrateWithAttributes(effect);
        } catch (Exception e) {
            triggerMusicOneShot(intensity);
        }
    }

    /**
     * Music composition: PRIMITIVE_CLICK for crisp beat.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private void triggerMusicComposition(int intensity) {
        float scale = Math.max(0.1f, intensity / 100f);
        try {
            VibrationEffect effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                    .compose();
            vibrateWithAttributes(effect);
        } catch (Exception e) {
            triggerMusicOneShot(intensity);
        }
    }

    /**
     * Music one-shot: 30-80ms with amplitude control.
     */
    private void triggerMusicOneShot(int intensity) {
        int duration = 30 + intensity / 2;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int amplitude = Math.max(1, intensity * 255 / 100);
            VibrationEffect effect = VibrationEffect.createOneShot(duration, amplitude);
            vibrateWithAttributes(effect);
        } else {
            vibrateSimple(duration);
        }
    }

    // ==================== Vibration helpers ====================

    private void vibrateWithAttributes(VibrationEffect effect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes attrs = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build();
            deviceVibrator.vibrate(effect, attrs);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            deviceVibrator.vibrate(effect);
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrateSimple(int durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            deviceVibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            deviceVibrator.vibrate(durationMs);
        }
    }

    // ==================== Gamepad Rumble ====================

    /**
     * Gamepad rumble with dynamic low/high motor allocation.
     * lowFreqRatio from C++ reflects actual audio frequency content:
     * - High ratio → explosion/bass → low-freq motor dominant
     * - Low ratio → crisp/high-pitched → high-freq motor dominant
     */
    private void triggerGamepadRumble(int intensity, int lowFreqRatio) {
        if (controllerHandler == null) return;

        int base = intensity * 65535 / 100;
        // Dynamic allocation: at least 15% per motor, matching HarmonyOS
        float lowWeight = Math.max(0.15f, Math.min(0.85f, lowFreqRatio / 100f));
        float highWeight = 1.0f - lowWeight;

        short lowFreq = (short)(base * lowWeight);
        short highFreq = (short)(base * highWeight);

        controllerHandler.handleRumble((short)0, lowFreq, highFreq);
        isGamepadRumbling = true;
    }

    private void stopGamepadRumble() {
        if (controllerHandler != null) {
            controllerHandler.handleRumble((short)0, (short)0, (short)0);
        }
        isGamepadRumbling = false;
    }

    // ==================== Stop ====================

    private void stopAll() {
        if (isDeviceVibrating) {
            stopDeviceVibration();
        }
        if (isGamepadRumbling) {
            stopGamepadRumble();
        }
        lastIntensity = 0;
    }

    private void stopDeviceVibration() {
        if (deviceVibrator != null) {
            deviceVibrator.cancel();
        }
        isDeviceVibrating = false;
    }
}
