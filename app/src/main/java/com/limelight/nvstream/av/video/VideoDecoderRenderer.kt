package com.limelight.nvstream.av.video

abstract class VideoDecoderRenderer {
    abstract fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int

    abstract fun start()

    abstract fun stop()

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    abstract fun submitDecodeUnit(
        decodeUnitData: ByteArray, decodeUnitLength: Int, decodeUnitType: Int,
        frameNumber: Int, frameType: Int, frameHostProcessingLatency: Char,
        receiveTimeUs: Long, enqueueTimeUs: Long, hostPresentationTimeUs: Long
    ): Int

    abstract fun cleanup()

    abstract fun getCapabilities(): Int

    abstract fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?)

    // Surface-backed renderers override this. MediaCodec and PyroWave both
    // share the same SurfaceHolder owned by Game. A null target publishes a
    // destroyed Surface; the decoder worker performs the native detach before
    // accepting the next frame.
    open fun setRenderTarget(renderTarget: android.view.SurfaceHolder?) {
        // Default implementation for renderers that do not own a Surface.
    }

    /**
     * Checks the complete PyroWave renderer path after the output Surface exists.
     * Renderers which do not implement PyroWave must not advertise it to common-c.
     */
    open fun canInitializePyrowave(width: Int, height: Int, hdrMode: Int, fullRange: Boolean): Boolean = false

    /**
     * Publishes the result of the Surface-backed PyroWave preflight. The
     * connection performs this check on its worker thread before querying
     * capabilities, so capability reporting must not repeat Vulkan setup on
     * the Activity thread.
     */
    open fun setPyrowavePreflightResult(available: Boolean) {
        // Renderers without a PyroWave implementation have nothing to cache.
    }

    // Called when the host resolution changes (e.g., screen rotation)
    open fun onResolutionChanged(width: Int, height: Int) {
        // Default implementation does nothing
        // Subclasses can override to handle resolution changes
    }
}
