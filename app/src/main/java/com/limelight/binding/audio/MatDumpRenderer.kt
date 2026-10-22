package com.limelight.binding.audio

import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/** Lifecycle only. Native AudioStream -> MatDumpFile handles all bytes and SHA.
 * Deliberately constructs no decoder, AudioTrack, spatializer or passthrough. */
class MatDumpRenderer : AudioRenderer {
    override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int,
                       samplesPerFrame: Int, codec: Int, bitrate: Int): Int {
        if (codec != MoonBridge.AUDIO_CODEC_MAT_DUMP || sampleRate != 48000 || samplesPerFrame != 960) return -1
        LimeLog.info("MAT dump-only active; native reassembly/SHA256, no audio playback")
        return 0
    }
    override fun start() {}
    override fun stop() {}
    override fun cleanup() {}
    override fun playDecodedAudio(audioData: ShortArray) { error("MAT must not enter PCM renderer") }
    override fun playEncodedAudio(audioData: ByteArray, length: Int) { error("MAT must not enter encoded renderer") }
}
