package com.limelight.binding.input.evdev

import android.app.Activity
import com.limelight.BuildConfig
import com.limelight.binding.input.capture.InputCaptureProvider

object EvdevCaptureProviderShim {
    @JvmStatic
    fun isCaptureProviderSupported(): Boolean {
        return BuildConfig.ROOT_BUILD
    }

    // We need to construct our capture provider using reflection because it isn't included in non-root builds
    @JvmStatic
    fun createEvdevCaptureProvider(activity: Activity, listener: EvdevListener): InputCaptureProvider {
        try {
            val providerClass = Class.forName("com.limelight.binding.input.evdev.EvdevCaptureProvider")
            return providerClass.constructors[0].newInstance(activity, listener) as InputCaptureProvider
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }
}
