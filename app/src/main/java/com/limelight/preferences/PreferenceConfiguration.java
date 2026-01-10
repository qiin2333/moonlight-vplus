package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.view.Display;
import android.graphics.Point;
import android.view.WindowManager;
import android.view.KeyEvent;

import com.limelight.nvstream.jni.MoonBridge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PreferenceConfiguration {
    public enum FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
    };

    public enum AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

    public enum PerfOverlayOrientation {
        HORIZONTAL,
        VERTICAL
    }

    public enum PerfOverlayPosition {
        // 水平方向选项
        TOP,
        BOTTOM,
        // 垂直方向选项（四个角）
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private static final String ENABLE_DOUBLE_CLICK_DRAG_PREF_STRING = "pref_enable_double_click_drag";
    private static final String DOUBLE_TAP_TIME_THRESHOLD_PREF_STRING = "seekbar_double_tap_time_threshold";
    private static final String ENABLE_LOCAL_CURSOR_RENDERING_PREF_STRING = "pref_enable_local_cursor_rendering";

    private static final String LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps";
    private static final String LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround";

    static final String RESOLUTION_PREF_STRING = "list_resolution";
    static final String FPS_PREF_STRING = "list_fps";
    static final String BITRATE_PREF_STRING = "seekbar_bitrate_kbps";
    static final String HOST_SCALE_PREF_STRING = "seekbar_resolutions_scale";
    private static final String BITRATE_PREF_OLD_STRING = "seekbar_bitrate";
    static final String LONG_PRESS_FLAT_REGION_PIXELS_PREF_STRING = "seekbar_flat_region_pixels";
    static final String SYNC_TOUCH_EVENT_WITH_DISPLAY_PREF_STRING = "checkbox_sync_touch_event_with_display";
    static final String ENABLE_KEYBOARD_TOGGLE_IN_NATIVE_TOUCH = "checkbox_enable_keyboard_toggle_in_native_touch";
    static final String NATIVE_TOUCH_FINGERS_TO_TOGGLE_KEYBOARD_PREF_STRING = "seekbar_keyboard_toggle_fingers_native_touch";



    private static final String STRETCH_PREF_STRING = "checkbox_stretch_video";
    private static final String SOPS_PREF_STRING = "checkbox_enable_sops";
    private static final String DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings";
    private static final String HOST_AUDIO_PREF_STRING = "checkbox_host_audio";
    private static final String DEADZONE_PREF_STRING = "seekbar_deadzone";
    private static final String OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity";
    private static final String LANGUAGE_PREF_STRING = "list_languages";
    private static final String SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode";
    private static final String MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller";
    static final String AUDIO_CONFIG_PREF_STRING = "list_audio_config";
    private static final String USB_DRIVER_PREF_SRING = "checkbox_usb_driver";
    private static final String VIDEO_FORMAT_PREF_STRING = "video_format";
    private static final String ONSCREEN_KEYBOARD_PREF_STRING = "checkbox_show_onscreen_keyboard";
    private static final String ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3";
    private static final String SHOW_GUIDE_BUTTON_PREF_STRING = "checkbox_show_guide_button";
    private static final String HALF_HEIGHT_OSC_PORTRAIT_PREF_STRING = "checkbox_half_height_osc_portrait";
    private static final String LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop";
    private static final String ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr";
    private static final String ENABLE_HDR_HIGH_BRIGHTNESS_PREF_STRING = "checkbox_enable_hdr_high_brightness";
    private static final String ENABLE_PIP_PREF_STRING = "checkbox_enable_pip";
    private static final String ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay";
    private static final String PERF_OVERLAY_LOCKED_STRING = "perf_overlay_locked";
    private static final String PERF_OVERLAY_ORIENTATION_STRING = "list_perf_overlay_orientation";
    private static final String PERF_OVERLAY_POSITION_STRING = "list_perf_overlay_position";
    private static final String BIND_ALL_USB_STRING = "checkbox_usb_bind_all";
    private static final String MOUSE_EMULATION_STRING = "checkbox_mouse_emulation";
    private static final String ANALOG_SCROLLING_PREF_STRING = "analog_scrolling";
    private static final String MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons";
    static final String UNLOCK_FPS_STRING = "checkbox_unlock_fps";
    private static final String VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc";
    private static final String VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback";
    private static final String VIBRATE_FALLBACK_STRENGTH_PREF_STRING = "seekbar_vibrate_fallback_strength";
    private static final String FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons";
    public static final String TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad";
    private static final String LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast";
    private static final String ENABLE_STUN_PREF_STRING = "checkbox_enable_stun";
    private static final String LOCK_SCREEN_AFTER_DISCONNECT_PREF_STRING = "checkbox_lock_screen_after_disconnect";
    private static final String SWAP_QUIT_AND_DISCONNECT_PERF_STRING = "checkbox_swap_quit_and_disconnect";
    private static final String SCREEN_COMBINATION_MODE_PREF_STRING = "list_screen_combination_mode";
    private static final String FRAME_PACING_PREF_STRING = "frame_pacing";
    private static final String ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode";
    public static final String ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING = "checkbox_enable_native_mouse_pointer";
    public static final String NATIVE_MOUSE_MODE_PRESET_PREF_STRING = "list_native_mouse_mode_preset";
    // Card visibility preferences
    private static final String SHOW_BITRATE_CARD_PREF_STRING = "checkbox_show_bitrate_card";
    private static final String SHOW_GYRO_CARD_PREF_STRING = "checkbox_show_gyro_card";
    private static final String SHOW_QuickKeyCard = "checkbox_show_QuickKeyCard";

    public static final String ENABLE_ENHANCED_TOUCH_PREF_STRING = "checkbox_enable_enhanced_touch";
    private static final String ENHANCED_TOUCH_ON_RIGHT_PREF_STRING = "checkbox_enhanced_touch_on_which_side";
    private static final String ENHANCED_TOUCH_ZONE_DIVIDER_PREF_STRING = "enhanced_touch_zone_divider";
    private static final String POINTER_VELOCITY_FACTOR_PREF_STRING = "pointer_velocity_factor";
    // private static final String POINTER_FIXED_X_VELOCITY_PREF_STRING = "fixed_x_velocity";


    private static final String ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx";
    private static final String ENABLE_SPATIALIZER_PREF_STRING = "checkbox_enable_spatializer";
    private static final String REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate";
    private static final String FULL_RANGE_PREF_STRING = "checkbox_full_range";
    private static final String GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse";
    private static final String GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors";
    private static final String GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback";
    
    // 陀螺仪偏好设置
    private static final String GYRO_SENSITIVITY_MULTIPLIER_PREF_STRING = "gyro_sensitivity_multiplier";
    private static final String GYRO_INVERT_X_AXIS_PREF_STRING = "gyro_invert_x_axis";
    private static final String GYRO_INVERT_Y_AXIS_PREF_STRING = "gyro_invert_y_axis";
    private static final String GYRO_ACTIVATION_KEY_CODE_PREF_STRING = "gyro_activation_key_code";

    // 麦克风设置
    private static final String ENABLE_MIC_PREF_STRING = "checkbox_enable_mic";
    private static final String MIC_BITRATE_PREF_STRING = "seekbar_mic_bitrate_kbps";
    private static final String MIC_ICON_COLOR_PREF_STRING = "list_mic_icon_color";
    private static final String ENABLE_ESC_MENU_PREF_STRING = "checkbox_enable_esc_menu";
    private static final String ESC_MENU_KEY_PREF_STRING = "list_esc_menu_key";
    
    // 控制流only模式设置
    private static final String CONTROL_ONLY_PREF_STRING = "checkbox_control_only";

    // 输出缓冲区队列大小设置
    private static final String OUTPUT_BUFFER_QUEUE_LIMIT_PREF_STRING = "seekbar_output_buffer_queue_limit";

    //wg
    private static final String ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls";
    static final String IMPORT_CONFIG_STRING = "import_super_config";
    static final String EXPORT_CONFIG_STRING = "export_super_config";
    static final String MERGE_CONFIG_STRING = "merge_super_config";
    static final String ABOUT_AUTHOR = "about_author";

    static final String DEFAULT_RESOLUTION = "1920x1080";
    static final String DEFAULT_FPS = "60";
    private static final boolean DEFAULT_STRETCH = false;
    private static final boolean DEFAULT_SOPS = true;
    private static final boolean DEFAULT_DISABLE_TOASTS = false;
    private static final boolean DEFAULT_HOST_AUDIO = false;
    private static final int DEFAULT_DEADZONE = 7;
    private static final int DEFAULT_OPACITY = 90;
    public static final String DEFAULT_LANGUAGE = "default";
    private static final boolean DEFAULT_MULTI_CONTROLLER = true;
    private static final boolean DEFAULT_USB_DRIVER = true;

    private static final boolean ONSCREEN_CONTROLLER_DEFAULT = false;
    private static final boolean ONSCREEN_KEYBOARD_DEFAULT = false;
    private static final boolean ONLY_L3_R3_DEFAULT = false;
    private static final boolean SHOW_GUIDE_BUTTON_DEFAULT = true;
    private static final boolean HALF_HEIGHT_OSC_PORTRAIT_DEFAULT = true;
    private static final boolean DEFAULT_ENABLE_HDR = false;
    private static final boolean DEFAULT_ENABLE_HDR_HIGH_BRIGHTNESS = false;
    private static final boolean DEFAULT_ENABLE_PIP = false;
    private static final boolean DEFAULT_ENABLE_PERF_OVERLAY = false;
    private static final boolean DEFAULT_PERF_OVERLAY_LOCKED = false;
    private static final String DEFAULT_PERF_OVERLAY_ORIENTATION = "horizontal";
    private static final String DEFAULT_PERF_OVERLAY_POSITION = "top";
    private static final boolean DEFAULT_BIND_ALL_USB = false;
    private static final boolean DEFAULT_MOUSE_EMULATION = true;
    private static final String DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right";
    private static final boolean DEFAULT_MOUSE_NAV_BUTTONS = false;
    private static final String DEFAULT_NATIVE_MOUSE_MODE_PRESET = "classic";
    private static final boolean DEFAULT_UNLOCK_FPS = false;
    private static final boolean DEFAULT_VIBRATE_OSC = true;
    private static final boolean DEFAULT_VIBRATE_FALLBACK = false;
    private static final int DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100;
    private static final boolean DEFAULT_FLIP_FACE_BUTTONS = false;
    private static final boolean DEFAULT_TOUCHSCREEN_TRACKPAD = true;
    private static final String DEFAULT_AUDIO_CONFIG = "2"; // Stereo
    private static final boolean DEFAULT_LATENCY_TOAST = false;
    private static final boolean DEFAULT_ENABLE_STUN = false;
    private static final String DEFAULT_SCREEN_COMBINATION_MODE = "-1";
    private static final String DEFAULT_FRAME_PACING = "latency";
    private static final boolean DEFAULT_ABSOLUTE_MOUSE_MODE = false;
    private static final boolean DEFAULT_ENABLE_NATIVE_MOUSE_POINTER = false;
    private static final boolean DEFAULT_ENABLE_AUDIO_FX = false;
    private static final boolean DEFAULT_ENABLE_SPATIALIZER = false;
    private static final boolean DEFAULT_REDUCE_REFRESH_RATE = false;
    private static final boolean DEFAULT_FULL_RANGE = false;
    private static final boolean DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false;
    private static final boolean DEFAULT_GAMEPAD_MOTION_SENSORS = true;
    private static final boolean DEFAULT_GAMEPAD_MOTION_FALLBACK = false;
    
    // 陀螺仪偏好默认值
    private static final float DEFAULT_GYRO_SENSITIVITY_MULTIPLIER = 1.0f;
    private static final boolean DEFAULT_GYRO_INVERT_X_AXIS = false;
    private static final boolean DEFAULT_GYRO_INVERT_Y_AXIS = false;
    private static final int DEFAULT_GYRO_ACTIVATION_KEY_CODE = KeyEvent.KEYCODE_BUTTON_L2;

    // 麦克风设置默认值
    private static final boolean DEFAULT_ENABLE_MIC = false;
    private static final int DEFAULT_MIC_BITRATE = 96; // 默认128 kbps
    private static final String DEFAULT_MIC_ICON_COLOR = "solid_white"; // 默认白
    private static final boolean DEFAULT_ENABLE_ESC_MENU = true; // 默认启用ESC菜单
    private static final int DEFAULT_ESC_MENU_KEY = KeyEvent.KEYCODE_ESCAPE;
    
    // 控制流only模式默认值
    private static final boolean DEFAULT_CONTROL_ONLY = false;

    // 输出缓冲区队列大小默认值
    private static final int DEFAULT_OUTPUT_BUFFER_QUEUE_LIMIT = 2;

    private static final boolean DEFAULT_ENABLE_DOUBLE_CLICK_DRAG = false;
    private static final int DEFAULT_DOUBLE_TAP_TIME_THRESHOLD = 125; // 默认125ms
    public boolean enableDoubleClickDrag;
    public int doubleTapTimeThreshold;
    
    private static final boolean DEFAULT_ENABLE_LOCAL_CURSOR_RENDERING = true;
    public boolean enableLocalCursorRendering;
    public static final int FRAME_PACING_MIN_LATENCY = 0;
    public static final int FRAME_PACING_BALANCED = 1;
    public static final int FRAME_PACING_CAP_FPS = 2;
    public static final int FRAME_PACING_MAX_SMOOTHNESS = 3;
    public static final int FRAME_PACING_EXPERIMENTAL_LOW_LATENCY = 4;
    public static final int FRAME_PACING_PRECISE_SYNC = 5;

    public static final String RES_360P = "640x360";
    public static final String RES_480P = "854x480";
    public static final String RES_720P = "1280x720";
    public static final String RES_1080P = "1920x1080";
    public static final String RES_1440P = "2560x1440";
    public static final String RES_4K = "3840x2160";
    public static final String RES_NATIVE = "Native";

    private static final String VIDEO_FORMAT_AUTO = "auto";
    private static final String VIDEO_FORMAT_AV1 = "forceav1";
    private static final String VIDEO_FORMAT_HEVC = "forceh265";
    private static final String VIDEO_FORMAT_H264 = "neverh265";

    private static final String[] RESOLUTIONS = {
        "640x360", "854x480", "1280x720", "1920x1080", "2560x1440", "3840x2160", "Native"
    };

    private static final String REVERSE_RESOLUTION_PREF_STRING = "checkbox_reverse_resolution";
    private static final boolean DEFAULT_REVERSE_RESOLUTION = false;

    private static final String ROTABLE_SCREEN_PREF_STRING = "checkbox_rotable_screen";
    private static final boolean DEFAULT_ROTABLE_SCREEN = false;

    // 画面位置常量
    private static final String SCREEN_POSITION_PREF_STRING = "list_screen_position";
    private static final String SCREEN_OFFSET_X_PREF_STRING = "seekbar_screen_offset_x";
    private static final String SCREEN_OFFSET_Y_PREF_STRING = "seekbar_screen_offset_y";

    // 默认值
    private static final String DEFAULT_SCREEN_POSITION = "center"; // 居中
    private static final int DEFAULT_SCREEN_OFFSET_X = 0;
    private static final int DEFAULT_SCREEN_OFFSET_Y = 0;

    // 位置枚举
    public enum ScreenPosition {
        TOP_LEFT,
        TOP_CENTER,
        TOP_RIGHT,
        CENTER_LEFT,
        CENTER,
        CENTER_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_CENTER,
        BOTTOM_RIGHT
    }

    public int width, height, fps, resolutionScale;
    public int bitrate;
    public int longPressflatRegionPixels; //Assigned to NativeTouchContext.INTIAL_ZONE_PIXELS
    public boolean syncTouchEventWithDisplay; // if true, view.requestUnbufferedDispatch(event) will be disabled
    public boolean enableEnhancedTouch; //Assigned to NativeTouchContext.ENABLE_ENHANCED_TOUCH
    public boolean enhancedTouchOnWhichSide; //Assigned to NativeTouchContext.ENHANCED_TOUCH_ON_RIGHT
    public int enhanceTouchZoneDivider; //Assigned to NativeTouchContext.ENHANCED_TOUCH_ZONE_DIVIDER
    public float pointerVelocityFactor; //Assigned to NativeTouchContext.POINTER_VELOCITY_FACTOR
    // public float pointerFixedXVelocity; //Assigned to NativeTouchContext.POINTER_FIXED_X_VELOCITY
    public int nativeTouchFingersToToggleKeyboard; // Number of fingers to tap to toggle local on-screen keyboard in native touch mode.



    public FormatOption videoFormat;
    public int deadzonePercentage;
    public int oscOpacity;
    public boolean stretchVideo, enableSops, playHostAudio, disableWarnings;
    public String language;
    public boolean smallIconMode, multiController, usbDriver, flipFaceButtons;
    public boolean onscreenController;
    public boolean onscreenKeyboard;
    public boolean onlyL3R3;
    public boolean showGuideButton;
    public boolean halfHeightOscPortrait;
    public boolean enableHdr;
    public boolean enableHdrHighBrightness;
    public boolean enablePip;
    public boolean enablePerfOverlay;
    public boolean perfOverlayLocked;
    public PerfOverlayOrientation perfOverlayOrientation;
    public PerfOverlayPosition perfOverlayPosition;
    public boolean enableSimplifyPerfOverlay;
    public boolean enableLatencyToast;
    public boolean enableStun;
    public int screenCombinationMode;
    public boolean lockScreenAfterDisconnect;
    public boolean swapQuitAndDisconnect;
    public boolean bindAllUsb;
    public boolean mouseEmulation;
    public AnalogStickForScrolling analogStickForScrolling;
    public boolean mouseNavButtons;
    public boolean unlockFps;
    public boolean vibrateOsc;
    public boolean vibrateFallbackToDevice;
    public int vibrateFallbackToDeviceStrength;
    public boolean touchscreenTrackpad;
    public MoonBridge.AudioConfiguration audioConfiguration;
    public int framePacing;
    public boolean absoluteMouseMode;
    public boolean enableNativeMousePointer;
    public boolean enableAudioFx;
    public boolean enableSpatializer;
    public boolean reduceRefreshRate;
    public boolean fullRange;
    public boolean gamepadMotionSensors;
    public boolean gamepadTouchpadAsMouse;
    public boolean gamepadMotionSensorsFallbackToDevice;
    public boolean reverseResolution;
    public boolean rotableScreen;
    // Runtime-only: enable mapping gyroscope motion to right analog stick
    public boolean gyroToRightStick;
    // Runtime-only: sensitivity in deg/s for full stick deflection
    public float gyroFullDeflectionDps;
    // Persistent: sensitivity multiplier (higher -> faster)
    public float gyroSensitivityMultiplier;
    // Persistent: activation keycode to hold (Android keycode); 0 means LT analog, 1 means RT analog, otherwise Android key
    public int gyroActivationKeyCode;
    // Persistent: invert X-axis direction for gyro input
    public boolean gyroInvertXAxis;
    // Persistent: invert Y-axis direction for gyro input
    public boolean gyroInvertYAxis;
    // Card visibility
    public boolean showBitrateCard;
    public boolean showGyroCard;
    public boolean showQuickKeyCard;

    // 麦克风设置
    public boolean enableMic;
    public int micBitrate;
    public String micIconColor;
    
    // ESC菜单设置
    public boolean enableEscMenu;
    public int escMenuKey;
    
    // 控制流only模式设置
    public boolean controlOnly;

    // 输出缓冲区队列大小
    public int outputBufferQueueLimit;

    public ScreenPosition screenPosition;
    public int screenOffsetX;
    public int screenOffsetY;
    
    public boolean useExternalDisplay;

    public static boolean isNativeResolution(int width, int height) {
        // 使用集合检查是否为原生分辨率
        Set<String> resolutionSet = new HashSet<>(Arrays.asList(RESOLUTIONS));
        return !resolutionSet.contains(width + "x" + height);
    }

    // If we have a screen that has semi-square dimensions, we may want to change our behavior
    // to allow any orientation and vertical+horizontal resolutions.
    public static boolean isSquarishScreen(int width, int height) {
        float longDim = Math.max(width, height);
        float shortDim = Math.min(width, height);

        // We just put the arbitrary cutoff for a square-ish screen at 1.3
        return longDim / shortDim < 1.3f;
    }

    public static boolean isSquarishScreen(Display display) {
        int width, height;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            width = display.getMode().getPhysicalWidth();
            height = display.getMode().getPhysicalHeight();
        }
        else {
            width = display.getWidth();
            height = display.getHeight();
        }

        return isSquarishScreen(width, height);
    }

    private static String convertFromLegacyResolutionString(String resString) {
        if (resString.equalsIgnoreCase("360p")) {
            return RES_360P;
        }
        else if (resString.equalsIgnoreCase("480p")) {
            return RES_480P;
        }
        else if (resString.equalsIgnoreCase("720p")) {
            return RES_720P;
        }
        else if (resString.equalsIgnoreCase("1080p")) {
            return RES_1080P;
        }
        else if (resString.equalsIgnoreCase("1440p")) {
            return RES_1440P;
        }
        else if (resString.equalsIgnoreCase("4K")) {
            return RES_4K;
        }
        else {
            // Should be unreachable
            return RES_1080P;
        }
    }

    private static int getWidthFromResolutionString(String resString) {
        try {
            return Integer.parseInt(resString.split("x")[0]);
        } catch (Exception e) {
            // 如果解析失败，返回默认宽度
            return Integer.parseInt(DEFAULT_RESOLUTION.split("x")[0]);
        }
    }

    private static int getHeightFromResolutionString(String resString) {
        try {
            return Integer.parseInt(resString.split("x")[1]);
        } catch (Exception e) {
            // 如果解析失败，返回默认高度
            return Integer.parseInt(DEFAULT_RESOLUTION.split("x")[1]);
        }
    }

    private static String getResolutionString(int width, int height) {
        // 使用数组简化分辨率获取
        for (String res : RESOLUTIONS) {
            String[] dimensions = res.split("x");
            if (height == Integer.parseInt(dimensions[1])) {
                return res;
            }
        }
        return RES_1080P; // 默认返回1080P
    }

    public static int getDefaultBitrate(String resString, String fpsString) {
        int width = getWidthFromResolutionString(resString);
        int height = getHeightFromResolutionString(resString);
        int fps = Integer.parseInt(fpsString);

        // This logic is shamelessly stolen from Moonlight Qt:
        // https://github.com/moonlight-stream/moonlight-qt/blob/master/app/settings/streamingpreferences.cpp

        // Don't scale bitrate linearly beyond 60 FPS. It's definitely not a linear
        // bitrate increase for frame rate once we get to values that high.
        double frameRateFactor = (fps <= 60 ? fps : (Math.sqrt(fps / 60.f) * 60.f)) / 30.f;

        // TODO: Collect some empirical data to see if these defaults make sense.
        // We're just using the values that the Shield used, as we have for years.
        int[] pixelVals = {
            640 * 360,
            854 * 480,
            1280 * 720,
            1920 * 1080,
            2560 * 1440,
            3840 * 2160,
            -1,
        };
        int[] factorVals = {
            1,
            2,
            5,
            10,
            20,
            40,
            -1
        };

        // Calculate the resolution factor by linear interpolation of the resolution table
        float resolutionFactor;
        int pixels = width * height;
        for (int i = 0; ; i++) {
            if (pixels == pixelVals[i]) {
                // We can bail immediately for exact matches
                resolutionFactor = factorVals[i];
                break;
            }
            else if (pixels < pixelVals[i]) {
                if (i == 0) {
                    // Never go below the lowest resolution entry
                    resolutionFactor = factorVals[i];
                }
                else {
                    // Interpolate between the entry greater than the chosen resolution (i) and the entry less than the chosen resolution (i-1)
                    resolutionFactor = ((float)(pixels - pixelVals[i-1]) / (pixelVals[i] - pixelVals[i-1])) * (factorVals[i] - factorVals[i-1]) + factorVals[i-1];
                }
                break;
            }
            else if (pixelVals[i] == -1) {
                // Never go above the highest resolution entry
                resolutionFactor = factorVals[i-1];
                break;
            }
        }

        return (int)Math.round(resolutionFactor * frameRateFactor) * 1000;
    }

    public static boolean getDefaultSmallMode(Context context) {
        PackageManager manager = context.getPackageManager();
        if (manager != null) {
            // TVs shouldn't use small mode by default
            if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                return false;
            }

            // API 21 uses LEANBACK instead of TELEVISION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    return false;
                }
            }
        }

        // Use small mode on anything smaller than a 7" tablet
        return context.getResources().getConfiguration().smallestScreenWidthDp < 500;
    }

    public static int getDefaultBitrate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return getDefaultBitrate(
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION),
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS));
    }

    private static FormatOption getVideoFormatValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String str = prefs.getString(VIDEO_FORMAT_PREF_STRING, VIDEO_FORMAT_AUTO);
        if (str.equals(VIDEO_FORMAT_AV1)) {
            return FormatOption.FORCE_AV1;
        } else if (str.equals(VIDEO_FORMAT_HEVC)) {
            return FormatOption.FORCE_HEVC;
        } else if (str.equals(VIDEO_FORMAT_H264)) {
            return FormatOption.FORCE_H264;
        }
        else {
            return FormatOption.AUTO;
        }
    }

    private static String getVideoFormatPreferenceString(FormatOption format) {
        switch (format) {
            case AUTO:
                return VIDEO_FORMAT_AUTO;
            case FORCE_AV1:
                return VIDEO_FORMAT_AV1;
            case FORCE_HEVC:
                return VIDEO_FORMAT_HEVC;
            case FORCE_H264:
                return VIDEO_FORMAT_H264;
            default:
                return VIDEO_FORMAT_AUTO;
        }
    }

    private static int getFramePacingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Migrate legacy never drop frames option to the new location
        if (prefs.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
            boolean legacyNeverDropFrames = prefs.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false);
            prefs.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, legacyNeverDropFrames ? "balanced" : "latency")
                    .apply();
        }

        String str = prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING);
        if (str.equals("latency")) {
            return FRAME_PACING_MIN_LATENCY;
        }
        else if (str.equals("balanced")) {
            return FRAME_PACING_BALANCED;
        }
        else if (str.equals("cap-fps")) {
            return FRAME_PACING_CAP_FPS;
        }
        else if (str.equals("smoothness")) {
            return FRAME_PACING_MAX_SMOOTHNESS;
        }
        else if (str.equals("experimental-low-latency")) {
            return FRAME_PACING_EXPERIMENTAL_LOW_LATENCY;
        }
        else if (str.equals("precise-sync")) {
            return FRAME_PACING_PRECISE_SYNC;
        }
        else {
            // Should never get here
            return FRAME_PACING_MIN_LATENCY;
        }
    }

    private static AnalogStickForScrolling getAnalogStickForScrollingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING);
        if (str.equals("right")) {
            return AnalogStickForScrolling.RIGHT;
        }
        else if (str.equals("left")) {
            return AnalogStickForScrolling.LEFT;
        }
        else {
            return AnalogStickForScrolling.NONE;
        }
    }

    public static void resetStreamingSettings(Context context) {
        // We consider resolution, FPS, bitrate, HDR, and video format as "streaming settings" here
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(HOST_SCALE_PREF_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(ENABLE_HDR_HIGH_BRIGHTNESS_PREF_STRING)
                .remove(UNLOCK_FPS_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply();
    }

    public static void completeLanguagePreferenceMigration(Context context) {
        // Put our language option back to default which tells us that we've already migrated it
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE).apply();
    }

    public static boolean isShieldAtvFirmwareWithBrokenHdr() {
        // This particular Shield TV firmware crashes when using HDR
        // https://www.nvidia.com/en-us/geforce/forums/notifications/comment/155192/
        return Build.MANUFACTURER.equalsIgnoreCase("NVIDIA") &&
                Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395");
    }

    public static PreferenceConfiguration readPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PreferenceConfiguration config = new PreferenceConfiguration();

        // Migrate legacy preferences to the new locations
        if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
            if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply();
            }
        }

        String resStr = prefs.getString(RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);

        // 添加Native分辨率支持
        if (resStr.equals(RES_NATIVE)) {
            // 获取设备原生分辨率
            Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            Point size = new Point();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealSize(size);  // 需要API 17+
            } else {
                display.getSize(size);      // 兼容旧版本
            }
            config.width = size.x;
            config.height = size.y;
        } else {
            // 原有解析逻辑
            config.width = PreferenceConfiguration.getWidthFromResolutionString(resStr);
            config.height = PreferenceConfiguration.getHeightFromResolutionString(resStr);
        }

        // 处理新旧数据类型兼容
        Object fpsValue = prefs.getAll().get(FPS_PREF_STRING);
        if (fpsValue instanceof String) {
            config.fps = Integer.parseInt((String) fpsValue);
        } else if (fpsValue instanceof Integer) {
            // 迁移旧整型值为字符串
            config.fps = (Integer) fpsValue;
            prefs.edit().putString(FPS_PREF_STRING, String.valueOf(config.fps)).apply();
        } else {
            // 默认值处理
            config.fps = Integer.parseInt(prefs.getString(FPS_PREF_STRING, DEFAULT_FPS));
        }

        if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
            // We need to write small icon mode's default to disk for the settings page to display
            // the current state of the option properly
            prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context)).apply();
        }

        if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
            // Android 12 has a nasty bug that causes crashes when the app touches the InputDevice's
            // associated InputDeviceSensorManager (just calling getSensorManager() is enough).
            // As a workaround, we will override the default value for the gamepad motion sensor
            // option to disabled on Android 12 to reduce the impact of this bug.
            // https://cs.android.com/android/_/android/platform/frameworks/base/+/8970010a5e9f3dc5c069f56b4147552accfcbbeb
            prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply();
        }

        // This must happen after the preferences migration to ensure the preferences are populated
        config.bitrate = prefs.getInt(BITRATE_PREF_STRING, prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000);
        if (config.bitrate == 0) {
            config.bitrate = getDefaultBitrate(context);
        }

        config.resolutionScale = prefs.getInt(HOST_SCALE_PREF_STRING, 100);
        config.longPressflatRegionPixels = prefs.getInt(LONG_PRESS_FLAT_REGION_PIXELS_PREF_STRING, 0);  // define a flat region to suppress coordinates jitter. This is a simulation of iOS behavior since it only send 1 touch event during long press, which feels better in some cases.
        config.syncTouchEventWithDisplay = prefs.getBoolean(SYNC_TOUCH_EVENT_WITH_DISPLAY_PREF_STRING, false); // set true to disable "requestUnbufferedDispatch", feels better in some cases.
        if(prefs.getBoolean(ENABLE_KEYBOARD_TOGGLE_IN_NATIVE_TOUCH, true)) {
            config.nativeTouchFingersToToggleKeyboard = prefs.getInt(NATIVE_TOUCH_FINGERS_TO_TOGGLE_KEYBOARD_PREF_STRING, 3); // least fingers of tap to toggle local keyboard, configurable from 3 to 10 in menu.
        }
        else{
            config.nativeTouchFingersToToggleKeyboard = -1; // completely disable keyboard toggle in multi-point touch
        }

        // Enhance touch settings
        config.enableEnhancedTouch = prefs.getBoolean(ENABLE_ENHANCED_TOUCH_PREF_STRING, false);
        config.enhancedTouchOnWhichSide = prefs.getBoolean(ENHANCED_TOUCH_ON_RIGHT_PREF_STRING, true);  // by default, enhanced touch zone is on the right side.
        config.enhanceTouchZoneDivider = prefs.getInt(ENHANCED_TOUCH_ZONE_DIVIDER_PREF_STRING,50);  // decides where to divide native touch zone & enhance touch zone by a vertical line.
        config.pointerVelocityFactor = prefs.getInt(POINTER_VELOCITY_FACTOR_PREF_STRING,100);  // set pointer velocity faster or slower within enhance touch zone, useful in some games for tweaking view rotation sensitivity.
        // config.pointerFixedXVelocity = prefs.getInt(POINTER_FIXED_X_VELOCITY_PREF_STRING,0);

        String audioConfig = prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG);
        if (audioConfig.equals("71")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_71_SURROUND;
        }
        else if (audioConfig.equals("51")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_51_SURROUND;
        }
        else /* if (audioConfig.equals("2")) */ {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        }

        config.videoFormat = getVideoFormatValue(context);
        config.framePacing = getFramePacingValue(context);

        config.analogStickForScrolling = getAnalogStickForScrollingValue(context);

        config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE);

        config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY);

        config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE);

        // Checkbox preferences
        config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS);
        config.enableDoubleClickDrag = prefs.getBoolean(ENABLE_DOUBLE_CLICK_DRAG_PREF_STRING, DEFAULT_ENABLE_DOUBLE_CLICK_DRAG);
        config.doubleTapTimeThreshold = prefs.getInt(DOUBLE_TAP_TIME_THRESHOLD_PREF_STRING, DEFAULT_DOUBLE_TAP_TIME_THRESHOLD);
        config.enableLocalCursorRendering = prefs.getBoolean(ENABLE_LOCAL_CURSOR_RENDERING_PREF_STRING, DEFAULT_ENABLE_LOCAL_CURSOR_RENDERING);
        config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS);
        config.stretchVideo = prefs.getBoolean(STRETCH_PREF_STRING, DEFAULT_STRETCH);
        config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO);
        config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context));
        config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER);
        config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER);
        config.onscreenController = prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, ONSCREEN_CONTROLLER_DEFAULT);
        config.onscreenKeyboard = prefs.getBoolean(ONSCREEN_KEYBOARD_PREF_STRING, ONSCREEN_KEYBOARD_DEFAULT);
        config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT);
        config.showGuideButton = prefs.getBoolean(SHOW_GUIDE_BUTTON_PREF_STRING, SHOW_GUIDE_BUTTON_DEFAULT);
        config.halfHeightOscPortrait = prefs.getBoolean(HALF_HEIGHT_OSC_PORTRAIT_PREF_STRING, HALF_HEIGHT_OSC_PORTRAIT_DEFAULT);
        config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) && !isShieldAtvFirmwareWithBrokenHdr();
        config.enableHdrHighBrightness = prefs.getBoolean(ENABLE_HDR_HIGH_BRIGHTNESS_PREF_STRING, DEFAULT_ENABLE_HDR_HIGH_BRIGHTNESS);
        config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP);
        config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY);
        config.perfOverlayLocked = prefs.getBoolean(PERF_OVERLAY_LOCKED_STRING, DEFAULT_PERF_OVERLAY_LOCKED);
        
        // 读取性能覆盖层方向和位置设置
        String perfOverlayOrientation = prefs.getString(PERF_OVERLAY_ORIENTATION_STRING, DEFAULT_PERF_OVERLAY_ORIENTATION);
        if ("vertical".equals(perfOverlayOrientation)) {
            config.perfOverlayOrientation = PerfOverlayOrientation.VERTICAL;
        } else {
            config.perfOverlayOrientation = PerfOverlayOrientation.HORIZONTAL;
        }
        
        String perfOverlayPosition = prefs.getString(PERF_OVERLAY_POSITION_STRING, DEFAULT_PERF_OVERLAY_POSITION);
        switch (perfOverlayPosition) {
            case "bottom":
                config.perfOverlayPosition = PerfOverlayPosition.BOTTOM;
                break;
            case "top_left":
                config.perfOverlayPosition = PerfOverlayPosition.TOP_LEFT;
                break;
            case "top_right":
                config.perfOverlayPosition = PerfOverlayPosition.TOP_RIGHT;
                break;
            case "bottom_left":
                config.perfOverlayPosition = PerfOverlayPosition.BOTTOM_LEFT;
                break;
            case "bottom_right":
                config.perfOverlayPosition = PerfOverlayPosition.BOTTOM_RIGHT;
                break;
            default:
                config.perfOverlayPosition = PerfOverlayPosition.TOP;
                break;
        }
        
        config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB);
        config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION);
        config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS);
        config.unlockFps = prefs.getBoolean(UNLOCK_FPS_STRING, DEFAULT_UNLOCK_FPS);
        config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC);
        config.vibrateFallbackToDevice = prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK);
        config.vibrateFallbackToDeviceStrength = prefs.getInt(VIBRATE_FALLBACK_STRENGTH_PREF_STRING, DEFAULT_VIBRATE_FALLBACK_STRENGTH);
        config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS);
        config.touchscreenTrackpad = prefs.getBoolean(TOUCHSCREEN_TRACKPAD_PREF_STRING, DEFAULT_TOUCHSCREEN_TRACKPAD);
        config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST);
        config.enableStun = prefs.getBoolean(ENABLE_STUN_PREF_STRING, DEFAULT_ENABLE_STUN);

        String screenModeString = prefs.getString(SCREEN_COMBINATION_MODE_PREF_STRING, DEFAULT_SCREEN_COMBINATION_MODE);
        try {
            config.screenCombinationMode = Integer.parseInt(screenModeString);
        } catch (NumberFormatException e) {
            config.screenCombinationMode = -1;
        }

        config.lockScreenAfterDisconnect = prefs.getBoolean(LOCK_SCREEN_AFTER_DISCONNECT_PREF_STRING, DEFAULT_LATENCY_TOAST);
        config.swapQuitAndDisconnect = prefs.getBoolean(SWAP_QUIT_AND_DISCONNECT_PERF_STRING, DEFAULT_LATENCY_TOAST);
        config.absoluteMouseMode = prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE);
        
        // 对于没有触摸屏的设备，默认启用本地鼠标指针
        boolean hasTouchscreen = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        boolean defaultNativeMouse = hasTouchscreen ? DEFAULT_ENABLE_NATIVE_MOUSE_POINTER : true;
        config.enableNativeMousePointer = prefs.getBoolean(ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING, defaultNativeMouse);
        
        // 如果没有触摸屏，强制设置为本地鼠标指针模式
        if (!hasTouchscreen) {
            config.enableNativeMousePointer = true;
            config.enableEnhancedTouch = false;
            config.touchscreenTrackpad = false;
        }
        config.enableAudioFx = prefs.getBoolean(ENABLE_AUDIO_FX_PREF_STRING, DEFAULT_ENABLE_AUDIO_FX);
        config.enableSpatializer = prefs.getBoolean(ENABLE_SPATIALIZER_PREF_STRING, DEFAULT_ENABLE_SPATIALIZER);
        config.reduceRefreshRate = prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE);
        config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE);
        config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE);
        config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS);
        config.gamepadMotionSensorsFallbackToDevice = prefs.getBoolean(GAMEPAD_MOTION_FALLBACK_PREF_STRING, DEFAULT_GAMEPAD_MOTION_FALLBACK);
        config.enableSimplifyPerfOverlay = false;
        
        // 加载陀螺仪偏好设置
        config.gyroSensitivityMultiplier = prefs.getFloat(GYRO_SENSITIVITY_MULTIPLIER_PREF_STRING, DEFAULT_GYRO_SENSITIVITY_MULTIPLIER);
        config.gyroInvertXAxis = prefs.getBoolean(GYRO_INVERT_X_AXIS_PREF_STRING, DEFAULT_GYRO_INVERT_X_AXIS);
        config.gyroInvertYAxis = prefs.getBoolean(GYRO_INVERT_Y_AXIS_PREF_STRING, DEFAULT_GYRO_INVERT_Y_AXIS);
        config.gyroActivationKeyCode = prefs.getInt(GYRO_ACTIVATION_KEY_CODE_PREF_STRING, DEFAULT_GYRO_ACTIVATION_KEY_CODE);

        // Cards visibility (defaults to true)
        config.showBitrateCard = prefs.getBoolean(SHOW_BITRATE_CARD_PREF_STRING, true);
        config.showGyroCard = prefs.getBoolean(SHOW_GYRO_CARD_PREF_STRING, true);
        // 横屏时快捷卡片默认不开启
        boolean defaultQuickKeyCard = config.width > config.height ? false : true;
        config.showQuickKeyCard = prefs.getBoolean(SHOW_QuickKeyCard, defaultQuickKeyCard);

        // 读取麦克风设置
        config.enableMic = prefs.getBoolean(ENABLE_MIC_PREF_STRING, DEFAULT_ENABLE_MIC);
        config.micBitrate = prefs.getInt(MIC_BITRATE_PREF_STRING, DEFAULT_MIC_BITRATE);
        config.micIconColor = prefs.getString(MIC_ICON_COLOR_PREF_STRING, DEFAULT_MIC_ICON_COLOR);
        
        // 读取ESC菜单设置
        config.enableEscMenu = prefs.getBoolean(ENABLE_ESC_MENU_PREF_STRING, DEFAULT_ENABLE_ESC_MENU);
        
        String escMenuKeyStr = prefs.getString(ESC_MENU_KEY_PREF_STRING, String.valueOf(DEFAULT_ESC_MENU_KEY));
        try {
            config.escMenuKey = Integer.parseInt(escMenuKeyStr);
        } catch (NumberFormatException e) {
            config.escMenuKey = DEFAULT_ESC_MENU_KEY;
        }
        
        // 读取控制流only模式设置
        config.controlOnly = prefs.getBoolean(CONTROL_ONLY_PREF_STRING, DEFAULT_CONTROL_ONLY);

        // 读取输出缓冲区队列大小设置
        config.outputBufferQueueLimit = prefs.getInt(OUTPUT_BUFFER_QUEUE_LIMIT_PREF_STRING, DEFAULT_OUTPUT_BUFFER_QUEUE_LIMIT);
        // 确保值在合理范围内 (1-5)
        if (config.outputBufferQueueLimit < 1) {
            config.outputBufferQueueLimit = 1;
        } else if (config.outputBufferQueueLimit > 5) {
            config.outputBufferQueueLimit = 5;
        }

        config.reverseResolution = prefs.getBoolean(REVERSE_RESOLUTION_PREF_STRING, DEFAULT_REVERSE_RESOLUTION);
        config.rotableScreen = prefs.getBoolean(ROTABLE_SCREEN_PREF_STRING, DEFAULT_ROTABLE_SCREEN);

        // 如果启用了分辨率反转，则交换宽度和高度
        if (config.reverseResolution) {
            int temp = config.width;
            config.width = config.height;
            config.height = temp;
        }

        // 读取画面位置设置
        String posString = prefs.getString(SCREEN_POSITION_PREF_STRING, DEFAULT_SCREEN_POSITION);
        switch (posString) {
            case "top_left":
                config.screenPosition = ScreenPosition.TOP_LEFT;
                break;
            case "top_center":
                config.screenPosition = ScreenPosition.TOP_CENTER;
                break;
            case "top_right":
                config.screenPosition = ScreenPosition.TOP_RIGHT;
                break;
            case "center_left":
                config.screenPosition = ScreenPosition.CENTER_LEFT;
                break;
            case "center_right":
                config.screenPosition = ScreenPosition.CENTER_RIGHT;
                break;
            case "bottom_left":
                config.screenPosition = ScreenPosition.BOTTOM_LEFT;
                break;
            case "bottom_center":
                config.screenPosition = ScreenPosition.BOTTOM_CENTER;
                break;
            case "bottom_right":
                config.screenPosition = ScreenPosition.BOTTOM_RIGHT;
                break;
            default:
                config.screenPosition = ScreenPosition.CENTER;
                break;
        }
        
        // 读取偏移百分比
        config.screenOffsetX = prefs.getInt(SCREEN_OFFSET_X_PREF_STRING, DEFAULT_SCREEN_OFFSET_X);
        config.screenOffsetY = prefs.getInt(SCREEN_OFFSET_Y_PREF_STRING, DEFAULT_SCREEN_OFFSET_Y);
        
        config.useExternalDisplay = prefs.getBoolean("use_external_display", false);

        // Runtime-only defaults; controlled via in-stream GameMenu
        config.gyroToRightStick = false;
        config.gyroFullDeflectionDps = 180.0f;

        return config;
    }

    public boolean writePreferences(Context context) {
        return writePreferences(context, false);
    }

    /**
     * 写入设置到SharedPreferences
     * @param context 上下文
     * @param synchronous 是否同步写入（true使用commit，false使用apply）
     * @return 是否成功
     */
    public boolean writePreferences(Context context, boolean synchronous) {
        if (context == null) {
            return false;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        try {
            // 转换枚举为字符串
            String positionString;
            switch (screenPosition) {
                case TOP_LEFT:
                    positionString = "top_left";
                    break;
                case TOP_CENTER:
                    positionString = "top_center";
                    break;
                case TOP_RIGHT:
                    positionString = "top_right";
                    break;
                case CENTER_LEFT:
                    positionString = "center_left";
                    break;
                case CENTER_RIGHT:
                    positionString = "center_right";
                    break;
                case BOTTOM_LEFT:
                    positionString = "bottom_left";
                    break;
                case BOTTOM_CENTER:
                    positionString = "bottom_center";
                    break;
                case BOTTOM_RIGHT:
                    positionString = "bottom_right";
                    break;
                default:
                    positionString = "center";
                    break;
            }
            
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(RESOLUTION_PREF_STRING, width + "x" + height)
                    .putString(FPS_PREF_STRING, String.valueOf(fps))
                    .putInt(BITRATE_PREF_STRING, bitrate)
                    .putString(VIDEO_FORMAT_PREF_STRING, getVideoFormatPreferenceString(videoFormat))
                    .putBoolean(ENABLE_HDR_PREF_STRING, enableHdr)
                    .putBoolean(ENABLE_HDR_HIGH_BRIGHTNESS_PREF_STRING, enableHdrHighBrightness)
                    .putBoolean(ENABLE_PERF_OVERLAY_STRING, enablePerfOverlay)
                    .putBoolean(PERF_OVERLAY_LOCKED_STRING, perfOverlayLocked)
                    .putBoolean(REVERSE_RESOLUTION_PREF_STRING, reverseResolution)
                    .putBoolean(ROTABLE_SCREEN_PREF_STRING, rotableScreen)
                    .putBoolean(SHOW_BITRATE_CARD_PREF_STRING, showBitrateCard)
                    .putBoolean(SHOW_GYRO_CARD_PREF_STRING, showGyroCard)
                    .putBoolean(SHOW_QuickKeyCard, showQuickKeyCard)
                    .putString(SCREEN_POSITION_PREF_STRING, positionString)
                    .putInt(SCREEN_OFFSET_X_PREF_STRING, screenOffsetX)
                    .putInt(SCREEN_OFFSET_Y_PREF_STRING, screenOffsetY)
                    .putBoolean("use_external_display", useExternalDisplay)
                    .putBoolean(ENABLE_MIC_PREF_STRING, enableMic)
                    .putInt(MIC_BITRATE_PREF_STRING, micBitrate)
                    .putString(MIC_ICON_COLOR_PREF_STRING, micIconColor)
                    .putBoolean(ENABLE_ESC_MENU_PREF_STRING, enableEscMenu)
                    .putString(ESC_MENU_KEY_PREF_STRING, String.valueOf(escMenuKey))
                    .putBoolean(CONTROL_ONLY_PREF_STRING, controlOnly)
                    .putBoolean(ENABLE_NATIVE_MOUSE_POINTER_PREF_STRING, enableNativeMousePointer)
                    .putBoolean(ENABLE_DOUBLE_CLICK_DRAG_PREF_STRING, enableDoubleClickDrag)
                    .putBoolean(ENABLE_LOCAL_CURSOR_RENDERING_PREF_STRING, enableLocalCursorRendering)
                    .putFloat(GYRO_SENSITIVITY_MULTIPLIER_PREF_STRING, gyroSensitivityMultiplier)
                    .putBoolean(GYRO_INVERT_X_AXIS_PREF_STRING, gyroInvertXAxis)
                    .putBoolean(GYRO_INVERT_Y_AXIS_PREF_STRING, gyroInvertYAxis)
                    .putInt(GYRO_ACTIVATION_KEY_CODE_PREF_STRING, gyroActivationKeyCode);
            
            if (synchronous) {
                return editor.commit();
            } else {
                editor.apply();
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public PreferenceConfiguration copy() {
        PreferenceConfiguration copy = new PreferenceConfiguration();
        copy.width = this.width;
        copy.height = this.height;
        copy.fps = this.fps;
        copy.bitrate = this.bitrate;
        copy.videoFormat = this.videoFormat;
        copy.enableHdr = this.enableHdr;
        copy.enableHdrHighBrightness = this.enableHdrHighBrightness;
        copy.enablePerfOverlay = this.enablePerfOverlay;
        copy.perfOverlayLocked = this.perfOverlayLocked;
        copy.perfOverlayOrientation = this.perfOverlayOrientation;
        copy.perfOverlayPosition = this.perfOverlayPosition;
        copy.reverseResolution = this.reverseResolution;
        copy.rotableScreen = this.rotableScreen;
        copy.screenPosition = this.screenPosition;
        copy.screenOffsetX = this.screenOffsetX;
        copy.screenOffsetY = this.screenOffsetY;
        copy.useExternalDisplay = this.useExternalDisplay;
        copy.enableMic = this.enableMic;
        copy.controlOnly = this.controlOnly;
        copy.outputBufferQueueLimit = this.outputBufferQueueLimit;
        copy.micBitrate = this.micBitrate;
        copy.micIconColor = this.micIconColor;
        copy.enableEscMenu = this.enableEscMenu;
        copy.escMenuKey = this.escMenuKey;
        copy.enableNativeMousePointer = this.enableNativeMousePointer;
        copy.enableDoubleClickDrag = this.enableDoubleClickDrag;
        copy.enableLocalCursorRendering = this.enableLocalCursorRendering;
        copy.gyroToRightStick = this.gyroToRightStick;
        copy.gyroFullDeflectionDps = this.gyroFullDeflectionDps;
        copy.gyroSensitivityMultiplier = this.gyroSensitivityMultiplier;
        copy.gyroActivationKeyCode = this.gyroActivationKeyCode;
        copy.gyroInvertXAxis = this.gyroInvertXAxis;
        copy.gyroInvertYAxis = this.gyroInvertYAxis;
        copy.showBitrateCard = this.showBitrateCard;
        copy.showGyroCard = this.showGyroCard;
        copy.showQuickKeyCard = this.showQuickKeyCard;
        return copy;
    }
}
