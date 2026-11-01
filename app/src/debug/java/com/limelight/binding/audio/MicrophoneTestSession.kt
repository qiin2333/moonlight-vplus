package com.limelight.binding.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import com.limelight.LimeLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

internal data class MicrophoneTestSnapshot(
    val running: Boolean = false,
    val waveform: List<Float> = emptyList(),
    val rms: Float = 0f,
    val peak: Float = 0f,
    val source: String = "",
    val selectedInput: String = "",
    val boundInput: String = "",
    val route: String = "",
    val communicationRoute: String = "",
    val preferredRoute: String = "",
    val recordingRoute: String = "",
    val format: String = "",
    val buffer: String = "",
    val session: String = "",
    val displayGain: Float = 1f,
    val recording: Boolean = false,
    val recordingSeconds: Int = 0,
    val hasRecording: Boolean = false,
    val playing: Boolean = false,
    val error: String? = null
)

/** Captures local PCM only. It does not use an Opus encoder or a host connection. */
internal class MicrophoneTestSession(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val devices = MicrophoneInputDeviceRepository(appContext)
    private val bluetoothRoute = BluetoothMicrophoneRouteController(appContext)
    private val worker = HandlerThread("MicrophoneTest").apply { start() }
    private val handler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var running = AtomicBoolean(false)
    private var listener: ((MicrophoneTestSnapshot) -> Unit)? = null
    private var playbackThread: Thread? = null
    private var playback: AudioTrack? = null
    private val playbackLock = Any()
    private var communicationTrackThread: Thread? = null
    private var communicationTrack: AudioTrack? = null
    private val communicationTrackLock = Any()
    private val recordingLock = Any()
    private val recordingData = ByteArrayOutputStream()
    private var recordingActive = false
    private var recordingStartedAt = 0L
    private var lastSnapshot = MicrophoneTestSnapshot()
    private var captureSampleRate = MicrophoneConfig.SAMPLE_RATE

    fun choices(): List<MicrophoneInputDevice> = devices.choices()

    fun displayNames(choices: List<MicrophoneInputDevice>): Map<String, String> =
        devices.displayNames(choices)

    fun start(choice: MicrophoneInputDevice?, onChanged: (MicrophoneTestSnapshot) -> Unit) {
        val token = generation.incrementAndGet()
        listener = onChanged
        stopRecorder()
        publish(MicrophoneTestSnapshot())
        bluetoothRoute.release {
            if (generation.get() != token) return@release
            if (choice?.communication == true) {
                bluetoothRoute.activate(choice, { generation.get() == token },
                    onRouteLost = {
                        if (generation.get() == token) publishError("Bluetooth microphone route lost")
                    }) { success ->
                    if (generation.get() != token) return@activate
                    if (success) createRecorder(token, choice)
                    else publishError("Unable to activate the selected microphone route")
                }
            } else {
                createRecorder(token, choice)
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        stopRecorder()
        stopPlayback()
        clearRecording()
        bluetoothRoute.release()
        publish(MicrophoneTestSnapshot())
    }

    fun close() {
        stop()
        worker.quitSafely()
    }

    fun toggleRecording() {
        if (!running.get()) return
        synchronized(recordingLock) {
            if (recordingActive) {
                recordingActive = false
            } else {
                stopPlayback()
                recordingData.reset()
                recordingStartedAt = android.os.SystemClock.elapsedRealtime()
                recordingActive = true
            }
        }
        publish(lastSnapshot)
    }

    fun playRecording() {
        val data = synchronized(recordingLock) { recordingData.toByteArray() }
        if (data.isEmpty() || synchronized(playbackLock) { playbackThread?.isAlive == true }) return
        val minBuffer = AudioTrack.getMinBufferSize(
            captureSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return
        val bufferSize = maxOf(minBuffer, data.size)
        val track = if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(captureSampleRate)
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
                AudioManager.STREAM_MUSIC,
                MicrophoneConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
        val thread = Thread({
            try {
                track.play()
                var offset = 0
                while (offset < data.size && synchronized(playbackLock) { playback === track }) {
                    val written = if (Build.VERSION.SDK_INT >= 23) {
                        track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
                    } else {
                        @Suppress("DEPRECATION")
                        track.write(data, offset, data.size - offset)
                    }
                    if (written <= 0) break
                    offset += written
                }
            } catch (e: RuntimeException) {
                LimeLog.warning("Microphone test playback failed: " + e.javaClass.simpleName)
            } finally {
                try { track.stop() } catch (_: RuntimeException) { }
                track.release()
                var completed = false
                synchronized(playbackLock) {
                    if (playback === track) {
                        playback = null
                        playbackThread = null
                        completed = true
                    }
                }
                if (completed) publish(lastSnapshot)
            }
        }, "MicrophoneTestPlayback")
        synchronized(playbackLock) {
            if (playbackThread?.isAlive == true) {
                try { track.release() } catch (_: RuntimeException) { }
                return
            }
            playback = track
            playbackThread = thread
            thread.start()
        }
        publish(lastSnapshot)
    }

    private fun createRecorder(token: Long, choice: MicrophoneInputDevice?) {
        handler.post {
            if (generation.get() != token) return@post
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                publishError("Microphone permission is required")
                return@post
            }
            try {
                val sampleRate = captureSampleRate(choice)
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                check(minBuffer > 0) { "AudioRecord buffer unavailable" }
                val bufferSize = maxOf(minBuffer * 2, MicrophoneConfig.CAPTURE_BUFFER_SIZE)
                val source = microphoneCaptureSource(choice?.communication == true, false)
                val input = matchingInput(choice)
                val audioRecord = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord initialization failed"
                }
                // Some OEMs expose a Bluetooth communication endpoint but no matching
                // AudioRecord input port. Keep the communication route and let the
                // platform choose its recording endpoint instead of failing the test.
                val key = input?.key ?: if (choice?.communication == true) {
                    DEFAULT_MICROPHONE_INPUT
                } else {
                    choice?.key ?: DEFAULT_MICROPHONE_INPUT
                }
                check(devices.apply(audioRecord, key)) {
                    "Microphone input preference rejected"
                }
                audioRecord.startRecording()
                check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord did not start"
                }
                // Some OEMs accept the preferred input before start but only apply it
                // after the recording session owns the route.
                val reapplied = devices.apply(audioRecord, key)
                check(reapplied || input == null) {
                    "Microphone input preference was not retained after start"
                }
                record = audioRecord
                captureSampleRate = sampleRate
                running.set(true)
                if (choice?.communication == true) {
                    startCommunicationKeepalive(token, sampleRate)
                }
                publishSnapshot(
                    audioRecord, source, emptyList(), 0f, 0f, choice, input, bufferSize, sampleRate
                )
                capture(token, audioRecord, source, bufferSize, choice, input, sampleRate)
            } catch (e: Exception) {
                LimeLog.warning("Microphone test failed: ${e.javaClass.simpleName}")
                publishError(e.message ?: "Unable to start microphone test")
                stopRecorder()
                bluetoothRoute.release()
            }
        }
    }

    private fun matchingInput(choice: MicrophoneInputDevice?): MicrophoneInputDevice? =
        choice?.takeIf { it.communication }?.let {
            matchingBluetoothInput(it, devices.devices())
        }

    private fun capture(
        token: Long,
        audioRecord: AudioRecord,
        source: Int,
        bufferSize: Int,
        choice: MicrophoneInputDevice?,
        input: MicrophoneInputDevice?,
        sampleRate: Int
    ) {
        val thread = Thread({
            val buffer = ByteArray(bufferSize)
            val waveform = ArrayDeque<Float>(WAVEFORM_POINTS)
            var lastPublish = 0L
            try {
                while (generation.get() == token && running.get()) {
                    val read = if (Build.VERSION.SDK_INT >= 23) {
                        audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    } else {
                        audioRecord.read(buffer, 0, buffer.size)
                    }
                    if (read > 1) {
                        val metrics = pcmMetrics(buffer, read)
                        synchronized(recordingLock) {
                            if (recordingActive) {
                                recordingData.write(buffer, 0, read)
                                if (android.os.SystemClock.elapsedRealtime() - recordingStartedAt >=
                                    MAX_RECORDING_MS
                                ) {
                                    recordingActive = false
                                }
                            }
                        }
                        metrics.waveform.forEach(waveform::addLast)
                        while (waveform.size > WAVEFORM_POINTS) waveform.removeFirst()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                            lastPublish = now
                            publishSnapshot(
                                audioRecord, source, waveform.toList(), metrics.rms, metrics.peak,
                                choice, input, bufferSize, sampleRate
                            )
                        }
                    } else if (read < 0) {
                        publishError("AudioRecord read failed: $read")
                        break
                    } else {
                        Thread.sleep(10)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: RuntimeException) {
                LimeLog.warning("Microphone test read failed: ${e.javaClass.simpleName}")
                publishError(e.message ?: "Microphone test read failed")
            }
        }, "MicrophoneTestCapture")
        captureThread = thread
        thread.start()
    }

    private data class PcmMetrics(
        val waveform: List<Float>,
        val rms: Float,
        val peak: Float
    )

    private fun pcmMetrics(buffer: ByteArray, length: Int): PcmMetrics {
        var sum = 0.0
        var peak = 0f
        var count = 0
        val samples = ArrayList<Float>(WAVEFORM_SAMPLES_PER_READ)
        val sampleCount = length / 2
        val stride = maxOf(1, sampleCount / WAVEFORM_SAMPLES_PER_READ)
        var i = 0
        var sampleIndex = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort()
            val signed = sample.toFloat() / Short.MAX_VALUE
            val normalized = kotlin.math.abs(signed)
            sum += normalized * normalized
            peak = maxOf(peak, normalized)
            if (sampleIndex % stride == 0 && samples.size < WAVEFORM_SAMPLES_PER_READ) {
                samples += signed
            }
            count++
            sampleIndex++
            i += 2
        }
        return if (count == 0) PcmMetrics(emptyList(), 0f, 0f)
        else PcmMetrics(samples, sqrt((sum / count).toFloat()), peak)
    }

    private fun publishSnapshot(
        audioRecord: AudioRecord,
        source: Int,
        waveform: List<Float>,
        rms: Float,
        peak: Float,
        choice: MicrophoneInputDevice?,
        input: MicrophoneInputDevice?,
        bufferSize: Int,
        sampleRate: Int
    ) {
        val device = if (Build.VERSION.SDK_INT >= 23) {
            try { audioRecord.routedDevice } catch (_: RuntimeException) { null }
        } else {
            null
        }
        val route = device?.let {
            val name = if (Build.VERSION.SDK_INT >= 23) it.productName?.toString() else null
            val id = if (Build.VERSION.SDK_INT >= 23) it.id else -1
            val type = if (Build.VERSION.SDK_INT >= 23) it.type else -1
            "${name ?: type} (type=$type, id=$id)"
        } ?: "Unknown"
        val preferred = if (Build.VERSION.SDK_INT >= 23) {
            try { audioRecord.preferredDevice } catch (_: RuntimeException) { null }
        } else null
        val recordingDevice = if (Build.VERSION.SDK_INT >= 29) {
            try { audioRecord.activeRecordingConfiguration?.audioDevice }
            catch (_: RuntimeException) { null }
        } else null
        val communication = if (Build.VERSION.SDK_INT >= 31) {
            try {
                val manager = appContext.getSystemService(AudioManager::class.java)
                val device = manager.communicationDevice
                val sco = manager.isBluetoothScoOn
                "${formatDevice(device)} (sco=$sco)"
            } catch (_: RuntimeException) {
                "Unavailable"
            }
        } else {
            "Unavailable (Android < 12)"
        }
        publish(MicrophoneTestSnapshot(
            running = true,
            waveform = waveform,
            rms = rms,
            peak = peak,
            source = if (source == MediaRecorder.AudioSource.MIC) "MIC (1)" else "VOICE_COMMUNICATION (7)",
            selectedInput = if (choice == null) "System default" else formatChoice(choice),
            boundInput = input?.let(::formatChoice) ?: "None",
            route = route,
            communicationRoute = communication,
            preferredRoute = formatDevice(preferred),
            recordingRoute = formatDevice(recordingDevice),
            format = "$sampleRate Hz, mono, PCM 16-bit",
            buffer = "$bufferSize bytes",
            session = if (Build.VERSION.SDK_INT >= 24) {
                "${audioRecord.audioSessionId}"
            } else {
                "Unavailable (Android < 7)"
            },
            displayGain = waveformDisplayGain(waveform)
        ))
    }

    private fun formatDevice(device: android.media.AudioDeviceInfo?): String {
        if (device == null) return "Unknown"
        val name = if (Build.VERSION.SDK_INT >= 23) device.productName?.toString() else null
        val id = if (Build.VERSION.SDK_INT >= 23) device.id else -1
        val type = if (Build.VERSION.SDK_INT >= 23) device.type else -1
        val address = if (Build.VERSION.SDK_INT >= 28) {
            try { device.address.takeIf(String::isNotBlank) } catch (_: SecurityException) { null }
        } else null
        return "${name ?: type} (type=$type, id=$id${address?.let { ", address=$it" } ?: ""})"
    }

    private fun formatChoice(device: MicrophoneInputDevice?): String {
        if (device == null) return "None"
        return "${device.name.ifBlank { "Unknown" }} (type=${device.type}, id=${device.id}" +
            device.address.takeIf(String::isNotBlank)?.let { ", address=$it" }.orEmpty() + ")"
    }

    private fun captureSampleRate(choice: MicrophoneInputDevice?): Int =
        microphoneCaptureSampleRate(choice)

    private fun waveformDisplayGain(values: List<Float>): Float {
        val peak = values.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        return if (peak < DISPLAY_NOISE_FLOOR) {
            1f
        } else {
            (0.78f / peak).coerceIn(1f, MAX_DISPLAY_GAIN)
        }
    }

    private fun publishError(message: String) {
        publish(MicrophoneTestSnapshot(error = message))
    }

    private fun publish(snapshot: MicrophoneTestSnapshot) {
        val enriched = synchronized(recordingLock) {
            snapshot.copy(
                recording = recordingActive,
                recordingSeconds = if (recordingActive) {
                    ((android.os.SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L)
                        .toInt().coerceIn(0, MAX_RECORDING_SECONDS)
                } else 0,
                hasRecording = recordingData.size() > 0,
                playing = playbackThread?.isAlive == true
            )
        }
        lastSnapshot = enriched
        val callback = listener ?: return
        mainHandler.post {
            if (listener === callback) callback(enriched)
        }
    }

    private fun stopRecorder() {
        running.set(false)
        stopCommunicationKeepalive()
        try { record?.stop() } catch (_: RuntimeException) { }
        captureThread?.interrupt()
        captureThread = null
        try { record?.release() } catch (_: RuntimeException) { }
        record = null
    }

    private fun stopPlayback() {
        synchronized(playbackLock) {
            val track = playback
            try { track?.stop() } catch (_: RuntimeException) { }
            playback = null
            playbackThread?.interrupt()
        }
    }

    private fun clearRecording() {
        synchronized(recordingLock) {
            recordingActive = false
            recordingStartedAt = 0L
            recordingData.reset()
        }
    }

    /** Some OEM HFP stacks open the uplink only while a communication playback stream exists. */
    private fun startCommunicationKeepalive(token: Long, sampleRate: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return
        val bufferSize = maxOf(minBuffer, sampleRate / 10 * 2)
        val track = if (Build.VERSION.SDK_INT >= 23) {
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
        try {
            track.setVolume(0f)
            track.play()
        } catch (e: RuntimeException) {
            track.release()
            LimeLog.warning("Bluetooth communication keepalive failed: ${e.javaClass.simpleName}")
            return
        }
        val thread = Thread({
            val silence = ByteArray(bufferSize)
            try {
                while (generation.get() == token && running.get()) {
                    val written = if (Build.VERSION.SDK_INT >= 23) {
                        track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                    } else {
                        @Suppress("DEPRECATION")
                        track.write(silence, 0, silence.size)
                    }
                    if (written <= 0) break
                }
            } catch (_: RuntimeException) {
            } finally {
                try { track.stop() } catch (_: RuntimeException) { }
                track.release()
                synchronized(communicationTrackLock) {
                    if (communicationTrack === track) {
                        communicationTrack = null
                        communicationTrackThread = null
                    }
                }
            }
        }, "MicrophoneTestCommunicationKeepalive")
        synchronized(communicationTrackLock) {
            communicationTrack = track
            communicationTrackThread = thread
            thread.start()
        }
    }

    private fun stopCommunicationKeepalive() {
        synchronized(communicationTrackLock) {
            val track = communicationTrack
            try { track?.stop() } catch (_: RuntimeException) { }
            communicationTrack = null
            communicationTrackThread?.interrupt()
        }
    }

    companion object {
        private const val WAVEFORM_POINTS = 160
        private const val WAVEFORM_SAMPLES_PER_READ = 16
        private const val PUBLISH_INTERVAL_MS = 50L
        private const val MAX_RECORDING_SECONDS = 15
        private const val MAX_RECORDING_MS = MAX_RECORDING_SECONDS * 1000L
        private const val DISPLAY_NOISE_FLOOR = 0.04f
        private const val MAX_DISPLAY_GAIN = 12f
    }
}
