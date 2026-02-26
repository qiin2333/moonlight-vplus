package com.limelight.binding.audio;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.CombinedVibration;
import android.os.VibrationAttributes;
import android.view.InputDevice;

import com.limelight.LimeLog;
import com.limelight.binding.input.ControllerHandler;

/**
 * Audio-driven vibration service for Android.
 *
 * Receives bass energy intensity (0-100) from the native BassEnergyAnalyzer
 * (via JNI callback) and routes vibration to:
 *   - Device vibrator (phone/tablet built-in motor)
 *   - Gamepad rumble (via ControllerHandler)
 *
 * Ported from HarmonyOS AudioVibrationService.ets.
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

    private boolean enabled = false;
    private int strength = 100;       // 0-100
    private String vibrationMode = MODE_AUTO;
    private int sceneMode = SCENE_GAME;

    // State
    private int lastIntensity = 0;
    private boolean isDeviceVibrating = false;
    private boolean isGamepadRumbling = false;

    // Debounce: minimum interval between vibration API calls
    private long lastVibrationTime = 0;
    private static final long MIN_VIBRATION_INTERVAL_MS = 40;
    private static final long MIN_VIBRATION_INTERVAL_MUSIC_MS = 25;

    // Android vibrator
    private final Vibrator deviceVibrator;
    private final boolean hasAmplitudeControl;

    // Gamepad rumble handler (optional, set externally)
    private ControllerHandler controllerHandler;

    public AudioVibrationService(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            deviceVibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            deviceVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        hasAmplitudeControl = deviceVibrator != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                deviceVibrator.hasAmplitudeControl();
    }

    /**
     * Set the controller handler for gamepad rumble routing.
     */
    public void setControllerHandler(ControllerHandler handler) {
        this.controllerHandler = handler;
    }

    /**
     * Update settings.
     */
    public void setSettings(boolean enabled, int strength, String vibrationMode, int sceneMode) {
        this.enabled = enabled;
        this.strength = Math.max(0, Math.min(100, strength));
        this.vibrationMode = vibrationMode;
        this.sceneMode = sceneMode;

        if (!enabled) {
            stopAll();
        }
    }

    /**
     * Get scene mode as integer for native layer.
     */
    public int getSceneModeInt() {
        return sceneMode;
    }

    /**
     * Handle bass energy intensity from native layer.
     * Called from MoonBridge.bridgeBassEnergy() on the audio decode thread.
     *
     * @param intensity Bass energy intensity (0-100)
     */
    public void handleBassEnergy(int intensity) {
        if (!enabled) return;

        // Zero intensity → stop vibration
        if (intensity == 0) {
            if (isDeviceVibrating || isGamepadRumbling) {
                stopAll();
            }
            return;
        }

        // Apply user strength factor
        int effectiveIntensity = intensity * strength / 100;
        if (effectiveIntensity < 5) {
            if (isDeviceVibrating || isGamepadRumbling) {
                stopAll();
            }
            return;
        }

        // Debounce check
        long now = System.currentTimeMillis();
        long minInterval = isMusicScene() ? MIN_VIBRATION_INTERVAL_MUSIC_MS : MIN_VIBRATION_INTERVAL_MS;
        if (now - lastVibrationTime < minInterval) {
            return;
        }

        // Skip if intensity change is too small
        int changeTolerance = isMusicScene() ? 3 : 8;
        if ((isDeviceVibrating || isGamepadRumbling) &&
                Math.abs(effectiveIntensity - lastIntensity) < changeTolerance) {
            return;
        }

        lastIntensity = effectiveIntensity;
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
            triggerGamepadRumble(effectiveIntensity);
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

        // Stop current vibration first
        if (isDeviceVibrating) {
            deviceVibrator.cancel();
        }

        try {
            if (sceneMode == SCENE_MUSIC) {
                triggerMusicVibration(intensity);
            } else {
                triggerGameVibration(intensity);
            }
            isDeviceVibrating = true;
        } catch (Exception e) {
            LimeLog.warning("AudioVibration: " + e.getMessage());
        }
    }

    /**
     * Game/Movie mode: longer, deeper vibration (50-300ms)
     */
    private void triggerGameVibration(int intensity) {
        int duration = 50 + intensity * 250 / 100; // 50-300ms

        if (hasAmplitudeControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Amplitude-controlled vibration: 1-255 mapped from intensity 0-100
            int amplitude = Math.max(1, intensity * 255 / 100);
            VibrationEffect effect = VibrationEffect.createOneShot(duration, amplitude);
            vibrateWithAttributes(effect);
        } else {
            // Fallback: simple on/off vibration
            vibrateSimple(duration);
        }
    }

    /**
     * Music/Rhythm mode: short pulse vibration (30-80ms)
     */
    private void triggerMusicVibration(int intensity) {
        int duration = 30 + intensity / 2; // 30-80ms

        if (hasAmplitudeControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int amplitude = Math.max(1, intensity * 255 / 100);
            VibrationEffect effect = VibrationEffect.createOneShot(duration, amplitude);
            vibrateWithAttributes(effect);
        } else {
            vibrateSimple(duration);
        }
    }

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
     * Gamepad rumble with scene-aware low/high freq balance.
     * Game mode: lowFreq dominant (deep rumble)
     * Music mode: highFreq higher (60%), snappy feel
     */
    private void triggerGamepadRumble(int intensity) {
        if (controllerHandler == null) return;

        int base = intensity * 65535 / 100;
        short lowFreq, highFreq;

        if (sceneMode == SCENE_MUSIC) {
            lowFreq = (short)(base * 0.4);
            highFreq = (short)(base * 0.6);
        } else {
            lowFreq = (short)base;
            highFreq = (short)(base * 0.25);
        }

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
