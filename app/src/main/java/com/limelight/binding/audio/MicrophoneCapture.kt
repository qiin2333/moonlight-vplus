package com.limelight.binding.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
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
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class MicrophoneRouteChange(
    val prepare: ((Boolean) -> Unit) -> Unit,
    val rollback: ((Boolean) -> Unit) -> Unit,
    val isReady: () -> Boolean = { true },
    val restoreInput: ((AudioRecord) -> Boolean)? = null,
    val restoredInputId: () -> Int? = { null },
    val recreateRecorder: Boolean = false,
    val communicationInput: Boolean = false
)

internal class MicrophoneCaptureInput(
    val choice: MicrophoneInputDevice?,
    val configure: (AudioRecord) -> Boolean,
    val routeChange: MicrophoneRouteChange? = null,
    expectedCommunicationInputId: Int? = null
) {
    @Volatile private var boundCommunicationInputId = expectedCommunicationInputId

    fun bindCommunicationInput(id: Int?) {
        if (id != null) boundCommunicationInputId = id
    }

    val contract = MicrophoneInputContract(
        choice,
        { boundCommunicationInputId },
    ) { routeChange?.isReady?.invoke() != false }
}

class MicrophoneCapture internal constructor(
    private val dataCallback: MicrophoneDataCallback,
    private val initialInput: MicrophoneCaptureInput,
    private val onCaptureStopped: () -> Unit = {},
    private val onInitialResult: (MicrophoneInputResult) -> Unit = {},
    private val onInputLost: () -> Unit = {}
) {
    private val routeLock = ReentrantLock()
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var bufferSize: Int = MicrophoneConfig.CAPTURE_BUFFER_SIZE
    private var captureSampleRate = MicrophoneConfig.SAMPLE_RATE
    private var sampleRateConverter: Pcm16SampleRateConverter? = null
    private var audioSource = MediaRecorder.AudioSource.MIC
    private val bluetoothCommunicationKeepalive = BluetoothCommunicationKeepalive()

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
    @Volatile private var systemSilenced = false
    private var unregisterRecordingCallback: (() -> Unit)? = null
    private var unregisterRoutingCallback: (() -> Unit)? = null
    private var lastPcmRouteId: Int? = null
    @Volatile private var acceptingInput = false
    @Volatile private var activeContract: MicrophoneInputContract? = null
    @Volatile private var activeCaptureInput: MicrophoneCaptureInput? = null
    @Volatile private var observedInput = MicrophoneObservedInput(null, null)
    private var initialReported = false
    private var startupStage: MicrophoneRouteStage? = null
    private var lastRouteCheckMs = 0L

    private class RestoreRoute(
        val actualId: Int?,
        val expectedId: Int?,
        val apply: (AudioRecord) -> Boolean
    )

    private class InputSwitch(
        val configure: (AudioRecord) -> Boolean,
        val complete: (MicrophoneInputResult) -> Unit,
        var restore: RestoreRoute? = null,
        var recovery: MicrophoneSwitchRecovery? = null,
        val routeChange: MicrophoneRouteChange? = null,
        var stage: MicrophoneRouteStage? = null,
        var restoreAction: MicrophoneSwitchRecovery.Action? = null,
        var restoring: Boolean = false,
        var previousAudioSource: Int? = null,
        var previousCaptureSampleRate: Int? = null,
        val captureInput: MicrophoneCaptureInput,
        val contract: MicrophoneInputContract,
        var previousCaptureInput: MicrophoneCaptureInput? = null,
        var previousContract: MicrophoneInputContract? = null,
        val initial: Boolean = false
    )

    private val inputSwitch = AtomicReference<InputSwitch?>()

    fun interface MicrophoneDataCallback {
        fun onMicrophoneData(data: ByteArray, offset: Int, length: Int)
    }

    fun start(): Boolean {
        if (running.get()) return true

        try {
            configureCaptureFormat(initialInput.choice)
            val minBufferSize = AudioRecord.getMinBufferSize(captureSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                LimeLog.severe("不支持的音频参数")
                return false
            } else if (minBufferSize == AudioRecord.ERROR) {
                LimeLog.severe("无法获取最小缓冲区大小")
                return false
            }

            bufferSize = maxOf(minBufferSize * 2, MicrophoneConfig.captureBufferSize(captureSampleRate))
            audioSource = microphoneCaptureSource(initialInput.choice?.communication == true,
                MicrophoneConfig.useVoiceCommunication())

            // 从配置加载音量增益及其平衡参数
            volumeProcessor.configure(
                enabled = MicrophoneConfig.isVolumeProcessingEnabled(),
                gainEnabled = MicrophoneConfig.isVolumeGainEnabled(),
                gainDb = MicrophoneConfig.getVolumeGainDb(),
                balanceEnabled = MicrophoneConfig.isVolumeBalanceEnabled(),
                balanceTargetPercent = MicrophoneConfig.getVolumeBalanceTargetPercent(),
                voiceEnhancementEnabled = MicrophoneConfig.isVoiceEnhancementEnabled()
            )

            running.set(true)
            lastFrameTime = SystemClock.elapsedRealtimeNanos()

            captureThread = Thread({
                val buffer = ByteBuffer.allocateDirect(bufferSize)
                val data = ByteArray(bufferSize)

                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    val openRecorder = {
                        routeLock.withLock {
                            if (running.get()) {
                                audioRecord = createRecord(initialInput.configure)
                                if (running.get()) {
                                    initializeAudioEffects()
                                    audioRecord!!.startRecording()
                                    check(audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                                    check(reapplyInputAfterStart(initialInput.configure, "initial") ||
                                        !requiresRecordingBinding(initialInput)) {
                                        "Initial microphone recording input was not retained"
                                    }
                                    startBluetoothCommunicationKeepalive(initialInput.choice)
                                    bindConfiguredCommunicationInput(initialInput)
                                }
                            }
                        }
                    }
                    val initialChange = initialInput.routeChange
                    if (initialChange != null) {
                        val stage = MicrophoneRouteStage()
                        startupStage = stage
                        stage.start(running::get, {}, initialChange.prepare)
                        val opened = stage.openWhenReady(running::get, openRecorder)
                        check(opened || !running.get()) { "Initial microphone route unavailable" }
                        startupStage = null
                    } else openRecorder()
                    if (!running.get()) return@Thread
                    val expectedId = initialInput.contract.expectedId
                    inputSwitch.set(InputSwitch(initialInput.configure, ::reportInitial,
                        recovery = MicrophoneSwitchRecovery(expectedId, null, null, true,
                            SystemClock.elapsedRealtime()), routeChange = initialInput.routeChange,
                        captureInput = initialInput, contract = initialInput.contract, initial = true))
                    acceptingInput = running.get()
                    LimeLog.info("麦克风捕获已启动，缓冲区大小: $bufferSize 字节")

                    while (running.get()) {
                        val pending = inputSwitch.get()
                        if (pending != null && pending.recovery == null && !prepareInputSwitch(pending)) {
                            Thread.sleep(5)
                            continue
                        }
                        if (pending?.restoreAction != null && pending.stage?.result == null) {
                            // A failed recreation may have released AudioRecord already.
                            // Wait for the route rollback before reading or recreating it.
                            Thread.sleep(5)
                            continue
                        }
                        if (pending?.restoreAction != null && pending.stage?.result != null) {
                            if (pending.stage?.result != true ||
                                !restoreInput(pending, pending.restoreAction == MicrophoneSwitchRecovery.Action.RECREATE)) break
                            pending.restoreAction = null
                            pending.stage = null
                        }
                        // stop() may skip the recorder while route preparation owns the lock.
                        if (!running.get()) break
                        val record = audioRecord ?: break
                        val bytesRead = try {
                            if (pending != null && Build.VERSION.SDK_INT >= 23) {
                                record.read(buffer, bufferSize, AudioRecord.READ_NON_BLOCKING)
                            } else {
                                record.read(buffer, bufferSize)
                            }
                        } catch (e: RuntimeException) {
                            LimeLog.warning("Microphone read exception: ${e.javaClass.simpleName}")
                            AudioRecord.ERROR_INVALID_OPERATION
                        }
                        if (!running.get()) break
                        val refreshRequested = routeRefreshRequested.getAndSet(false)
                        val now = SystemClock.elapsedRealtime()
                        if (refreshRequested || pending != null ||
                            (activeContract?.choice != null && now - lastRouteCheckMs >= 100L)) {
                            val updated = queryObservedInput()
                            if (updated != observedInput) {
                                LimeLog.info("Microphone recording input: source=$audioSource, " +
                                    "deviceType=${updated.type}, deviceId=${updated.id}, silenced=${updated.silenced}")
                            }
                            observedInput = updated
                            cachedRoutedId = observedInput.id
                            lastRouteCheckMs = now
                            if (observedInput.silenced != systemSilenced) {
                                LimeLog.info("Microphone system silence changed: silenced=${observedInput.silenced}")
                            }
                            systemSilenced = observedInput.silenced
                        }
                        if (bytesRead > 0) {
                            if (lastPcmRouteId != cachedRoutedId) frameBufferPos = 0
                            lastPcmRouteId = cachedRoutedId
                            buffer.get(data, 0, bytesRead)
                            buffer.clear()
                            if (pending == null && activeContract?.matches(observedInput) == true) {
                                processCapturedData(data, 0, bytesRead)
                            } else frameBufferPos = 0
                        }
                        if (pending == null && activeContract != null &&
                            !activeContract!!.matches(observedInput)) {
                            activeContract = null
                            activeCaptureInput = null
                            frameBufferPos = 0
                            onInputLost()
                        }
                        if (pending?.recovery != null && pending.restoreAction == null) {
                            val routeLost = !pending.restoring && pending.routeChange?.isReady?.invoke() == false
                            val contract = if (pending.restoring) pending.previousContract else pending.contract
                            val silenced = contract?.choice?.communication == true && systemSilenced
                            val action = pending.recovery!!.observe(SystemClock.elapsedRealtime(), cachedRoutedId,
                                if (contract?.matches(observedInput) == true) bytesRead else 0,
                                routeUsable = !silenced && !routeLost)
                            if (pending.initial && action != MicrophoneSwitchRecovery.Action.WAIT &&
                                action != MicrophoneSwitchRecovery.Action.SUCCEEDED) break
                            when (action) {
                                MicrophoneSwitchRecovery.Action.SUCCEEDED -> finishInputSwitch(pending, true)
                                MicrophoneSwitchRecovery.Action.FAILED -> finishInputSwitch(pending, false)
                                MicrophoneSwitchRecovery.Action.RESTORE,
                                MicrophoneSwitchRecovery.Action.RECREATE -> {
                                    LimeLog.warning("Microphone input recovery: action=$action, read=$bytesRead")
                                    val restoreAction = if (pending.routeChange?.recreateRecorder == true)
                                        MicrophoneSwitchRecovery.Action.RECREATE else action
                                    if (pending.routeChange != null) beginRouteRestore(pending, restoreAction)
                                    else if (!restoreInput(pending, action == MicrophoneSwitchRecovery.Action.RECREATE)) break
                                }
                                MicrophoneSwitchRecovery.Action.STOP -> {
                                    LimeLog.warning("Microphone input recovery exhausted; read=$bytesRead")
                                    break
                                }
                                MicrophoneSwitchRecovery.Action.WAIT -> Unit
                            }
                        } else if (pending == null && bytesRead < 0) {
                            LimeLog.warning("AudioRecord read failed: $bytesRead")
                            break
                        }
                        if (bytesRead <= 0) Thread.sleep(5)
                    }
                } catch (e: SecurityException) {
                    LimeLog.severe("麦克风权限不足: ${e.message}")
                } catch (e: Exception) {
                    LimeLog.severe("麦克风捕获出错: ${e.message}")
                } finally {
                    running.set(false)
                    acceptingInput = false
                    cachedRoutedId = null
                    activeContract = null
                    activeCaptureInput = null
                    startupStage?.cancel()
                    val pending = inputSwitch.getAndSet(null)
                    pending?.stage?.cancel()
                    try { release() } catch (e: RuntimeException) {
                        LimeLog.warning("Microphone recorder cleanup failed: ${e.javaClass.simpleName}")
                    }
                    pending?.complete?.invoke(MicrophoneInputResult.Stopped)
                    if (!initialReported) reportInitial(MicrophoneInputResult.Stopped)
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

    private fun processCapturedData(data: ByteArray, offset: Int, length: Int) {
        val converter = sampleRateConverter
        if (converter == null) processAudioData(data, offset, length)
        else {
            val converted = converter.process(data, offset, length)
            if (converted.isNotEmpty()) processAudioData(converted, 0, converted.size)
        }
    }

    fun stop() {
        running.set(false)
        acceptingInput = false
        startupStage?.cancel()
        inputSwitch.getAndSet(null)?.stage?.cancel()

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
        input: MicrophoneCaptureInput,
        complete: (MicrophoneInputResult) -> Unit
    ): Boolean {
        if (!running.get() || !acceptingInput) return false
        val pending = InputSwitch(input.configure, complete,
            routeChange = input.routeChange, captureInput = input, contract = input.contract)
        if (!inputSwitch.compareAndSet(null, pending)) return false
        if (!running.get()) {
            inputSwitch.compareAndSet(pending, null)
            return false
        }
        return true
    }

    internal fun isInputSwitchPending(): Boolean = running.get() &&
        (!acceptingInput || inputSwitch.get() != null)

    internal fun hasConfirmedInput(): Boolean = running.get() && !isInputSwitchPending() &&
        activeContract?.matches(observedInput) == true

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun prepareInputSwitch(pending: InputSwitch): Boolean = routeLock.withLock {
        if (!running.get() || inputSwitch.get() !== pending) return false
        if (pending.previousContract == null) {
            pending.previousContract = activeContract
            pending.previousCaptureInput = activeCaptureInput
        }
        val restore = pending.restore ?: try {
            val record = checkNotNull(audioRecord)
            if (Build.VERSION.SDK_INT >= 23) InputRouting.snapshot(record)
            else RestoreRoute(null, null) { true }
        } catch (e: RuntimeException) {
            LimeLog.warning("Microphone route snapshot failed: ${e.javaClass.simpleName}")
            finishInputSwitch(pending, false)
            return false
        }
        pending.restore = restore
        if (pending.previousAudioSource == null) pending.previousAudioSource = audioSource
        if (pending.previousCaptureSampleRate == null) pending.previousCaptureSampleRate = captureSampleRate
        val change = pending.routeChange
        if (change != null && pending.stage == null) {
            val stage = MicrophoneRouteStage()
            pending.stage = stage
            // Release the old input session before changing the communication route.
            stage.start(
                isCurrent = { running.get() && inputSwitch.get() === pending },
                beforeRequest = {
                    if (change.recreateRecorder) release()
                    else if (Build.VERSION.SDK_INT >= 23) audioRecord?.setPreferredDevice(null)
                },
                request = change.prepare)
        }
        if (change != null && pending.stage?.result == null) return false
        var accepted = false
        if (change != null && change.recreateRecorder && pending.stage?.result == true) {
            try {
                val source = microphoneCaptureSource(change.communicationInput,
                    MicrophoneConfig.useVoiceCommunication())
                if (!recreateRecord(pending.configure, source, pending.captureInput,
                        microphoneCaptureSampleRate(pending.captureInput.choice))) return false
                accepted = true
            } catch (e: RuntimeException) {
                LimeLog.warning("Microphone recorder recreation failed: ${e.javaClass.simpleName}")
            }
        } else {
            accepted = (change == null || pending.stage?.result == true) &&
                try { audioRecord?.let(pending.configure) == true } catch (_: RuntimeException) { false }
        }
        val expectedId = pending.contract.expectedId
        pending.recovery = MicrophoneSwitchRecovery(expectedId, restore.actualId,
            restore.expectedId, accepted, SystemClock.elapsedRealtime())
        if (!accepted) pending.restoring = true
        if (change != null && !accepted) beginRouteRestore(pending, if (change.recreateRecorder)
            MicrophoneSwitchRecovery.Action.RECREATE else MicrophoneSwitchRecovery.Action.RESTORE)
        LimeLog.info("Microphone input switch requested; accepted=$accepted")
        true
    }

    private fun beginRouteRestore(pending: InputSwitch, action: MicrophoneSwitchRecovery.Action) {
        pending.restoring = true
        val stage = MicrophoneRouteStage()
        pending.stage = stage
        pending.restoreAction = action
        stage.start(
            isCurrent = { running.get() && inputSwitch.get() === pending },
            beforeRequest = {
                if (action == MicrophoneSwitchRecovery.Action.RECREATE) release()
            },
            request = pending.routeChange!!.rollback)
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun recreateRecord(
        configure: (AudioRecord) -> Boolean,
        source: Int = audioSource,
        captureInput: MicrophoneCaptureInput? = null,
        sampleRate: Int = captureSampleRate
    ): Boolean {
        release()
        if (!running.get()) return false
        audioSource = source
        configureCaptureFormat(captureInput?.choice, sampleRate)
        audioRecord = createRecord(configure)
        if (!running.get()) return false
        initializeAudioEffects()
        if (!running.get()) return false
        audioRecord!!.startRecording()
        check(audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        check(reapplyInputAfterStart(configure, "recreate") ||
            !requiresRecordingBinding(captureInput)) {
            "Microphone recording input was not retained after recreation"
        }
        startBluetoothCommunicationKeepalive(captureInput?.choice)
        captureInput?.let(::bindConfiguredCommunicationInput)
        frameBufferPos = 0
        lastPcmRouteId = null
        return true
    }

    /** Some OEMs accept the pre-start preferred device but apply it only after recording starts. */
    private fun reapplyInputAfterStart(configure: (AudioRecord) -> Boolean, phase: String): Boolean {
        val record = audioRecord ?: return false
        try {
            val accepted = configure(record)
            LimeLog.info("Microphone preferred input reapplied after start: phase=$phase, accepted=$accepted")
            routeRefreshRequested.set(true)
            return accepted
        } catch (e: RuntimeException) {
            LimeLog.warning("Microphone preferred input reapply failed: ${e.javaClass.simpleName}")
            routeRefreshRequested.set(true)
            return false
        }
    }

    private fun requiresRecordingBinding(input: MicrophoneCaptureInput?): Boolean =
        input?.choice?.communication == true && requiresBluetoothRecordingBinding(input.choice.type)

    private fun startBluetoothCommunicationKeepalive(input: MicrophoneInputDevice?) {
        if (input?.communication == true && input.type == 7) {
            bluetoothCommunicationKeepalive.start(captureSampleRate)
        } else {
            bluetoothCommunicationKeepalive.stop()
        }
    }

    private fun configureCaptureFormat(input: MicrophoneInputDevice?, sampleRate: Int = microphoneCaptureSampleRate(input)) {
        captureSampleRate = sampleRate
        val minBufferSize = AudioRecord.getMinBufferSize(captureSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        check(minBufferSize > 0) { "Unsupported microphone sample rate: $captureSampleRate" }
        bufferSize = maxOf(minBufferSize * 2, MicrophoneConfig.captureBufferSize(captureSampleRate))
        sampleRateConverter = if (captureSampleRate == MicrophoneConfig.SAMPLE_RATE) null
        else Pcm16SampleRateConverter(captureSampleRate, MicrophoneConfig.SAMPLE_RATE)
    }

    private fun bindConfiguredCommunicationInput(input: MicrophoneCaptureInput) {
        if (input.choice?.communication != true || Build.VERSION.SDK_INT < 23) return
        val preferred = try { audioRecord?.preferredDevice } catch (_: RuntimeException) { null }
        input.bindCommunicationInput(preferred?.id)
        LimeLog.info("Microphone communication binding: choiceType=${input.choice.type}, " +
            "preferredType=${preferred?.type}, preferredId=${preferred?.id}")
    }

    private fun finishInputSwitch(pending: InputSwitch, success: Boolean) {
        val completed = running.get() && inputSwitch.compareAndSet(pending, null)
        if (completed) {
            LimeLog.info("Microphone input switch completed; success=$success")
            val contract = if (success) pending.contract else pending.previousContract
            activeContract = contract?.takeIf { it.matches(observedInput) }
            activeCaptureInput = activeContract?.let {
                if (success) pending.captureInput else pending.previousCaptureInput
            }
            frameBufferPos = 0
            val active = activeContract?.let { MicrophoneActiveInput(it.choice, observedInput) }
            pending.complete(when {
                active == null -> MicrophoneInputResult.Stopped
                success -> MicrophoneInputResult.Applied(active)
                else -> MicrophoneInputResult.Restored(active)
            })
        }
    }

    private fun reportInitial(result: MicrophoneInputResult) {
        if (!initialReported) {
            initialReported = true
            onInitialResult(result)
        }
    }

    private fun restoreInput(pending: InputSwitch, recreate: Boolean): Boolean {
        if (!running.get() || inputSwitch.get() !== pending) return false
        pending.restoring = true
        val restore = pending.restore ?: return false
        val apply = pending.routeChange?.restoreInput ?: restore.apply
        return try {
            routeLock.withLock {
                if (!running.get() || inputSwitch.get() !== pending) return false
                if (recreate) {
                    if (!recreateRecord(apply, pending.previousAudioSource ?: audioSource,
                            pending.previousCaptureInput,
                            pending.previousCaptureSampleRate ?: captureSampleRate)) return false
                } else {
                    val accepted = try { apply(audioRecord!!) } catch (_: RuntimeException) { false }
                    LimeLog.info("Microphone previous route requested on existing recorder; accepted=$accepted")
                }
            }
            val restoreId = if (pending.routeChange?.restoreInput != null)
                pending.routeChange.restoredInputId() else restore.expectedId
            pending.recovery!!.recoveryStarted(SystemClock.elapsedRealtime(), restoreId)
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
    private fun createRecord(configure: (AudioRecord) -> Boolean): AudioRecord {
        val record = AudioRecord(audioSource, captureSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            check(configure(record)) { "Microphone input preference rejected" }
            val preferred = if (Build.VERSION.SDK_INT >= 23) {
                try { record.preferredDevice } catch (_: RuntimeException) { null }
            } else null
            val preferredDescription = if (Build.VERSION.SDK_INT >= 23) {
                "type=${preferred?.type}, id=${preferred?.id}"
            } else "unsupported"
            LimeLog.info("Microphone recorder created: source=$audioSource, sampleRate=$captureSampleRate, " +
                "preferred=$preferredDescription")
            if (Build.VERSION.SDK_INT >= 29) {
                unregisterRecordingCallback = RecordingState.observe(record) { routeRefreshRequested.set(true) }
            }
            if (Build.VERSION.SDK_INT >= 24) {
                unregisterRoutingCallback = RoutingState.observe(record) { routeRefreshRequested.set(true) }
            }
            routeRefreshRequested.set(true)
            LimeLog.info("Microphone recorder created: source=$audioSource")
            return record
        } catch (e: RuntimeException) {
            record.release()
            throw e
        }
    }

    internal fun isCapturing(): Boolean = running.get()

    internal fun isSystemSilenced(): Boolean = running.get() && systemSilenced

    internal fun routedInputId(): Int? {
        if (!running.get()) return null
        routeRefreshRequested.set(true)
        return cachedRoutedId
    }

    private fun queryObservedInput(): MicrophoneObservedInput {
        return if (Build.VERSION.SDK_INT >= 23) {
            try {
                val device = audioRecord?.routedDevice
                val routed = MicrophoneObservedInput(device?.id, device?.type)
                val recording = if (Build.VERSION.SDK_INT >= 29)
                    audioRecord?.let(RecordingState::input) else null
                reconcileMicrophoneInput(routed, recording)
            } catch (_: RuntimeException) { MicrophoneObservedInput(null, null) }
        } else MicrophoneObservedInput(null, null)
    }

    @RequiresApi(23)
    private object InputRouting {
        fun snapshot(record: AudioRecord): RestoreRoute {
            val actual = record.routedDevice
            val preferred = record.preferredDevice
            val restoreId = microphoneRestoreInputId(preferred?.id, actual?.id)
            val device = actual?.takeIf { it.id == restoreId }
            LimeLog.info("Microphone route snapshot: preferredType=${preferred?.type}, actualType=${actual?.type}, restoreDefault=${device == null}")
            return RestoreRoute(actual?.id, restoreId) { it.setPreferredDevice(device) }
        }
    }

    @RequiresApi(24)
    private object RoutingState {
        fun observe(record: AudioRecord, changed: () -> Unit): () -> Unit {
            val enabled = AtomicBoolean(true)
            val listener = AudioRouting.OnRoutingChangedListener { if (enabled.get()) changed() }
            try { record.addOnRoutingChangedListener(listener, null) }
            catch (_: RuntimeException) { enabled.set(false) }
            return {
                if (enabled.getAndSet(false)) {
                    try { record.removeOnRoutingChangedListener(listener) } catch (_: RuntimeException) { }
                }
            }
        }
    }

    @RequiresApi(29)
    private object RecordingState {
        fun input(record: AudioRecord): MicrophoneObservedInput? = try {
            record.activeRecordingConfiguration?.let { config ->
                if (config.clientAudioSessionId != record.audioSessionId) MicrophoneObservedInput(null, null)
                else {
                    val device = config.audioDevice
                    MicrophoneObservedInput(device?.id, device?.type, config.isClientSilenced)
                }
            }
        } catch (_: RuntimeException) { null }

        fun observe(record: AudioRecord, changed: () -> Unit): () -> Unit {
            val enabled = AtomicBoolean(true)
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    // Only request a refresh. Never publish an old recorder's state after recreation.
                    if (enabled.get()) changed()
                }
            }
            try {
                record.registerAudioRecordingCallback(Executor { it.run() }, callback)
            } catch (_: RuntimeException) {
                enabled.set(false)
                LimeLog.warning("Microphone recording state callback unavailable")
            }
            return {
                if (enabled.getAndSet(false)) {
                    try { record.unregisterAudioRecordingCallback(callback) } catch (_: RuntimeException) { }
                }
            }
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
        bluetoothCommunicationKeepalive.stop()
        unregisterRoutingCallback?.invoke()
        unregisterRoutingCallback = null
        unregisterRecordingCallback?.invoke()
        unregisterRecordingCallback = null
        systemSilenced = false
        cachedRoutedId = null
        sampleRateConverter?.reset()
        sampleRateConverter = null
        captureSampleRate = MicrophoneConfig.SAMPLE_RATE
        frameBufferPos = 0
        lastPcmRouteId = null
        releaseAudioEffects()

        val record = audioRecord
        audioRecord = null
        record?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) it.stop()
            } catch (_: IllegalStateException) {
                // Startup may fail before recording begins.
            } finally {
                it.release()
            }
        }
    }

    fun isAudioEffectsWorking(): Boolean {
        return (echoCanceler?.enabled == true) ||
                (gainControl?.enabled == true) ||
                (noiseSuppressor?.enabled == true)
    }

    fun getAudioSourceInfo(): String {
        return if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            "VOICE_COMMUNICATION"
        } else {
            "MIC (使用硬件音频效果器)"
        }
    }

    companion object {
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
