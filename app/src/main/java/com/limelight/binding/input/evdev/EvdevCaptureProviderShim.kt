package com.limelight.binding.input.evdev

import android.app.Activity
import com.limelight.BuildConfig
import com.limelight.binding.input.capture.InputCaptureProvider

object EvdevCaptureProviderShim {
    fun isCaptureProviderSupported(): Boolean {
        return BuildConfig.ROOT_BUILD
    }

    // We need to construct our capture provider using reflection because it isn't included in non-root builds
    fun createEvdevCaptureProvider(
        activity: Activity,
        listener: EvdevListener,
        optimizeHardwareTouchpad: Boolean
    ): InputCaptureProvider {
        try {
            val providerClass = Class.forName("com.limelight.binding.input.evdev.EvdevCaptureProvider")
            return providerClass.getConstructor(
                Activity::class.java,
                EvdevListener::class.java,
                Boolean::class.javaPrimitiveType!!
            ).newInstance(activity, listener, optimizeHardwareTouchpad) as InputCaptureProvider
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }
}
