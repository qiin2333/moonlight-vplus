package com.limelight.binding.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.limelight.LimeLog

/**
 * Keeps the voice-communication output stream alive while classic Bluetooth
 * HFP microphone capture is active. Some OEM audio stacks do not open the
 * headset uplink for AudioRecord alone.
 */
internal class BluetoothCommunicationKeepalive {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start(sampleRate: Int) {
        stop()

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            LimeLog.warning("Bluetooth communication keepalive unavailable: buffer=$minBuffer")
            return
        }

        val bufferSize = maxOf(minBuffer, sampleRate / 10 * 2)
        val newTrack = try {
            if (Build.VERSION.SDK_INT >= 23) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
        } catch (e: RuntimeException) {
            LimeLog.warning("Bluetooth communication keepalive creation failed: ${e.javaClass.simpleName}")
            return
        }

        try {
            check(newTrack.state == AudioTrack.STATE_INITIALIZED)
            newTrack.setVolume(0f)
            newTrack.play()
        } catch (e: RuntimeException) {
            try { newTrack.release() } catch (_: RuntimeException) { }
            LimeLog.warning("Bluetooth communication keepalive start failed: ${e.javaClass.simpleName}")
            return
        }

        val writer = Thread({
            val silence = ByteArray(bufferSize)
            try {
                while (true) {
                    synchronized(lock) {
                        if (track !== newTrack) break
                    }
                    val written = if (Build.VERSION.SDK_INT >= 23) {
                        newTrack.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                    } else {
                        @Suppress("DEPRECATION")
                        newTrack.write(silence, 0, silence.size)
                    }
                    if (written <= 0) break
                }
            } catch (_: RuntimeException) {
                // Route teardown can interrupt a blocking write.
            } finally {
                try { newTrack.stop() } catch (_: RuntimeException) { }
                newTrack.release()
                synchronized(lock) {
                    if (track === newTrack) {
                        track = null
                        thread = null
                    }
                }
            }
        }, "BluetoothCommunicationKeepalive")

        synchronized(lock) {
            track = newTrack
            thread = writer
            writer.start()
        }
    }

    fun stop() {
        val oldTrack: AudioTrack?
        val oldThread: Thread?
        synchronized(lock) {
            oldTrack = track
            oldThread = thread
            track = null
            thread = null
        }
        try { oldTrack?.stop() } catch (_: RuntimeException) { }
        oldThread?.interrupt()
        // The writer owns release(); do not release the track from this thread.
    }
}
