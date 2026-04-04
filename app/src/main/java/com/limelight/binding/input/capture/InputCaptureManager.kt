package com.limelight.binding.input.capture

import android.app.Activity
import android.view.View
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
     *
     * 外接显示器模式下，主 Activity 的 surfaceView 被设为 GONE，
     * requestPointerCapture() 对隐藏 View 会失败。
     * 因此回退到标准捕获时，使用仍然可见的 backgroundTouchView 作为捕获目标。
     */
    @JvmStatic
    fun getInputCaptureProviderForExternalDisplay(activity: Activity, rootListener: EvdevListener): InputCaptureProvider {
        // 外接显示器模式下，优先使用Evdev捕获，因为它对多显示器支持更好
        if (EvdevCaptureProviderShim.isCaptureProviderSupported()) {
            LimeLog.info("Using Evdev mouse capture for external display")
            return EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, rootListener)
        }

        // Evdev 不可用时，使用 backgroundTouchView（在主屏上仍然可见）作为指针捕获目标
        val captureView: View = activity.findViewById(R.id.backgroundTouchView)
            ?: activity.window.decorView

        return when {
            AndroidNativePointerCaptureProvider.isCaptureProviderSupported() -> {
                LimeLog.info("Using Android O+ native mouse capture for external display (backgroundTouchView)")
                AndroidNativePointerCaptureProvider(activity, captureView)
            }
            AndroidPointerIconCaptureProvider.isCaptureProviderSupported() -> {
                LimeLog.info("Using Android N+ pointer hiding for external display")
                AndroidPointerIconCaptureProvider(activity, captureView)
            }
            else -> {
                LimeLog.info("Mouse capture not available for external display")
                NullCaptureProvider()
            }
        }
    }
}
