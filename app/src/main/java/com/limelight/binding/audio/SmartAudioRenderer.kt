package com.limelight.binding.audio

import android.content.Context

import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/**
 * Composite renderer that picks between [AndroidAudioRenderer] (Opus / PCM)
 * and [Ac3PassthroughRenderer] (AC3 / E-AC3 bit-perfect) based on the
 * negotiated codec reported by the native layer in [setup].
 *
 * The non-chosen child is never initialized, so this is essentially a free
 * dispatcher with no behavioral overhead vs. the legacy direct usage.
 */
class SmartAudioRenderer(
    private val context: Context,
    private val enableAudioFx: Boolean,
    private val enableSpatializer: Boolean,
    private val passthroughBufferBytes: Int = 16 * 1024
) : AudioRenderer {

    private var delegate: AudioRenderer? = null

    override fun setup(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int,
        codec: Int,
        bitrate: Int
    ): Int {
        // Route by negotiated codec:
        //   PCM_S16  -> PcmPassthroughRenderer (raw LPCM, lowest latency)
        //   AC3/EAC3 -> Ac3PassthroughRenderer (encoded bitstream to AVR)
        //   OPUS     -> AndroidAudioRenderer (decode + render)
        if (codec == MoonBridge.AUDIO_CODEC_PCM_S16) {
            val pcmThru = PcmPassthroughRenderer(context, passthroughBufferBytes)
            val res = pcmThru.setup(audioConfiguration, sampleRate, samplesPerFrame, codec, bitrate)
            if (res == 0) {
                LimeLog.info("SmartAudioRenderer: using PcmPassthroughRenderer")
                delegate = pcmThru
                return 0
            }
            LimeLog.warning("SmartAudioRenderer: PcmPassthroughRenderer setup failed ($res)")
            pcmThru.cleanup()
            return res
        }

        if (codec != MoonBridge.AUDIO_CODEC_OPUS) {
            val passthrough = Ac3PassthroughRenderer(context, passthroughBufferBytes)
            val res = passthrough.setup(audioConfiguration, sampleRate, samplesPerFrame, codec, bitrate)
            if (res == 0) {
                LimeLog.info("SmartAudioRenderer: using Ac3PassthroughRenderer (codec=$codec)")
                delegate = passthrough
                return 0
            }
            LimeLog.warning("SmartAudioRenderer: Ac3PassthroughRenderer setup failed ($res); native side already negotiated $codec, no PCM fallback available")
            passthrough.cleanup()
            return res
        }

        val pcm = AndroidAudioRenderer(context, enableAudioFx, enableSpatializer)
        val res = pcm.setup(audioConfiguration, sampleRate, samplesPerFrame, codec, bitrate)
        if (res == 0) {
            delegate = pcm
        }
        return res
    }

    override fun start() {
        delegate?.start()
    }

    override fun stop() {
        delegate?.stop()
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        delegate?.playDecodedAudio(audioData)
    }

    override fun playEncodedAudio(audioData: ByteArray, length: Int) {
        delegate?.playEncodedAudio(audioData, length)
    }

    override fun cleanup() {
        delegate?.cleanup()
        delegate = null
    }

    /** Forwarded for [AndroidAudioRenderer.pauseProcessing] when active. */
    fun pauseProcessing() {
        (delegate as? AndroidAudioRenderer)?.pauseProcessing()
    }

    /** Forwarded for [AndroidAudioRenderer.resumeProcessing] when active. */
    fun resumeProcessing() {
        (delegate as? AndroidAudioRenderer)?.resumeProcessing()
    }
}
