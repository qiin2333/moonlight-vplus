package com.limelight.nvstream

/**
 * Authored DualSense haptics PCM captured from the host's virtual USB audio
 * endpoint.
 *
 * [pcm] holds signed 16-bit little-endian samples, interleaved haptic-left
 * then haptic-right. The bytes are copied out of the common-c callback
 * buffer before delivery.
 */
class Ds5HapticsPcmFrame(
    val controllerNumber: Short,
    val flags: Byte,
    val sequenceNumber: Int,
    val presentationTimeUs: Long,
    val sampleRate: Int,
    val frameCount: Int,
    val channelCount: Byte,
    val bitsPerSample: Byte,
    val pcm: ByteArray
) {
    companion object {
        const val FLAG_STREAM_START: Byte = 0x01
        const val FLAG_STREAM_END: Byte = 0x02
        const val FLAG_DISCONTINUITY: Byte = 0x04
    }
}
