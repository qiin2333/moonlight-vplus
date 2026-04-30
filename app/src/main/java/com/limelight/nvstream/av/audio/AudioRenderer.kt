package com.limelight.nvstream.av.audio

import com.limelight.nvstream.jni.MoonBridge

interface AudioRenderer {
    /**
     * @param codec one of [MoonBridge.AUDIO_CODEC_OPUS] / AC3 / EAC3 (see
     *  `LiGetNegotiatedAudioCodec`). For non-OPUS codecs the native layer
     *  bypasses Opus decoding and instead delivers raw encoded frames via
     *  [playEncodedAudio]; [playDecodedAudio] is never called.
     * @param bitrate Negotiated bitrate in bits/sec (only meaningful for AC3/E-AC3),
     *  0 when not applicable.
     */
    fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int, codec: Int, bitrate: Int): Int
    fun start()
    fun stop()
    fun playDecodedAudio(audioData: ShortArray)

    /**
     * Delivers raw encoded audio frames (AC3 / E-AC3) when [codec][setup] is
     * non-OPUS. Default impl ignores the data — renderers that advertise
     * passthrough support are expected to override this.
     *
     * @param audioData Backing buffer (shared, reused). Read only the leading
     *  [length] bytes; trailing bytes are stale.
     * @param length Valid byte count in [audioData].
     */
    fun playEncodedAudio(audioData: ByteArray, length: Int) {}

    fun cleanup()
}
