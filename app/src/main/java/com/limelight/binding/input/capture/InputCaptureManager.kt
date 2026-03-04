package com.limelight.binding.input.capture

import android.app.Activity
import com.limelight.BuildConfig
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.input.evdev.EvdevCaptureProviderShim
import com.limelight.binding.input.evdev.EvdevListener

object InputCaptureManager {
    @JvmStatic
    fun getInputCaptureProvider(activity: Activity, rootListener: EvdevListener): InputCaptureProvider {
        return when {
            AndroidNativePointerCaptureProvider.isCaptureProviderSupported() -> {
                LimeLog.info("Using Android O+ native mouse capture")
                AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.surfaceView))
            }
            // LineageOS implemented broken NVIDIA capture extensions, so avoid using them on root builds.
            !BuildConfig.ROOT_BUILD && ShieldCaptureProvider.isCaptureProviderSupported() -> {
                LimeLog.info("Using NVIDIA mouse capture extension")
                ShieldCaptureProvider(activity)
            }
            EvdevCaptureProviderShim.isCaptureProviderSupported() -> {
                LimeLog.info("Using Evdev mouse capture")
                EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener)
            }
            AndroidPointerIconCaptureProvider.isCaptureProviderSupported() -> {
                // Android N's native capture can't capture over system UI elements
                // so we want to only use it if there's no other option.
                LimeLog.info("Using Android N+ pointer hiding")
                AndroidPointerIconCaptureProvider(activity, activity.findViewById(R.id.surfaceView))
            }
            else -> {
                LimeLog.info("Mouse capture not available")
                NullCaptureProvider()
            }
        }
    }

    /**
     * 获取支持外接显示器的输入捕获提供者
     * 外接显示器模式下，使用更兼容的捕获方式
     */
    @JvmStatic
    fun getInputCaptureProviderForExternalDisplay(activity: Activity, rootListener: EvdevListener): InputCaptureProvider {
        // 外接显示器模式下，优先使用Evdev捕获，因为它对多显示器支持更好
        return if (EvdevCaptureProviderShim.isCaptureProviderSupported()) {
            LimeLog.info("Using Evdev mouse capture for external display")
            EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener)
        } else {
            // 如果Evdev不可用，回退到标准方式
            LimeLog.info("Falling back to standard capture provider for external display")
            getInputCaptureProvider(activity, rootListener)
        }
    }
}
