package com.limelight.binding.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.limelight.LimeLog
import java.nio.ByteBuffer

internal enum class HdrStreamState {
    UNKNOWN,
    DISABLED,
    ENABLED,
}

internal data class Hdr10PlusRuntimeSnapshot(
    val streamState: HdrStreamState,
    val configured: Boolean,
    val queryEnabled: Boolean,
    val metadataObserved: Boolean,
    val metadata: Hdr10PlusMetadataSnapshot,
)

/** Owns HDR10+ output sampling and stream-state transitions across codec lifecycles. */
internal class Hdr10PlusOutputObserver {
    private companion object {
        private const val INITIAL_PROBE_FRAMES = 120L
        private const val SAMPLE_INTERVAL_FRAMES = 60L
        private const val QUERY_FAILURE_BACKOFF_FRAMES = 60
    }

    private val lock = Any()
    private val metadataTracker = Hdr10PlusMetadataTracker()

    private var streamState = HdrStreamState.UNKNOWN
    private var configured = false
    private var queryEnabled = false
    private var metadataObserved = false
    private var outputFramesObserved = 0L
    private var queryBackoffFrames = 0

    fun beginCodecConfiguration(configuredAsHdr10Plus: Boolean) = synchronized(lock) {
        configured = configuredAsHdr10Plus
        resetObservationLocked()
    }

    fun restartCodecConfiguration() = synchronized(lock) {
        resetObservationLocked()
    }

    fun onCodecStarted() = synchronized(lock) {
        queryEnabled = configured && streamState != HdrStreamState.DISABLED
    }

    fun onHostHdrMode(enabled: Boolean) = synchronized(lock) {
        val previousEnabled = when (streamState) {
            HdrStreamState.ENABLED -> true
            HdrStreamState.DISABLED -> false
            HdrStreamState.UNKNOWN -> null
        }
        val resetObservation = HdrObservationEpochPolicy.shouldReset(previousEnabled, enabled)
        if (enabled) {
            if (resetObservation) {
                resetObservationLocked()
            }
            streamState = HdrStreamState.ENABLED
            queryEnabled = configured
        } else {
            streamState = HdrStreamState.DISABLED
            if (resetObservation) {
                resetObservationLocked()
            } else {
                queryEnabled = false
            }
        }
    }

    fun observeOutput(
        codec: MediaCodec,
        bufferIndex: Int,
        presentationTimeUs: Long,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        synchronized(lock) {
            if (!configured || !queryEnabled) return

            outputFramesObserved++
            if (queryBackoffFrames > 0) {
                queryBackoffFrames--
                return
            }

            val shouldProbe = if (metadataObserved) {
                outputFramesObserved % SAMPLE_INTERVAL_FRAMES == 0L
            } else {
                outputFramesObserved <= INITIAL_PROBE_FRAMES ||
                    outputFramesObserved % SAMPLE_INTERVAL_FRAMES == 0L
            }
            if (!shouldProbe) return

            try {
                val metadata = codec.getOutputFormat(bufferIndex)
                    .getByteBuffer(MediaFormat.KEY_HDR10_PLUS_INFO)
                recordMetadataLocked(metadata, presentationTimeUs)
            } catch (e: RuntimeException) {
                queryBackoffFrames = QUERY_FAILURE_BACKOFF_FRAMES
                if (metadataTracker.recordQueryFailure()) {
                    LimeLog.warning(
                        "Failed to query per-frame HDR10+ metadata: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
        }
    }

    internal fun recordMetadata(metadata: ByteBuffer?, presentationTimeUs: Long) = synchronized(lock) {
        recordMetadataLocked(metadata, presentationTimeUs)
    }

    fun snapshot(): Hdr10PlusRuntimeSnapshot = synchronized(lock) {
        val metadataSnapshot = metadataTracker.snapshot()
        Hdr10PlusRuntimeSnapshot(
            streamState = streamState,
            configured = configured,
            queryEnabled = queryEnabled,
            metadataObserved = metadataSnapshot.metadataFrames > 0,
            metadata = metadataSnapshot,
        )
    }

    private fun resetObservationLocked() {
        queryEnabled = false
        metadataObserved = false
        outputFramesObserved = 0
        queryBackoffFrames = 0
        metadataTracker.reset()
    }

    private fun recordMetadataLocked(metadata: ByteBuffer?, presentationTimeUs: Long) {
        when (metadataTracker.recordMetadata(metadata, presentationTimeUs)) {
            Hdr10PlusMetadataObservation.FIRST -> {
                metadataObserved = true
                LimeLog.info(
                    "HDR10+ dynamic metadata active: size=${metadataTracker.lastMetadataSize} " +
                        "hash=0x${Integer.toHexString(metadataTracker.lastMetadataHash)}"
                )
            }
            else -> Unit
        }
    }
}
