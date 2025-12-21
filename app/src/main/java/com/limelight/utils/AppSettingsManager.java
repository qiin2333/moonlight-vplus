package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.limelight.R;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * App Settings Manager
 * Used to store and retrieve the last streaming settings for each app
 */
public class AppSettingsManager {

    private static final String PREF_FILE_NAME = "app_last_settings";
    private static final String KEY_USE_LAST_SETTINGS = "use_last_settings";
    
    // Intent constants
    private static final String INTENT_USE_LAST_SETTINGS = "UseLastSettings";
    private static final String INTENT_LAST_SETTINGS_WIDTH = "LastSettingsWidth";
    private static final String INTENT_LAST_SETTINGS_HEIGHT = "LastSettingsHeight";
    private static final String INTENT_LAST_SETTINGS_FPS = "LastSettingsFps";
    private static final String INTENT_LAST_SETTINGS_BITRATE = "LastSettingsBitrate";
    private static final String INTENT_LAST_SETTINGS_RESOLUTION_SCALE = "LastSettingsResolutionScale";
    private static final String INTENT_LAST_SETTINGS_VIDEO_FORMAT = "LastSettingsVideoFormat";
    private static final String INTENT_LAST_SETTINGS_ENABLE_HDR = "LastSettingsEnableHdr";
    private static final String INTENT_LAST_SETTINGS_ENABLE_MIC = "LastSettingsEnableMic";
    private static final String INTENT_LAST_SETTINGS_MIC_BITRATE = "LastSettingsMicBitrate";
    private static final String INTENT_LAST_SETTINGS_ENABLE_NATIVE_MOUSE = "LastSettingsEnableNativeMouse";
    private static final String INTENT_LAST_SETTINGS_GYRO_SENSITIVITY = "LastSettingsGyroSensitivity";
    private static final String INTENT_LAST_SETTINGS_GYRO_INVERT_X = "LastSettingsGyroInvertX";
    private static final String INTENT_LAST_SETTINGS_GYRO_INVERT_Y = "LastSettingsGyroInvertY";
    private static final String INTENT_LAST_SETTINGS_GYRO_ACTIVATION_KEY = "LastSettingsGyroActivationKey";
    private static final String INTENT_LAST_SETTINGS_SHOW_BITRATE_CARD = "LastSettingsShowBitrateCard";
    private static final String INTENT_LAST_SETTINGS_SHOW_GYRO_CARD = "LastSettingsShowGyroCard";
    private static final String INTENT_LAST_SETTINGS_SHOW_QuickKeyCard = "LastSettingsshowQuickKeyCard";

    private final Context context;
    private final SharedPreferences preferences;

    public AppSettingsManager(Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Save the last settings for an app
     *
     * @param computerUuid Computer UUID
     * @param app          App object
     * @param settings     Settings configuration
     */
    public void saveAppLastSettings(String computerUuid, NvApp app, PreferenceConfiguration settings) {
        if (app == null || settings == null) {
            return;
        }

        String key = generateKey(computerUuid, app.getAppId());

        try {
            JSONObject settingsJson = settingsToJson(settings);
            preferences.edit()
                    .putString(key, settingsJson.toString())
                    .commit(); // Use commit to ensure synchronous save

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieve the last settings for an app
     *
     * @param computerUuid Computer UUID
     * @param app          App object
     * @return Last settings configuration, or null if none exists
     */
    public PreferenceConfiguration getAppLastSettings(String computerUuid, NvApp app) {
        if (app == null) {
            return null;
        }

        String key = generateKey(computerUuid, app.getAppId());
        String settingsJsonString = preferences.getString(key, null);

        if (settingsJsonString == null) {
            return null;
        }

        try {
            JSONObject settingsJson = new JSONObject(settingsJsonString);
            return jsonToSettings(settingsJson);

        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get whether the "use last settings" toggle is enabled
     *
     * @return true if use last settings is enabled, false otherwise
     */
    public boolean isUseLastSettingsEnabled() {
        return preferences.getBoolean(KEY_USE_LAST_SETTINGS, false);
    }

    /**
     * Set whether the "use last settings" toggle is enabled
     *
     * @param enabled true to enable use last settings, false to disable
     */
    public void setUseLastSettingsEnabled(boolean enabled) {
        preferences.edit()
                .putBoolean(KEY_USE_LAST_SETTINGS, enabled)
                .apply();
    }

    /**
     * Get the timestamp of the last settings for an app
     *
     * @param computerUuid Computer UUID
     * @param app          App object
     * @return Timestamp in milliseconds
     */
    public long getAppLastSettingsTimestamp(String computerUuid, NvApp app) {
        if (app == null) {
            return 0;
        }

        String key = generateKey(computerUuid, app.getAppId());
        String settingsJsonString = preferences.getString(key, null);

        if (settingsJsonString == null) {
            return 0;
        }

        try {
            JSONObject settingsJson = new JSONObject(settingsJsonString);
            return settingsJson.optLong("timestamp", 0);
        } catch (JSONException e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Clear the last settings for an app
     *
     * @param computerUuid Computer UUID
     * @param app          App object
     */
    public void clearAppLastSettings(String computerUuid, NvApp app) {
        if (app == null) {
            return;
        }

        String key = generateKey(computerUuid, app.getAppId());
        preferences.edit()
                .remove(key)
                .apply();
    }

    /**
     * Clear all last settings for all apps
     */
    public void clearAllAppLastSettings() {
        preferences.edit()
                .clear()
                .apply();
    }

    /**
     * Generate storage key
     *
     * @param computerUuid Computer UUID
     * @param appId        App ID
     * @return Storage key
     */
    private String generateKey(String computerUuid, int appId) {
        return computerUuid + "_" + appId;
    }

    /**
     * Get settings summary information
     *
     * @param computerUuid Computer UUID
     * @param app          App object
     * @return Settings summary string
     */
    public String getSettingsSummary(String computerUuid, NvApp app) {
        PreferenceConfiguration settings = getAppLastSettings(computerUuid, app);
        if (settings == null) {
            return context.getString(R.string.app_last_settings_none);
        }

        long timestamp = getAppLastSettingsTimestamp(computerUuid, app);
        String timeStr = formatTimestamp(timestamp);

        // Build detailed settings information
        StringBuilder summary = new StringBuilder();

        // Basic video settings
        summary.append(context.getString(R.string.setting_resolution, settings.width, settings.height));
        summary.append(" | ").append(context.getString(R.string.setting_fps, settings.fps));
        summary.append(" | ").append(context.getString(R.string.setting_bitrate, settings.bitrate));

        // Resolution scale
        if (settings.resolutionScale != 100) {
            summary.append(" | ").append(context.getString(R.string.setting_scale, settings.resolutionScale));
        }

        // Video format
        String videoFormatStr = settings.videoFormat.toString();
        summary.append(" | ").append(context.getString(R.string.setting_format, videoFormatStr));

        // HDR settings
        if (settings.enableHdr) {
            summary.append(" | ").append(context.getString(R.string.setting_hdr_enabled));
        }

        // Microphone settings
        if (settings.enableMic) {
            summary.append(" | ").append(context.getString(R.string.setting_mic_enabled, settings.micBitrate));
        }

        // Native mouse pointer
        if (settings.enableNativeMousePointer) {
            summary.append(" | ").append(context.getString(R.string.setting_native_mouse_enabled));
        }

        // Card configuration
        if (settings.showBitrateCard) {
            summary.append(" | ").append(context.getString(R.string.setting_bitrate_card_enabled));
        }
        if (settings.showGyroCard) {
            summary.append(" | ").append(context.getString(R.string.setting_gyro_card_enabled));
        }
        if (settings.showQuickKeyCard) {
            summary.append(" | ").append(context.getString(R.string.setting_QuickKey_card_enabled));
        }

        // Add time information
        summary.append(" (").append(timeStr).append(")");

        return summary.toString();
    }

    /**
     * Format timestamp to readable string
     *
     * @param timestamp Timestamp in milliseconds
     * @return Formatted time string
     */
    private String formatTimestamp(long timestamp) {
        if (timestamp == 0) {
            return context.getString(R.string.time_unknown);
        }

        long now = System.currentTimeMillis();
        long diff = now - timestamp;

        if (diff < 60000) { // Within 1 minute
            return context.getString(R.string.time_just_now);
        } else if (diff < 3600000) { // Within 1 hour
            return context.getString(R.string.time_minutes_ago, diff / 60000);
        } else if (diff < 86400000) { // Within 1 day
            return context.getString(R.string.time_hours_ago, diff / 3600000);
        } else { // More than 1 day
            return context.getString(R.string.time_days_ago, diff / 86400000);
        }
    }

    /**
     * Get video format preference string
     * Copied from PreferenceConfiguration method
     */
    private String getVideoFormatPreferenceString(PreferenceConfiguration.FormatOption format) {
        switch (format) {
            case AUTO:
                return "auto";
            case FORCE_H264:
                return "h264";
            case FORCE_HEVC:
                return "hevc";
            case FORCE_AV1:
                return "av1";
            default:
                return "auto";
        }
    }
    
    /**
     * Parse video format string to enum
     * Unified video format parsing logic
     */
    private PreferenceConfiguration.FormatOption parseVideoFormat(String videoFormatStr) {
        if (videoFormatStr == null) {
            return PreferenceConfiguration.FormatOption.AUTO;
        }
        
        switch (videoFormatStr.toLowerCase()) {
            case "h264":
            case "force_h264":
                return PreferenceConfiguration.FormatOption.FORCE_H264;
            case "hevc":
            case "force_hevc":
                return PreferenceConfiguration.FormatOption.FORCE_HEVC;
            case "av1":
            case "force_av1":
                return PreferenceConfiguration.FormatOption.FORCE_AV1;
            case "auto":
            default:
                return PreferenceConfiguration.FormatOption.AUTO;
        }
    }
    
    /**
     * Convert PreferenceConfiguration to JSONObject
     */
    private JSONObject settingsToJson(PreferenceConfiguration settings) throws JSONException {
        JSONObject settingsJson = new JSONObject();
        settingsJson.put("resolution", settings.width + "x" + settings.height);
        settingsJson.put("fps", String.valueOf(settings.fps));
        settingsJson.put("bitrate", settings.bitrate);
        settingsJson.put("resolutionScale", settings.resolutionScale);
        settingsJson.put("videoFormat", getVideoFormatPreferenceString(settings.videoFormat));
        settingsJson.put("enableHdr", settings.enableHdr);
        settingsJson.put("enableMic", settings.enableMic);
        settingsJson.put("micBitrate", settings.micBitrate);
        settingsJson.put("enableNativeMousePointer", settings.enableNativeMousePointer);
        settingsJson.put("gyroSensitivityMultiplier", settings.gyroSensitivityMultiplier);
        settingsJson.put("gyroInvertXAxis", settings.gyroInvertXAxis);
        settingsJson.put("gyroInvertYAxis", settings.gyroInvertYAxis);
        settingsJson.put("gyroActivationKeyCode", settings.gyroActivationKeyCode);
        settingsJson.put("showBitrateCard", settings.showBitrateCard);
        settingsJson.put("showGyroCard", settings.showGyroCard);
        settingsJson.put("showQuickKeyCard", settings.showQuickKeyCard);
        settingsJson.put("timestamp", System.currentTimeMillis());
        return settingsJson;
    }
    
    /**
     * Create PreferenceConfiguration from JSONObject
     */
    private PreferenceConfiguration jsonToSettings(JSONObject settingsJson) throws JSONException {
        PreferenceConfiguration settings = new PreferenceConfiguration();
        
        // Initialize all necessary fields to avoid NullPointerException
        settings.screenPosition = PreferenceConfiguration.ScreenPosition.CENTER;
        settings.screenOffsetX = 0;
        settings.screenOffsetY = 0;
        settings.useExternalDisplay = false;
        settings.enablePerfOverlay = false;
        settings.reverseResolution = false;
        settings.rotableScreen = false;
        settings.showBitrateCard = false;
        settings.showGyroCard = false;
        settings.showQuickKeyCard = false;

        // Parse resolution string format "1920x1080"
        String resolutionStr = settingsJson.optString("resolution", "1920x1080");
        String[] resolutionParts = resolutionStr.split("x");
        if (resolutionParts.length == 2) {
            settings.width = Integer.parseInt(resolutionParts[0]);
            settings.height = Integer.parseInt(resolutionParts[1]);
        } else {
            settings.width = 1920;
            settings.height = 1080;
        }

        settings.fps = Integer.parseInt(settingsJson.optString("fps", "60"));
        settings.bitrate = settingsJson.optInt("bitrate", PreferenceConfiguration.getDefaultBitrate(context));
        settings.resolutionScale = settingsJson.optInt("resolutionScale", 100);
        settings.videoFormat = parseVideoFormat(settingsJson.optString("videoFormat", "auto"));

        settings.enableHdr = settingsJson.optBoolean("enableHdr", false);
        settings.enableMic = settingsJson.optBoolean("enableMic", false);
        settings.micBitrate = settingsJson.optInt("micBitrate", 96);
        settings.enableNativeMousePointer = settingsJson.optBoolean("enableNativeMousePointer", false);
        settings.gyroSensitivityMultiplier = (float) settingsJson.optDouble("gyroSensitivityMultiplier", 1.0f);
        settings.gyroInvertXAxis = settingsJson.optBoolean("gyroInvertXAxis", false);
        settings.gyroInvertYAxis = settingsJson.optBoolean("gyroInvertYAxis", false);
        settings.gyroActivationKeyCode = settingsJson.optInt("gyroActivationKeyCode", android.view.KeyEvent.KEYCODE_BUTTON_L2);
        settings.showBitrateCard = settingsJson.optBoolean("showBitrateCard", true);
        settings.showGyroCard = settingsJson.optBoolean("showGyroCard", true);
        settings.showQuickKeyCard = settingsJson.optBoolean("showQuickKeyCard", true);
        
        return settings;
    }
    
    /**
     * Create start Intent with last settings if enabled
     * 
     * @param parent Parent Activity
     * @param app App object
     * @param computer Computer details
     * @param managerBinder Computer manager binder
     * @return Start Intent, includes last settings parameters if "use last settings" is enabled and last settings exist
     */
    public Intent createStartIntentWithLastSettingsIfEnabled(Activity parent, NvApp app, 
                                                           ComputerDetails computer,
                                                           ComputerManagerService.ComputerManagerBinder managerBinder) {
        // Check if use last settings is enabled
        boolean useLastSettingsEnabled = isUseLastSettingsEnabled();
        
        if (useLastSettingsEnabled && computer != null) {
            PreferenceConfiguration lastSettings = getAppLastSettings(computer.uuid, app);
            
            if (lastSettings != null) {
                // Create Intent with last settings
                return ServerHelper.createStartIntent(parent, app, computer, managerBinder, lastSettings);
            }
        }
        
        // Create Intent with default settings
        return ServerHelper.createStartIntent(parent, app, computer, managerBinder);
    }
    
    /**
     * Add last settings to Intent
     * 
     * @param intent Intent to add settings to
     * @param lastSettings Last settings configuration
     */
    public static void addLastSettingsToIntent(Intent intent, PreferenceConfiguration lastSettings) {
        if (intent == null || lastSettings == null) {
            return;
        }
        
        intent.putExtra(INTENT_USE_LAST_SETTINGS, true);
        intent.putExtra(INTENT_LAST_SETTINGS_WIDTH, lastSettings.width);
        intent.putExtra(INTENT_LAST_SETTINGS_HEIGHT, lastSettings.height);
        intent.putExtra(INTENT_LAST_SETTINGS_FPS, lastSettings.fps);
        intent.putExtra(INTENT_LAST_SETTINGS_BITRATE, lastSettings.bitrate);
        intent.putExtra(INTENT_LAST_SETTINGS_RESOLUTION_SCALE, lastSettings.resolutionScale);
        intent.putExtra(INTENT_LAST_SETTINGS_VIDEO_FORMAT, lastSettings.videoFormat.toString());
        intent.putExtra(INTENT_LAST_SETTINGS_ENABLE_HDR, lastSettings.enableHdr);
        intent.putExtra(INTENT_LAST_SETTINGS_ENABLE_MIC, lastSettings.enableMic);
        intent.putExtra(INTENT_LAST_SETTINGS_MIC_BITRATE, lastSettings.micBitrate);
        intent.putExtra(INTENT_LAST_SETTINGS_ENABLE_NATIVE_MOUSE, lastSettings.enableNativeMousePointer);
        intent.putExtra(INTENT_LAST_SETTINGS_GYRO_SENSITIVITY, lastSettings.gyroSensitivityMultiplier);
        intent.putExtra(INTENT_LAST_SETTINGS_GYRO_INVERT_X, lastSettings.gyroInvertXAxis);
        intent.putExtra(INTENT_LAST_SETTINGS_GYRO_INVERT_Y, lastSettings.gyroInvertYAxis);
        intent.putExtra(INTENT_LAST_SETTINGS_GYRO_ACTIVATION_KEY, lastSettings.gyroActivationKeyCode);
        intent.putExtra(INTENT_LAST_SETTINGS_SHOW_BITRATE_CARD, lastSettings.showBitrateCard);
        intent.putExtra(INTENT_LAST_SETTINGS_SHOW_GYRO_CARD, lastSettings.showGyroCard);
        intent.putExtra(INTENT_LAST_SETTINGS_SHOW_QuickKeyCard, lastSettings.showQuickKeyCard);
    }
    
    /**
     * Read last settings from Intent and apply to PreferenceConfiguration
     * 
     * @param intent Intent containing last settings
     * @param prefConfig PreferenceConfiguration object to apply settings to
     * @return true if last settings were successfully applied, false otherwise
     */
    public boolean applyLastSettingsFromIntent(Intent intent, PreferenceConfiguration prefConfig) {
        if (intent == null || prefConfig == null) {
            return false;
        }
        
        try {
            // Check if Intent contains last settings
            boolean useLastSettings = intent.getBooleanExtra(INTENT_USE_LAST_SETTINGS, false);
            if (!useLastSettings) {
                return false;
            }
            
            // Read last settings from Intent
            prefConfig.width = intent.getIntExtra(INTENT_LAST_SETTINGS_WIDTH, prefConfig.width);
            prefConfig.height = intent.getIntExtra(INTENT_LAST_SETTINGS_HEIGHT, prefConfig.height);
            prefConfig.fps = intent.getIntExtra(INTENT_LAST_SETTINGS_FPS, prefConfig.fps);
            prefConfig.bitrate = intent.getIntExtra(INTENT_LAST_SETTINGS_BITRATE, prefConfig.bitrate);
            prefConfig.resolutionScale = intent.getIntExtra(INTENT_LAST_SETTINGS_RESOLUTION_SCALE, prefConfig.resolutionScale);
            prefConfig.enableHdr = intent.getBooleanExtra(INTENT_LAST_SETTINGS_ENABLE_HDR, prefConfig.enableHdr);
            prefConfig.enableMic = intent.getBooleanExtra(INTENT_LAST_SETTINGS_ENABLE_MIC, prefConfig.enableMic);
            prefConfig.micBitrate = intent.getIntExtra(INTENT_LAST_SETTINGS_MIC_BITRATE, prefConfig.micBitrate);
            prefConfig.enableNativeMousePointer = intent.getBooleanExtra(INTENT_LAST_SETTINGS_ENABLE_NATIVE_MOUSE, prefConfig.enableNativeMousePointer);
            prefConfig.gyroSensitivityMultiplier = intent.getFloatExtra(INTENT_LAST_SETTINGS_GYRO_SENSITIVITY, prefConfig.gyroSensitivityMultiplier);
            prefConfig.gyroInvertXAxis = intent.getBooleanExtra(INTENT_LAST_SETTINGS_GYRO_INVERT_X, prefConfig.gyroInvertXAxis);
            prefConfig.gyroInvertYAxis = intent.getBooleanExtra(INTENT_LAST_SETTINGS_GYRO_INVERT_Y, prefConfig.gyroInvertYAxis);
            prefConfig.gyroActivationKeyCode = intent.getIntExtra(INTENT_LAST_SETTINGS_GYRO_ACTIVATION_KEY, prefConfig.gyroActivationKeyCode);
            prefConfig.showBitrateCard = intent.getBooleanExtra(INTENT_LAST_SETTINGS_SHOW_BITRATE_CARD, prefConfig.showBitrateCard);
            prefConfig.showGyroCard = intent.getBooleanExtra(INTENT_LAST_SETTINGS_SHOW_GYRO_CARD, prefConfig.showGyroCard);
            prefConfig.showQuickKeyCard = intent.getBooleanExtra(INTENT_LAST_SETTINGS_SHOW_QuickKeyCard, prefConfig.showQuickKeyCard);
            
            // Parse video format
            String videoFormatStr = intent.getStringExtra(INTENT_LAST_SETTINGS_VIDEO_FORMAT);
            prefConfig.videoFormat = parseVideoFormat(videoFormatStr);
            
            return true;
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Check if the specified app has last settings
     *
     * @param computerUuid Computer UUID
     * @param app          App object
     * @return true if last settings exist, false otherwise
     */
    public boolean hasLastSettings(String computerUuid, NvApp app) {
        if (app == null) {
            return false;
        }
        
        String key = generateKey(computerUuid, app.getAppId());
        String settingsJsonString = preferences.getString(key, null);
        
        return settingsJsonString != null && !settingsJsonString.isEmpty();
    }
}
