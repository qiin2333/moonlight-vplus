package com.limelight.binding.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/**
 * Bit-perfect AC3 / E-AC3 passthrough renderer.
 *
 * Hands the encoded bitstream straight to the platform [AudioTrack] in
 * [AudioFormat.ENCODING_AC3] / [AudioFormat.ENCODING_E_AC3] mode. The OS /
 * receiver then decodes (or further forwards via SPDIF / HDMI to an AVR).
 *
 * Only [setup] for non-Opus codecs succeeds; Opus must use [AndroidAudioRenderer].
 */
class Ac3PassthroughRenderer(private val context: Context) : AudioRenderer {

    private var track: AudioTrack? = null
    private var encoding: Int = 0
    private var codecName: String = ""

    override fun setup(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int,
        codec: Int,
        bitrate: Int
    ): Int {
        if (codec == MoonBridge.AUDIO_CODEC_OPUS) {
            return -1
        }

        encoding = when (codec) {
            MoonBridge.AUDIO_CODEC_AC3 -> {
                codecName = "AC3"
                AudioFormat.ENCODING_AC3
            }
            MoonBridge.AUDIO_CODEC_EAC3 -> {
                codecName = "E-AC3"
                AudioFormat.ENCODING_E_AC3
            }
            else -> {
                LimeLog.severe("Ac3PassthroughRenderer: unknown codec=$codec")
                return -1
            }
        }

        val channelMask = when (audioConfiguration.channelCount) {
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            else -> {
                LimeLog.severe("Ac3PassthroughRenderer: unsupported channels=${audioConfiguration.channelCount}")
                return -1
            }
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        // Verify the platform / route can actually consume this encoded format.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val supported = try {
                AudioTrack.isDirectPlaybackSupported(format, attributes)
            } catch (e: Throwable) {
                LimeLog.warning("isDirectPlaybackSupported threw: ${e.message}")
                false
            }
            if (!supported) {
                LimeLog.warning("Ac3PassthroughRenderer: $codecName direct playback NOT supported on this device/route")
                return -1
            }
        }

        // AC3 frame = 1536 samples; size up the buffer for ~6 frames worth of
        // bitstream headroom (each AC3 frame max ~2560 bytes @ 640 kbps).
        val bufferSize = 16 * 1024
        try {
            track = AudioTrack.Builder()
                .setAudioFormat(format)
                .setAudioAttributes(attributes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
            track!!.play()
            LimeLog.info("Ac3PassthroughRenderer: $codecName initialized @${sampleRate} Hz, ${audioConfiguration.channelCount}ch, bitrate=$bitrate bps")
            return 0
        } catch (e: Exception) {
            LimeLog.severe("Ac3PassthroughRenderer: AudioTrack create failed: ${e.message}")
            try {
                track?.release()
            } catch (_: Exception) {}
            track = null
            return -2
        }
    }

    override fun start() {
        // play() already called in setup(); nothing additional.
    }

    override fun stop() {
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {}
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        // Unused: native side never calls this for non-Opus codecs.
    }

    override fun playEncodedAudio(audioData: ByteArray, length: Int) {
        val t = track ?: return
        if (length <= 0) return
        try {
            // AC3 is frame-aligned (sync word 0x0B77 marks frame start). A short
            // write would desync the receiver / AVR. Loop with WRITE_BLOCKING
            // semantics until the entire frame is committed (or the track
            // returns an error).
            var offset = 0
            while (offset < length) {
                val written = t.write(audioData, offset, length - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    LimeLog.warning("Ac3PassthroughRenderer.write returned $written, dropping rest of frame")
                    break
                }
                offset += written
            }
        } catch (e: Exception) {
            LimeLog.warning("Ac3PassthroughRenderer.write failed: ${e.message}")
        }
    }

    override fun cleanup() {
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {}
        track = null
    }
}
