package com.limelight.binding.input.capture;

import android.app.Activity;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.evdev.EvdevCaptureProviderShim;
import com.limelight.binding.input.evdev.EvdevListener;

public class InputCaptureManager {
    public static InputCaptureProvider getInputCaptureProvider(Activity activity, EvdevListener rootListener) {
        if (AndroidNativePointerCaptureProvider.isCaptureProviderSupported()) {
            LimeLog.info("Using Android O+ native mouse capture");
            return new AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.surfaceView));
        }
        // LineageOS implemented broken NVIDIA capture extensions, so avoid using them on root builds.
        // See https://github.com/LineageOS/android_frameworks_base/commit/d304f478a023430f4712dbdc3ee69d9ad02cebd3
        else if (!BuildConfig.ROOT_BUILD && ShieldCaptureProvider.isCaptureProviderSupported()) {
            LimeLog.info("Using NVIDIA mouse capture extension");
            return new ShieldCaptureProvider(activity);
        }
        else if (EvdevCaptureProviderShim.isCaptureProviderSupported()) {
            LimeLog.info("Using Evdev mouse capture");
            return EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener);
        }
        else if (AndroidPointerIconCaptureProvider.isCaptureProviderSupported()) {
            // Android N's native capture can't capture over system UI elements
            // so we want to only use it if there's no other option.
            LimeLog.info("Using Android N+ pointer hiding");
            return new AndroidPointerIconCaptureProvider(activity, activity.findViewById(R.id.surfaceView));
        }
        else {
            LimeLog.info("Mouse capture not available");
            return new NullCaptureProvider();
        }
    }
    
    /**
     * 获取支持外接显示器的输入捕获提供者
     * 外接显示器模式下，使用更兼容的捕获方式
     */
    public static InputCaptureProvider getInputCaptureProviderForExternalDisplay(Activity activity, EvdevListener rootListener) {
        // 外接显示器模式下，优先使用Evdev捕获，因为它对多显示器支持更好
        if (EvdevCaptureProviderShim.isCaptureProviderSupported()) {
            LimeLog.info("Using Evdev mouse capture for external display");
            return EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener);
        }
        // 如果Evdev不可用，回退到标准方式
        else {
            LimeLog.info("Falling back to standard capture provider for external display");
            return getInputCaptureProvider(activity, rootListener);
        }
    }
}
