package com.limelight.binding.audio

import android.content.Context
import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/** Only negotiated authenticated live MAT enters this sink. */
class MatPassthroughRenderer(private val context: Context) : AudioRenderer, MatCarrierConsumer {
    @Volatile private var session: MatPlaybackSession? = null
    override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int,
                       samplesPerFrame: Int, codec: Int, bitrate: Int): Int {
        if (codec != MoonBridge.AUDIO_CODEC_MAT_LIVE || sampleRate != 48000 || samplesPerFrame != 960) return -1
        return try {
            val sink = MatAudioTrackSink(context)
            session = MatPlaybackSession(sink)
            LimeLog.info("MAT output: ${sink.diagnostics()}")
            LimeLog.info("MAT live IEC playback active: logical 48k/960, hardware 192k/8ch; no PCM fallback")
            0
        } catch (e: Exception) {
            cleanup()
            LimeLog.severe("MAT IEC setup failed: $e")
            -1
        }
    }
    override fun start() {}
    override fun stop() {
        val current = session ?: return
        current.close()
        LimeLog.info("MAT output stopped: state=${current.player().state()} received=${current.player().receivedCycles()} written=${current.player().writtenCycles()} submittedBytes=${current.player().submittedBytes()} error=${current.player().error()}")
    }
    override fun cleanup() { stop(); session = null }
    override fun playDecodedAudio(audioData: ShortArray) { error("MAT must not enter PCM callback") }
    override fun playEncodedAudio(audioData: ByteArray, length: Int) { error("MAT must not enter AC3 callback") }
    override fun offerMatCycle(bytes: ByteArray, length: Int, epoch: Long, domain: Long, index: Long, pts48k: Long): Boolean =
        session?.let {
            val accepted = it.offerMatCycle(bytes, length, epoch, domain, index, pts48k)
            if (!accepted) LimeLog.severe("MAT cycle rejected: index=$index state=${it.player().state()} error=${it.player().error()}")
            accepted
        } ?: false
    override fun endMatStream(cycles: Long): Boolean {
        val current = session ?: return false
        val ok = current.endMatStream(cycles)
        LimeLog.info("MAT IEC EOS drained=$ok submittedBytes=${current.player().submittedBytes()} SHA256=${current.player().submittedSha256()} error=${current.player().error()}")
        return ok
    }
}
