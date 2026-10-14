package com.limelight.binding.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission

import com.limelight.LimeLog

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MicrophoneCapture(
    private val dataCallback: MicrophoneDataCallback,
    private val configureInput: (AudioRecord) -> Boolean = { true },
    private val onInputFailure: () -> Unit = {},
    private val onCaptureStopped: () -> Unit = {}
) {
    private val routeLock = ReentrantLock()
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var bufferSize: Int = MicrophoneConfig.CAPTURE_BUFFER_SIZE
    private var audioSource = MediaRecorder.AudioSource.MIC

    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    // 音量增益及其平衡处理器（软件音量处理）
    private val volumeProcessor = MicrophoneVolumeProcessor()

    private val frameBuffer = ByteArray(MicrophoneConfig.BYTES_PER_FRAME)
    private var frameBufferPos = 0

    private var lastFrameTime = 0L
    private var frameCount = 0L
    @Volatile private var cachedRoutedId: Int? = null
    private val routeRefreshRequested = AtomicBoolean(true)
    private var lastPcmRouteId: Int? = null
    @Volatile private var acceptingInput = false

    private class RestoreRoute(
        val actualId: Int?,
        val expectedId: Int?,
        val apply: (AudioRecord) -> Boolean
    )

    private class InputSwitch(
        val expectedId: Int?,
        val configure: (AudioRecord) -> Boolean,
        val complete: (Boolean) -> Unit,
        var restore: RestoreRoute? = null,
        var recovery: MicrophoneSwitchRecovery? = null
    )

    private val inputSwitch = AtomicReference<InputSwitch?>()

    fun interface MicrophoneDataCallback {
        fun onMicrophoneData(data: ByteArray, offset: Int, length: Int)
    }

    fun start(): Boolean {
        if (running.get()) return true

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(MicrophoneConfig.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                LimeLog.severe("不支持的音频参数")
                return false
            } else if (minBufferSize == AudioRecord.ERROR) {
                LimeLog.severe("无法获取最小缓冲区大小")
                return false
            }

            bufferSize = maxOf(minBufferSize * 2, MicrophoneConfig.CAPTURE_BUFFER_SIZE)
            audioSource = if (MicrophoneConfig.useVoiceCommunication()) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                else MediaRecorder.AudioSource.MIC

            audioRecord = createRecord(configureInput, strict = false)

            // 从配置加载音量增益及其平衡参数
            volumeProcessor.configure(
                enabled = MicrophoneConfig.isVolumeProcessingEnabled(),
                gainEnabled = MicrophoneConfig.isVolumeGainEnabled(),
                gainDb = MicrophoneConfig.getVolumeGainDb(),
                balanceEnabled = MicrophoneConfig.isVolumeBalanceEnabled(),
                balanceTargetPercent = MicrophoneConfig.getVolumeBalanceTargetPercent(),
                voiceEnhancementEnabled = MicrophoneConfig.isVoiceEnhancementEnabled()
            )

            initializeAudioEffects()

            running.set(true)
            lastFrameTime = SystemClock.elapsedRealtimeNanos()

            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val buffer = ByteBuffer.allocateDirect(bufferSize)
                val data = ByteArray(bufferSize)

                try {
                    // Saved explicit inputs need the same verification as an in-session switch.
                    if (Build.VERSION.SDK_INT >= 23) {
                        audioRecord!!.preferredDevice?.id?.let { preferredId ->
                            inputSwitch.compareAndSet(null, InputSwitch(preferredId, configureInput,
                                { success -> if (!success) onInputFailure() },
                                InputRouting.systemDefault(),
                                MicrophoneSwitchRecovery(preferredId, null, null, true, SystemClock.elapsedRealtime())))
                        }
                    }
                    try {
                        routeLock.withLock {
                            if (!running.get()) return@Thread
                            audioRecord!!.startRecording()
                            check(audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        }
                    } catch (e: RuntimeException) {
                        val pending = inputSwitch.get()
                        if (pending?.recovery?.recreate(SystemClock.elapsedRealtime()) != MicrophoneSwitchRecovery.Action.RECREATE ||
                            !restoreInput(pending, recreate = true)) throw e
                    }
                    acceptingInput = running.get()
                    LimeLog.info("麦克风捕获已启动，缓冲区大小: $bufferSize 字节")

                    while (running.get()) {
                        val pending = inputSwitch.get()
                        if (pending != null && pending.recovery == null && !prepareInputSwitch(pending)) continue
                        val bytesRead = try {
                            if (pending != null && Build.VERSION.SDK_INT >= 23) {
                                audioRecord!!.read(buffer, bufferSize, AudioRecord.READ_NON_BLOCKING)
                            } else {
                                audioRecord!!.read(buffer, bufferSize)
                            }
                        } catch (e: RuntimeException) {
                            LimeLog.warning("Microphone read exception: ${e.javaClass.simpleName}")
                            AudioRecord.ERROR_INVALID_OPERATION
                        }
                        if (!running.get()) break
                        if (routeRefreshRequested.getAndSet(false) || pending != null) {
                            cachedRoutedId = queryRoutedInputId()
                        }
                        if (bytesRead > 0) {
                            if (lastPcmRouteId != cachedRoutedId) frameBufferPos = 0
                            lastPcmRouteId = cachedRoutedId
                            buffer.get(data, 0, bytesRead)
                            buffer.clear()
                            processAudioData(data, 0, bytesRead)
                        }
                        if (pending != null) {
                            val action = pending.recovery!!.observe(SystemClock.elapsedRealtime(), cachedRoutedId, bytesRead)
                            when (action) {
                                MicrophoneSwitchRecovery.Action.SUCCEEDED -> finishInputSwitch(pending, true)
                                MicrophoneSwitchRecovery.Action.FAILED -> finishInputSwitch(pending, false)
                                MicrophoneSwitchRecovery.Action.RESTORE,
                                MicrophoneSwitchRecovery.Action.RECREATE -> {
                                    LimeLog.warning("Microphone input recovery: action=$action, read=$bytesRead")
                                    if (!restoreInput(pending, action == MicrophoneSwitchRecovery.Action.RECREATE)) break
                                }
                                MicrophoneSwitchRecovery.Action.STOP -> {
                                    LimeLog.warning("Microphone input recovery exhausted; read=$bytesRead")
                                    break
                                }
                                MicrophoneSwitchRecovery.Action.WAIT -> Unit
                            }
                        } else if (bytesRead < 0) {
                            LimeLog.warning("AudioRecord read failed: $bytesRead")
                            break
                        }
                        if (bytesRead == 0) Thread.sleep(5)
                    }
                } catch (e: SecurityException) {
                    LimeLog.severe("麦克风权限不足: ${e.message}")
                } catch (e: Exception) {
                    LimeLog.severe("麦克风捕获出错: ${e.message}")
                } finally {
                    running.set(false)
                    acceptingInput = false
                    cachedRoutedId = null
                    val pending = inputSwitch.getAndSet(null)
                    release()
                    pending?.complete?.invoke(false)
                    onCaptureStopped()
                }
            }, "MicrophoneCapture")

            captureThread!!.start()
            return true
        } catch (e: SecurityException) {
            LimeLog.severe("麦克风权限不足: ${e.message}")
            release()
            return false
        } catch (e: Exception) {
            LimeLog.severe("无法创建麦克风捕获: ${e.message}")
            release()
            return false
        }
    }

    private fun processAudioData(data: ByteArray, offset: Int, length: Int) {
        var remainingBytes = length
        var dataOffset = offset

        while (remainingBytes > 0) {
            val bytesNeeded = MicrophoneConfig.BYTES_PER_FRAME - frameBufferPos
            val bytesToCopy = minOf(remainingBytes, bytesNeeded)

            System.arraycopy(data, dataOffset, frameBuffer, frameBufferPos, bytesToCopy)
            frameBufferPos += bytesToCopy
            dataOffset += bytesToCopy
            remainingBytes -= bytesToCopy

            if (frameBufferPos >= MicrophoneConfig.BYTES_PER_FRAME) {
                val currentTime = SystemClock.elapsedRealtimeNanos()
                val timeDiff = currentTime - lastFrameTime

                if (MicrophoneConfig.ENABLE_AUDIO_SYNC &&
                    timeDiff < MicrophoneConfig.FRAME_INTERVAL_NS * 0.8) {
                    try {
                        Thread.sleep(1)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }

                // 先应用音量处理，再交给编码器。
                volumeProcessor.processFrame(frameBuffer, 0, MicrophoneConfig.BYTES_PER_FRAME)
                dataCallback.onMicrophoneData(frameBuffer, 0, MicrophoneConfig.BYTES_PER_FRAME)
                frameBufferPos = 0
                lastFrameTime = currentTime
                frameCount++

                AudioDiagnostics.recordFrameCaptured()

                if (frameCount % 12000 == 0L) {
                    LimeLog.info("麦克风帧统计: $frameCount 帧, 平均间隔: ${timeDiff / 1000000}ms")
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        acceptingInput = false
        inputSwitch.set(null)

        // Stop AudioRecord first so a blocking read can return before its native resources
        // are released. Releasing while the capture thread is still inside read() can crash
        // on some Android audio implementations.
        // If a native route operation owns the lock, it will observe running=false and
        // release on the capture thread. Do not wait for that operation on the UI thread.
        if (routeLock.tryLock()) {
            try {
                audioRecord?.stop()
            } catch (_: IllegalStateException) { }
            finally { routeLock.unlock() }
        }

        var interrupted = false
        val thread = captureThread
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(1000)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }

        if (thread?.isAlive != true) {
            captureThread = null
            release()
        } else {
            LimeLog.warning("麦克风采集线程未及时退出，将在线程结束后释放AudioRecord")
        }

        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    internal fun requestInput(
        expectedId: Int?,
        configure: (AudioRecord) -> Boolean,
        complete: (Boolean) -> Unit
    ): Boolean {
        if (!running.get() || !acceptingInput) return false
        val pending = InputSwitch(expectedId, configure, complete)
        if (!inputSwitch.compareAndSet(null, pending)) return false
        if (!running.get()) {
            inputSwitch.compareAndSet(pending, null)
            return false
        }
        return true
    }

    internal fun isInputSwitchPending(): Boolean = inputSwitch.get() != null

    private fun prepareInputSwitch(pending: InputSwitch): Boolean = routeLock.withLock {
        if (!running.get() || inputSwitch.get() !== pending) return false
        val record = audioRecord ?: return false
        val restore = try {
            if (Build.VERSION.SDK_INT >= 23) InputRouting.snapshot(record)
            else RestoreRoute(null, null) { true }
        } catch (e: RuntimeException) {
            LimeLog.warning("Microphone route snapshot failed: ${e.javaClass.simpleName}")
            finishInputSwitch(pending, false)
            return false
        }
        pending.restore = restore
        val accepted = try { pending.configure(record) } catch (_: RuntimeException) { false }
        pending.recovery = MicrophoneSwitchRecovery(pending.expectedId, restore.actualId,
            restore.expectedId, accepted, SystemClock.elapsedRealtime())
        LimeLog.info("Microphone input switch requested; accepted=$accepted")
        true
    }

    private fun finishInputSwitch(pending: InputSwitch, success: Boolean) {
        val completed = running.get() && inputSwitch.compareAndSet(pending, null)
        if (completed) {
            LimeLog.info("Microphone input switch completed; success=$success")
            pending.complete(success)
        }
    }

    private fun restoreInput(pending: InputSwitch, recreate: Boolean): Boolean {
        if (!running.get() || inputSwitch.get() !== pending) return false
        val restore = pending.restore ?: return false
        return try {
            routeLock.withLock {
                if (!running.get() || inputSwitch.get() !== pending) return false
                if (recreate) {
                    release()
                    if (!running.get()) return false
                    audioRecord = createRecord(restore.apply, strict = true)
                    if (!running.get()) return false
                    initializeAudioEffects()
                    if (!running.get()) return false
                    audioRecord!!.startRecording()
                    check(audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    frameBufferPos = 0
                } else {
                    val accepted = try { restore.apply(audioRecord!!) } catch (_: RuntimeException) { false }
                    LimeLog.info("Microphone previous route requested on existing recorder; accepted=$accepted")
                }
            }
            pending.recovery!!.recoveryStarted(SystemClock.elapsedRealtime())
            true
        } catch (_: SecurityException) {
            LimeLog.warning("Microphone input restore failed: permission denied")
            false
        } catch (e: RuntimeException) {
            LimeLog.warning("Microphone input restore failed: ${e.javaClass.simpleName}")
            false
        }
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun createRecord(configure: (AudioRecord) -> Boolean, strict: Boolean): AudioRecord {
        val record = AudioRecord(audioSource, MicrophoneConfig.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            if (!configure(record)) {
                if (strict) error("Microphone input preference rejected")
                LimeLog.warning("Microphone input preference rejected; using system default")
                onInputFailure()
            }
            return record
        } catch (e: RuntimeException) {
            record.release()
            throw e
        }
    }

    internal fun isCapturing(): Boolean = running.get()

    internal fun routedInputId(): Int? {
        if (!running.get()) return null
        routeRefreshRequested.set(true)
        return cachedRoutedId
    }

    private fun queryRoutedInputId(): Int? {
        return if (Build.VERSION.SDK_INT >= 23) {
            try { audioRecord?.routedDevice?.id } catch (_: RuntimeException) { null }
        } else null
    }

    @RequiresApi(23)
    private object InputRouting {
        fun systemDefault() = RestoreRoute(null, null) { it.setPreferredDevice(null) }

        fun snapshot(record: AudioRecord): RestoreRoute {
            val actual = record.routedDevice
            val preferred = record.preferredDevice
            val restoreId = microphoneRestoreInputId(preferred?.id, actual?.id)
            val device = actual?.takeIf { it.id == restoreId }
            LimeLog.info("Microphone route snapshot: preferredType=${preferred?.type}, actualType=${actual?.type}, restoreDefault=${device == null}")
            return RestoreRoute(actual?.id, restoreId) { it.setPreferredDevice(device) }
        }
    }

    private fun initializeAudioEffects() {
        val record = audioRecord ?: run {
            LimeLog.warning("AudioRecord为空，无法初始化音频效果器")
            return
        }

        val audioSessionId = record.audioSessionId
        LimeLog.info("开始初始化音频效果器，AudioSessionId: $audioSessionId")

        if (MicrophoneConfig.enableAcousticEchoCanceler()) {
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(audioSessionId)
                    if (echoCanceler != null) {
                        val result = echoCanceler!!.setEnabled(true)
                        if (result == 0) {
                            LimeLog.info("✓ 回声消除器(AEC)已启用")
                        } else {
                            LimeLog.warning("回声消除器启用失败，错误码: $result")
                        }
                    } else {
                        LimeLog.warning("无法创建回声消除器实例")
                    }
                } catch (e: Exception) {
                    LimeLog.warning("初始化回声消除器失败: ${e.message}")
                }
            } else {
                LimeLog.info("设备不支持硬件回声消除(AEC)")
            }
        } else {
            LimeLog.info("回声消除器已被配置禁用")
        }

        if (MicrophoneConfig.enableAutomaticGainControl()) {
            if (AutomaticGainControl.isAvailable()) {
                try {
                    gainControl = AutomaticGainControl.create(audioSessionId)
                    if (gainControl != null) {
                        val result = gainControl!!.setEnabled(true)
                        if (result == 0) {
                            LimeLog.info("✓ 自动增益控制(AGC)已启用")
                        } else {
                            LimeLog.warning("自动增益控制启用失败，错误码: $result")
                        }
                    } else {
                        LimeLog.warning("无法创建自动增益控制实例")
                    }
                } catch (e: Exception) {
                    LimeLog.warning("初始化自动增益控制失败: ${e.message}")
                }
            } else {
                LimeLog.info("设备不支持自动增益控制(AGC)")
            }
        } else {
            LimeLog.info("自动增益控制已被配置禁用")
        }

        if (MicrophoneConfig.enableNoiseSuppressor()) {
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                    if (noiseSuppressor != null) {
                        val result = noiseSuppressor!!.setEnabled(true)
                        if (result == 0) {
                            LimeLog.info("✓ 噪声抑制器(NS)已启用")
                        } else {
                            LimeLog.warning("噪声抑制器启用失败，错误码: $result")
                        }
                    } else {
                        LimeLog.warning("无法创建噪声抑制器实例")
                    }
                } catch (e: Exception) {
                    LimeLog.warning("初始化噪声抑制器失败: ${e.message}")
                }
            } else {
                LimeLog.info("设备不支持噪声抑制(NS)")
            }
        } else {
            LimeLog.info("噪声抑制器已被配置禁用")
        }
    }

    private fun releaseAudioEffects() {
        echoCanceler?.let {
            try {
                it.setEnabled(false)
                it.release()
                LimeLog.info("回声消除器已释放")
            } catch (e: Exception) {
                LimeLog.warning("释放回声消除器失败: ${e.message}")
            }
        }
        echoCanceler = null

        gainControl?.let {
            try {
                it.setEnabled(false)
                it.release()
                LimeLog.info("自动增益控制已释放")
            } catch (e: Exception) {
                LimeLog.warning("释放自动增益控制失败: ${e.message}")
            }
        }
        gainControl = null

        noiseSuppressor?.let {
            try {
                it.setEnabled(false)
                it.release()
                LimeLog.info("噪声抑制器已释放")
            } catch (e: Exception) {
                LimeLog.warning("释放噪声抑制器失败: ${e.message}")
            }
        }
        noiseSuppressor = null
    }

    private fun release() = routeLock.withLock {
        releaseAudioEffects()

        audioRecord?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) it.stop()
            } catch (_: IllegalStateException) {
                // Startup may fail before recording begins.
            } finally {
                it.release()
            }
        }
        audioRecord = null
    }

    fun isAudioEffectsWorking(): Boolean {
        return (echoCanceler?.enabled == true) ||
                (gainControl?.enabled == true) ||
                (noiseSuppressor?.enabled == true)
    }

    fun getAudioSourceInfo(): String {
        return if (MicrophoneConfig.useVoiceCommunication()) {
            "VOICE_COMMUNICATION (系统级AEC/AGC/NS)"
        } else {
            "MIC (使用硬件音频效果器)"
        }
    }

    companion object {
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
