package com.limelight.binding.video

import android.view.Surface
import com.limelight.LimeLog
import com.limelight.framegen.FramegenInterceptor
import com.limelight.nvstream.jni.MoonBridge

internal data class PyrowaveFrameTimings(
    val decodeTimeUs: Long,
    val presentTimeUs: Long,
) {
    val isValid: Boolean
        get() = decodeTimeUs > 0L || presentTimeUs > 0L
}

/**
 * Owns the native PyroWave handle and its bounded recovery state.
 *
 * The native decoder is synchronous, so all handle, Surface, and recovery
 * transitions are serialized here. The failure callback is invoked after the
 * lock is released so connection teardown cannot deadlock a submit caller.
 */
internal class PyrowaveDecoderSession(
    private val native: PyrowaveNativeApi = MoonBridgePyrowaveNativeApi,
    private val gpuNative: PyrowaveNativeApi? = null,
    private val maxRecoveryAttempts: Int = DEFAULT_MAX_RECOVERY_ATTEMPTS,
    private val onFatalFailure: (String) -> Unit = {},
) {
    companion object {
        const val DEFAULT_MAX_RECOVERY_ATTEMPTS = 3
        internal const val NATIVE_SUBMIT_FRAME_DROPPED = 1
    }

    enum class SubmitResult {
        SUCCESS,
        FRAME_DROPPED,
        SURFACE_UNAVAILABLE,
        RECOVERED,
        RETRYING,
        INACTIVE,
        FATAL,
    }

    private val lock = Any()
    private var handle = 0L
    private var width = 0
    private var height = 0
    private data class SurfaceState(val surface: Surface?, val generation: Long)

    private val surfaceLock = Any()
    @Volatile
    private var surfaceState = SurfaceState(null, 0)
    private var boundSurfaceGeneration = -1L
    private var recoveryAttempts = 0
    private var fatalFailureReported = false
    private var usingGpu = false
    private var requestedHdrMode = 0
    private var requestedFullRange = true
    private var requestedDynamicHdrFormat = 0
    private var targetPeakNits = 1000f
    private var hdrEnabled = false
    private var hdrMetadata: ByteArray? = null
    private var invalidHdrMetadataLogged = false
    private var lastNativeFailure: String? = null
    private var lastFrameTimings = PyrowaveFrameTimings(0L, 0L)
    private var dynamicMetadataApplied = false

    val appliedDynamicHdrFormat: Int
        get() = synchronized(lock) { if (dynamicMetadataApplied) requestedDynamicHdrFormat else 0 }

    val isActive: Boolean
        get() = synchronized(lock) { handle != 0L }

    val isGpu: Boolean
        get() = synchronized(lock) { handle != 0L && usingGpu }

    val latestFrameTimings: PyrowaveFrameTimings
        get() = synchronized(lock) { lastFrameTimings }

    fun getProbeTimings(): LongArray? = synchronized(lock) {
        if (handle == 0L || !usingGpu) null else gpuNative?.getProbeTimings(handle)
    }

    /**
     * Surface callbacks only publish the latest target. The decoder worker
     * applies it before the next frame, without holding the native-operation
     * lock on the Activity thread.
     */
    fun setSurface(surface: Surface?) {
        synchronized(surfaceLock) {
            surfaceState = SurfaceState(surface, surfaceState.generation + 1)
        }
        // Do not call into Vulkan from a Surface callback. The native bridge
        // owns an acquired ANativeWindow reference, so the old window remains
        // valid until the decoder worker observes this generation (or the
        // session is destroyed). This keeps Surface destruction non-blocking
        // even when submit() is waiting for the GPU.
    }

    fun create(
        width: Int,
        height: Int,
        hdrMode: Int = 0,
        fullRange: Boolean = true,
        hdrEnabled: Boolean = hdrMode != 0,
        hdrMetadata: ByteArray? = null,
        dynamicHdrFormat: Int = 0,
        targetPeakNits: Float = 1000f,
    ): Boolean {
        synchronized(lock) {
            val candidateHdrEnabled = hdrEnabled && hdrMode != 0
            // The argument seeds initial creation only. Once a control update
            // has been accepted, this owner is authoritative for recovery;
            // a renderer-side copy may be older than that update.
            val candidateHdrMetadata = PyrowaveHdrMetadata.updatedSnapshot(
                this.hdrMetadata, candidateHdrEnabled, if (this.hdrMetadata == null) hdrMetadata else null,
            )
            val gpuReplacement = gpuNative?.let {
                safeCreate(it, width, height, hdrMode, fullRange, dynamicHdrFormat)
            } ?: 0L
            val target = surfaceState
            var replacement = gpuReplacement
            var replacementUsesGpu = gpuReplacement != 0L
            var replacementNative: PyrowaveNativeApi? = if (replacementUsesGpu) gpuNative else null
            if (replacement != 0L &&
                (!safeSetHdrMetadata(replacementNative, replacement, candidateHdrEnabled, candidateHdrMetadata) ||
                 !safeSetDynamicHdr(replacementNative, replacement, dynamicHdrFormat, targetPeakNits) ||
                 !safeSetSurface(replacementNative, replacement, target.surface))) {
                safeDestroy(replacementNative, replacement)
                replacement = 0L
                replacementUsesGpu = false
            }
            if (replacement == 0L && hdrMode == 0 && dynamicHdrFormat == 0) {
                replacement = safeCreate(native, width, height, hdrMode, fullRange, dynamicHdrFormat)
                replacementNative = native
            }
            if (replacement == 0L) {
                return false
            }
            if (!replacementUsesGpu &&
                (!safeSetHdrMetadata(replacementNative, replacement, candidateHdrEnabled, candidateHdrMetadata) ||
                 !safeSetDynamicHdr(replacementNative, replacement, dynamicHdrFormat, targetPeakNits) ||
                 !safeSetSurface(replacementNative, replacement, target.surface))) {
                safeDestroy(replacementNative, replacement)
                return false
            }

            val previous = handle
            val previousNative = if (usingGpu) gpuNative else native
            this.width = width
            this.height = height
            this.hdrEnabled = candidateHdrEnabled
            this.hdrMetadata = candidateHdrMetadata
            requestedHdrMode = hdrMode
            requestedFullRange = fullRange
            requestedDynamicHdrFormat = dynamicHdrFormat
            this.targetPeakNits = targetPeakNits
            handle = replacement
            dynamicMetadataApplied = false
            usingGpu = replacementUsesGpu
            boundSurfaceGeneration = target.generation
            lastFrameTimings = PyrowaveFrameTimings(0L, 0L)
            if (previous != 0L) {
                safeDestroy(previousNative, previous)
            }
            return true
        }
    }

    /**
     * Performs the same GPU/Surface/CPU selection as a real create, then tears
     * the candidate down. This is called after the Surface is attached and
     * before RTSP starts, so a swapchain failure cannot be negotiated as a
     * usable PyroWave stream.
     */
    fun canCreate(width: Int, height: Int, hdrMode: Int, fullRange: Boolean,
                  dynamicHdrFormat: Int = 0, targetPeakNits: Float = 1000f): Boolean {
        synchronized(lock) {
            if (handle != 0L) return true
            val generation = surfaceState.generation
            if (!create(width, height, hdrMode, fullRange,
                        dynamicHdrFormat = dynamicHdrFormat, targetPeakNits = targetPeakNits)) return false
            destroyLocked()
            return generation == surfaceState.generation
        }
    }

    fun destroy() {
        synchronized(lock) {
            destroyLocked()
        }
    }

    /** Apply the latest Sunshine static HDR metadata to a live PyroWave session. */
    fun setHdrMetadata(enabled: Boolean, metadata: ByteArray?): Boolean {
        synchronized(lock) {
            val candidate = PyrowaveHdrMetadata.updatedSnapshot(hdrMetadata, enabled, metadata)
            if (enabled && !PyrowaveHdrMetadata.isAbsent(metadata) &&
                PyrowaveHdrMetadata.fromSunshineBytes(metadata) == null
            ) {
                if (!invalidHdrMetadataLogged) {
                    LimeLog.warning("Invalid Sunshine static HDR metadata; keeping the last valid PyroWave snapshot")
                    invalidHdrMetadataLogged = true
                }
            } else if (!enabled || PyrowaveHdrMetadata.fromSunshineBytes(metadata) != null) {
                invalidHdrMetadataLogged = false
            }
            if (handle != 0L && !safeSetHdrMetadata(
                    if (usingGpu) gpuNative else native, handle, enabled, candidate,
                )) return false
            hdrEnabled = enabled
            hdrMetadata = candidate
            if (!enabled) dynamicMetadataApplied = false
            return true
        }
    }

    fun reset() {
        synchronized(lock) {
            destroyLocked()
            recoveryAttempts = 0
            fatalFailureReported = false
            // A new setup/Resume is a new video session. Do not let a static
            // HDR snapshot accepted by the previous connection seed the next
            // decoder before the current control stream supplies its metadata.
            // The renderer-owned snapshot is passed back through create() when
            // it is still valid for the new connection.
            hdrEnabled = false
            hdrMetadata = null
            invalidHdrMetadataLogged = false
            requestedHdrMode = 0
            requestedFullRange = true
            requestedDynamicHdrFormat = 0
            targetPeakNits = 1000f
            lastNativeFailure = null
        }
    }

    fun noteRecoveryFailure(reason: String): Boolean {
        var fatal = false
        synchronized(lock) {
            recoveryAttempts++
            if (recoveryAttempts >= maxRecoveryAttempts && !fatalFailureReported) {
                fatalFailureReported = true
                fatal = true
            }
        }
        if (fatal) {
            onFatalFailure(reason)
        }
        return fatal
    }

    fun submit(data: ByteArray, length: Int, frameMetadata: ByteArray? = null): SubmitResult {
        var result = SubmitResult.INACTIVE
        var fatalReason: String? = null
        synchronized(lock) {
            val activeNative = if (usingGpu) gpuNative else native
            val target = surfaceState
            // A Surface recreation can destroy the native swapchain and leave
            // the session without a handle. Let the bounded recovery path
            // below recreate it on the decode worker instead of returning
            // INACTIVE forever until the connection is torn down.
            val surfaceReady = if (handle != 0L && boundSurfaceGeneration == target.generation) {
                true
            } else if (handle != 0L) {
                dynamicMetadataApplied = false
                safeSetSurface(activeNative, handle, target.surface).also { bound ->
                    if (bound) boundSurfaceGeneration = target.generation
                }
            } else {
                false
            }
            if (handle != 0L && surfaceReady && target.generation != 0L && target.surface == null) {
                return SubmitResult.SURFACE_UNAVAILABLE
            }
            val submitResult = if (handle != 0L && surfaceReady) {
                safeSubmit(activeNative, handle, data, length,
                           if (requestedDynamicHdrFormat != 0) frameMetadata else null)
            } else {
                -1
            }
            if (submitResult == NATIVE_SUBMIT_FRAME_DROPPED) {
                // The native bridge cleared only the incomplete frame. Keep
                // the decoder, Surface, and last presented frame in place.
                return SubmitResult.FRAME_DROPPED
            }
            if (surfaceReady && submitResult == 0) {
                // A frame has decoded and the native bridge has completed its
                // presentation attempt. Only this event clears failures.
                recoveryAttempts = 0
                lastFrameTimings = safeGetTimings(activeNative, handle)
                dynamicMetadataApplied = usingGpu && requestedDynamicHdrFormat != 0
                return SubmitResult.SUCCESS
            }

            destroyLocked()
            recoveryAttempts++
            val gpuReplacement = gpuNative?.let {
                safeCreate(it, width, height, requestedHdrMode, requestedFullRange, requestedDynamicHdrFormat)
            } ?: 0L
            val replacementTarget = surfaceState
            var replacement = gpuReplacement
            var replacementUsesGpu = gpuReplacement != 0L
            var replacementNative: PyrowaveNativeApi? = if (replacementUsesGpu) gpuNative else null
            if (replacement != 0L &&
                (!safeSetHdrMetadata(replacementNative, replacement, hdrEnabled, hdrMetadata) ||
                 !safeSetDynamicHdr(replacementNative, replacement, requestedDynamicHdrFormat, targetPeakNits) ||
                 !safeSetSurface(replacementNative, replacement, replacementTarget.surface))) {
                safeDestroy(replacementNative, replacement)
                replacement = 0L
                replacementUsesGpu = false
            }
            if (replacement == 0L && requestedHdrMode == 0 && requestedDynamicHdrFormat == 0) {
                replacement = safeCreate(native, width, height, requestedHdrMode, requestedFullRange,
                    requestedDynamicHdrFormat)
                replacementNative = native
            }
            if (replacement != 0L &&
                (replacementUsesGpu ||
                    (safeSetHdrMetadata(replacementNative, replacement, hdrEnabled, hdrMetadata) &&
                     safeSetDynamicHdr(replacementNative, replacement, requestedDynamicHdrFormat, targetPeakNits) &&
                     safeSetSurface(replacementNative, replacement, replacementTarget.surface)))) {
                handle = replacement
                usingGpu = replacementUsesGpu
                boundSurfaceGeneration = replacementTarget.generation
                result = SubmitResult.RECOVERED
            } else {
                if (replacement != 0L) {
                    safeDestroy(replacementNative, replacement)
                }
                result = SubmitResult.INACTIVE
            }

            if (recoveryAttempts >= maxRecoveryAttempts && !fatalFailureReported) {
                fatalFailureReported = true
                fatalReason = "PyroWave submit failed after $recoveryAttempts attempts"
                result = SubmitResult.FATAL
            } else if (result == SubmitResult.INACTIVE) {
                result = SubmitResult.RETRYING
            }
        }

        if (fatalReason != null) {
            onFatalFailure(fatalReason!!)
        }
        return result
    }

    private fun destroyLocked() {
        dynamicMetadataApplied = false
        if (handle != 0L) {
            safeDestroy(if (usingGpu) gpuNative else native, handle)
            handle = 0L
            usingGpu = false
            boundSurfaceGeneration = -1L
            lastFrameTimings = PyrowaveFrameTimings(0L, 0L)
        }
    }

    private fun logNativeFailure(operation: String, detail: String) {
        val message = "$operation failed: $detail"
        if (lastNativeFailure != message) {
            lastNativeFailure = message
            LimeLog.warning("PyroWave decoder $message")
        }
    }

    private fun clearNativeFailure() {
        lastNativeFailure = null
    }

    private fun safeCreate(api: PyrowaveNativeApi, width: Int, height: Int, hdrMode: Int,
                           fullRange: Boolean, dynamicHdrFormat: Int): Long {
        return try {
            val created = api.create(width, height, hdrMode, fullRange, dynamicHdrFormat)
            if (created == 0L) {
                logNativeFailure("create", "native returned no handle for ${width}x$height hdr=$hdrMode")
            } else {
                clearNativeFailure()
            }
            created
        } catch (error: RuntimeException) {
            logNativeFailure("create", describe(error))
            0L
        }
    }

    private fun safeSetSurface(api: PyrowaveNativeApi?, handle: Long, surface: Surface?): Boolean {
        return try {
            if (api == null) {
                logNativeFailure("setSurface", "native API is unavailable")
                false
            } else {
                val bound = api.setSurface(handle, surface)
                if (surface != null && !bound) {
                    logNativeFailure("setSurface", "native surface binding returned false")
                } else {
                    clearNativeFailure()
                }
                surface == null || bound
            }
        } catch (error: RuntimeException) {
            logNativeFailure("setSurface", describe(error))
            false
        }
    }

    private fun safeSetHdrMetadata(
        api: PyrowaveNativeApi?,
        handle: Long,
        enabled: Boolean,
        metadata: ByteArray?,
    ): Boolean = try {
        val applied = api?.setHdrMetadata(handle, enabled, metadata) ?: false
        if (!applied) {
            logNativeFailure("setHdrMetadata", "native metadata update returned false")
        } else {
            clearNativeFailure()
        }
        applied
    } catch (error: RuntimeException) {
        logNativeFailure("setHdrMetadata", describe(error))
        false
    }

    private fun safeSetDynamicHdr(api: PyrowaveNativeApi?, handle: Long, format: Int, peak: Float): Boolean = try {
        val configured = api?.setDynamicHdr(handle, format, peak) ?: false
        if (!configured) logNativeFailure("setDynamicHdr", "format=$format targetPeak=$peak")
        configured
    } catch (error: RuntimeException) {
        logNativeFailure("setDynamicHdr", describe(error))
        false
    }

    private fun safeSubmit(api: PyrowaveNativeApi?, handle: Long, data: ByteArray, length: Int,
                           frameMetadata: ByteArray?): Int {
        return try {
            val result = api?.submitFrame(handle, data, length, frameMetadata) ?: -1
            if (result == NATIVE_SUBMIT_FRAME_DROPPED) {
                clearNativeFailure()
            } else if (result != 0) {
                logNativeFailure("submit", "native returned $result for $length bytes")
            } else {
                clearNativeFailure()
            }
            result
        } catch (error: RuntimeException) {
            logNativeFailure("submit", describe(error))
            -1
        }
    }

    private fun safeGetTimings(api: PyrowaveNativeApi?, handle: Long): PyrowaveFrameTimings {
        return try {
            val packed = api?.getTimings(handle) ?: 0L
            PyrowaveFrameTimings(
                decodeTimeUs = (packed ushr 32) and 0xffffffffL,
                presentTimeUs = packed and 0xffffffffL,
            )
        } catch (error: RuntimeException) {
            logNativeFailure("getTimings", describe(error))
            PyrowaveFrameTimings(0L, 0L)
        }
    }

    private fun safeDestroy(api: PyrowaveNativeApi?, handle: Long) {
        try {
            api?.destroy(handle)
        } catch (error: RuntimeException) {
            logNativeFailure("destroy", describe(error))
            // The handle is no longer exposed after this point. Teardown must
            // continue even if a vendor JNI wrapper reports an error.
        }
    }

    private fun describe(error: RuntimeException): String =
        "${error.javaClass.simpleName}: ${error.message ?: "no detail"}"
}

internal interface PyrowaveNativeApi {
    fun create(width: Int, height: Int, hdrMode: Int, fullRange: Boolean): Long
    fun create(width: Int, height: Int, hdrMode: Int, fullRange: Boolean,
               dynamicHdrFormat: Int): Long = create(width, height, hdrMode, fullRange)
    fun setSurface(handle: Long, surface: Surface?): Boolean
    fun submit(handle: Long, data: ByteArray, length: Int): Int
    fun submitFrame(handle: Long, data: ByteArray, length: Int, metadata: ByteArray?): Int =
        if (metadata == null || metadata.isEmpty()) submit(handle, data, length) else -1
    fun setDynamicHdr(handle: Long, format: Int, targetPeakNits: Float): Boolean = format == 0
    fun getTimings(handle: Long): Long
    fun getProbeTimings(handle: Long): LongArray? = null
    fun setHdrMetadata(handle: Long, enabled: Boolean, metadata: ByteArray?): Boolean
    fun destroy(handle: Long)
}

private object MoonBridgePyrowaveNativeApi : PyrowaveNativeApi {
    override fun create(width: Int, height: Int, hdrMode: Int, fullRange: Boolean): Long =
        MoonBridge.pyrowaveCreate(width, height, hdrMode, fullRange)

    override fun setSurface(handle: Long, surface: Surface?): Boolean {
        MoonBridge.pyrowaveSetSurface(handle, surface)
        return true
    }

    override fun submit(handle: Long, data: ByteArray, length: Int): Int =
        MoonBridge.pyrowaveSubmit(handle, data, length)

    override fun getTimings(handle: Long): Long = MoonBridge.pyrowaveGetLastTimings(handle)

    override fun setHdrMetadata(handle: Long, enabled: Boolean, metadata: ByteArray?): Boolean = true

    override fun destroy(handle: Long) {
        MoonBridge.pyrowaveDestroy(handle)
    }
}

internal object FramegenPyrowaveNativeApi : PyrowaveNativeApi {
    override fun create(width: Int, height: Int, hdrMode: Int, fullRange: Boolean): Long =
        FramegenInterceptor.createPyrowaveDecoder(width, height, hdrMode, fullRange)

    override fun create(width: Int, height: Int, hdrMode: Int, fullRange: Boolean,
                        dynamicHdrFormat: Int): Long =
        FramegenInterceptor.createPyrowaveDecoder(width, height, hdrMode, fullRange, dynamicHdrFormat)

    override fun setSurface(handle: Long, surface: Surface?): Boolean {
        return FramegenInterceptor.setPyrowaveDecoderSurface(handle, surface)
    }

    override fun submit(handle: Long, data: ByteArray, length: Int): Int =
        FramegenInterceptor.submitPyrowaveDecoder(handle, data, length)

    override fun submitFrame(handle: Long, data: ByteArray, length: Int, metadata: ByteArray?): Int =
        FramegenInterceptor.submitPyrowaveDecoder(handle, data, length, metadata)

    override fun setDynamicHdr(handle: Long, format: Int, targetPeakNits: Float): Boolean =
        FramegenInterceptor.setPyrowaveDecoderDynamicHdr(handle, format, targetPeakNits)

    override fun getTimings(handle: Long): Long =
        FramegenInterceptor.getPyrowaveDecoderTimings(handle)

    override fun getProbeTimings(handle: Long): LongArray? =
        FramegenInterceptor.getPyrowaveDecoderProbeTimings(handle)

    override fun setHdrMetadata(handle: Long, enabled: Boolean, metadata: ByteArray?): Boolean =
        FramegenInterceptor.setPyrowaveDecoderHdrMetadata(handle, enabled, metadata)

    override fun destroy(handle: Long) {
        FramegenInterceptor.destroyPyrowaveDecoder(handle)
    }
}
