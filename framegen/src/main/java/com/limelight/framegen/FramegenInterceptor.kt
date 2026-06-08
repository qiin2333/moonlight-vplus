package com.limelight.framegen

import android.os.Build
import android.util.Log

/**
 * Java entry point for the Android frame generation pipeline.
 *
 * The decoder can be redirected into an ImageReader, whose HardwareBuffer frames are
 * handed to native code for YUV conversion, LSFG frame generation, and presentation
 * back to the original SurfaceView. All public configuration methods are defensive:
 * unsupported devices or native failures fall back to the normal decoder path.
 */
class FramegenInterceptor {
    /** Verify that the framegen native library is present and callable. */
    fun selfTest(): String {
        if (!isAvailable()) {
            return "unavailable (sdk=${Build.VERSION.SDK_INT}, abi=${Build.SUPPORTED_ABIS.joinToString()})"
        }
        return try {
            nativeSelfTest()
        } catch (t: UnsatisfiedLinkError) {
            Log.e(TAG, "native lib not loaded", t)
            "missing-native"
        }
    }

    /** Validate that the user-provided Lossless.dll can be parsed and translated. */
    fun probeLosslessDll(dllPath: String): String {
        if (!isAvailable()) {
            return "unavailable (sdk=${Build.VERSION.SDK_INT}, abi=${Build.SUPPORTED_ABIS.joinToString()})"
        }
        return try {
            nativeProbeLosslessDll(dllPath)
        } catch (t: UnsatisfiedLinkError) {
            Log.e(TAG, "native probe missing", t)
            "missing-native"
        } catch (t: Throwable) {
            Log.e(TAG, "native probe failed", t)
            "probe-exception: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // Native entry points mirrored by framegen/src/main/cpp/jni_bridge.cpp.
    private external fun nativeSelfTest(): String
    private external fun nativeProbeLosslessDll(dllPath: String): String

    companion object {
        private const val TAG = "Framegen"

        @Volatile
        private var libLoaded: Boolean = false

        init {
            try {
                System.loadLibrary("moonlight-framegen")
                libLoaded = true
            } catch (t: UnsatisfiedLinkError) {
                Log.w(TAG, "libmoonlight-framegen.so is not loaded; framegen unavailable: ${t.message}")
                libLoaded = false
            }
        }

        @JvmStatic
        fun isAvailable(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            if (Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" }) return false
            return libLoaded
        }

        @JvmStatic
        fun configureLosslessDllPath(dllPath: String?) {
            if (!isAvailable() || dllPath.isNullOrBlank()) return
            try {
                nativeSetLosslessDllPath(dllPath)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to configure Lossless.dll path", t)
            }
        }

        @JvmStatic
        fun configureHdrEnabled(enabled: Boolean) {
            if (!isAvailable()) return
            try {
                nativeSetHdrEnabled(enabled)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to configure framegen HDR flag", t)
            }
        }

        @JvmStatic
        fun configureOutputFrameRate(fps: Int) {
            if (!isAvailable()) return
            try {
                nativeSetOutputFrameRate(fps.coerceAtLeast(0))
            } catch (t: Throwable) {
                Log.w(TAG, "failed to configure framegen output FPS", t)
            }
        }

        @JvmStatic
        fun configureTuning(
            internalWidth: Int,
            presentMode: Int,
            slowLsfgThresholdMs: Int,
            presentQueueMax: Int
        ) {
            if (!isAvailable()) return
            try {
                nativeSetTuningConfig(
                    internalWidth.coerceIn(0, 1920),
                    if (presentMode == 1) 1 else 0,
                    slowLsfgThresholdMs.coerceIn(0, 100),
                    presentQueueMax.coerceIn(1, 12)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "failed to configure framegen tuning", t)
            }
        }

        @JvmStatic
        fun configureOutputSurface(surface: android.view.Surface?) {
            if (!isAvailable()) return
            try {
                nativeSetOutputSurface(surface)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to configure framegen output surface", t)
            }
        }

        @JvmStatic
        fun prewarmContext(width: Int, height: Int): Boolean {
            if (!isAvailable()) return false
            return try {
                nativePrewarmContext(width, height)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to prewarm framegen context", t)
                false
            }
        }

        /**
         * Hand a decoded HardwareBuffer frame to native code. The native side does not
         * retain this Java-owned buffer beyond the call; FramegenCapture closes the Image.
         */
        @JvmStatic
        external fun nativeOnFrameAvailable(
            hwBuffer: android.hardware.HardwareBuffer,
            width: Int,
            height: Int,
            format: Int,
            timestampNs: Long,
            observedInputFps: Float
        ): Long

        @JvmStatic
        external fun nativeResetFrameCounter()

        @JvmStatic
        private external fun nativePrewarmContext(width: Int, height: Int): Boolean

        @JvmStatic
        private external fun nativeSetLosslessDllPath(dllPath: String)

        @JvmStatic
        private external fun nativeSetHdrEnabled(enabled: Boolean)

        @JvmStatic
        private external fun nativeSetOutputFrameRate(fps: Int)

        @JvmStatic
        private external fun nativeSetTuningConfig(
            internalWidth: Int,
            presentMode: Int,
            slowLsfgThresholdMs: Int,
            presentQueueMax: Int
        )

        @JvmStatic
        private external fun nativeSetOutputSurface(surface: android.view.Surface?)
    }
}
