package com.limelight.binding.video

internal data class DecoderConfigurationSnapshot(
    val width: Int,
    val height: Int,
    val generation: Long,
)

/** Tracks codec dimensions across asynchronous stream resolution updates. */
internal class DecoderConfigurationTracker {
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var generation = 0L

    fun reset(width: Int, height: Int) {
        configuredWidth = 0
        configuredHeight = 0
        requestedWidth = width
        requestedHeight = height
        generation = 0L
    }

    fun updateResolution(width: Int, height: Int): Boolean {
        if (width == requestedWidth && height == requestedHeight) {
            return false
        }

        val exceedsConfiguredDimensions = DecoderInputBufferSizing.requiresReconfiguration(
            configuredWidth,
            configuredHeight,
            width,
            height,
        )
        val exceedsPendingDimensions = DecoderInputBufferSizing.requiresReconfiguration(
            requestedWidth,
            requestedHeight,
            width,
            height,
        )

        requestedWidth = width
        requestedHeight = height
        if (exceedsConfiguredDimensions && exceedsPendingDimensions) {
            generation++
            return true
        }
        return false
    }

    fun snapshot(): DecoderConfigurationSnapshot =
        DecoderConfigurationSnapshot(requestedWidth, requestedHeight, generation)

    fun markConfigured(snapshot: DecoderConfigurationSnapshot): Boolean {
        if (snapshot.generation != generation) {
            return false
        }

        configuredWidth = snapshot.width
        configuredHeight = snapshot.height
        return true
    }
}
