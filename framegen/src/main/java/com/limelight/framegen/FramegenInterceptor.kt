package com.limelight.framegen

import android.os.Build
import android.util.Log

data class FramegenStats(
    val realFrames: Long,
    val interpolatedFrames: Long,
    val bypassFrames: Long,
    val realOnlyFrames: Long,
    val presenterDrops: Long,
    val fallbackFrames: Long,
    val queueDepth: Int,
    val outputFrameRate: Int,
    val inputFps: Float,
    val mode: Int,
    val lastLsfgWaitMs: Int,
    val lastBlitMs: Int
) {
    val presentedFrames: Long
        get() = realFrames + interpolatedFrames + bypassFrames + fallbackFrames

    val isGenerating: Boolean
        get() = interpolatedFrames > 0

    companion object {
        const val MODE_NORMAL = 0
        const val MODE_HIGH_INPUT_BYPASS = 1
        const val MODE_SLOW_COOLDOWN = 2
        const val MODE_CADENCE_RECOVER = 3
    }
}

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
        fun configureHdrMode(mode: Int, fullRange: Boolean) {
            if (!isAvailable()) return
            try {
                nativeSetHdrMode(mode.coerceIn(0, 2), fullRange)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to configure framegen HDR mode", t)
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
            presentQueueMax: Int,
            allowHighInputBypass: Boolean = false
        ) {
            if (!isAvailable()) return
            try {
                nativeSetTuningConfig(
                    internalWidth.coerceIn(0, 1920),
                    if (presentMode == 1) 1 else 0,
                    slowLsfgThresholdMs.coerceIn(0, 100),
                    presentQueueMax.coerceIn(1, 12),
                    allowHighInputBypass
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
        fun getStats(): FramegenStats? {
            if (!isAvailable()) return null
            return try {
                nativeGetStats().toFramegenStats()
            } catch (t: Throwable) {
                Log.w(TAG, "failed to read framegen stats", t)
                null
            }
        }

        @JvmStatic
        private external fun nativePrewarmContext(width: Int, height: Int): Boolean

        @JvmStatic
        private external fun nativeGetStats(): LongArray

        @JvmStatic
        private external fun nativeSetLosslessDllPath(dllPath: String)

        @JvmStatic
        private external fun nativeSetHdrMode(mode: Int, fullRange: Boolean)

        @JvmStatic
        private external fun nativeSetOutputFrameRate(fps: Int)

        @JvmStatic
        private external fun nativeSetTuningConfig(
            internalWidth: Int,
            presentMode: Int,
            slowLsfgThresholdMs: Int,
            presentQueueMax: Int,
            allowHighInputBypass: Boolean
        )

        @JvmStatic
        private external fun nativeSetOutputSurface(surface: android.view.Surface?)

        private fun LongArray.toFramegenStats(): FramegenStats? {
            if (size < 12) return null
            return FramegenStats(
                realFrames = this[0],
                interpolatedFrames = this[1],
                bypassFrames = this[2],
                realOnlyFrames = this[3],
                presenterDrops = this[4],
                fallbackFrames = this[5],
                queueDepth = this[6].toInt(),
                outputFrameRate = this[7].toInt(),
                inputFps = this[8].toFloat() / 10f,
                mode = this[9].toInt(),
                lastLsfgWaitMs = this[10].toInt(),
                lastBlitMs = this[11].toInt()
            )
        }
    }
}
