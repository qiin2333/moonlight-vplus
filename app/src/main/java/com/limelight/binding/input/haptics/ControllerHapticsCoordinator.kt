package com.limelight.binding.input.haptics

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.GenericControllerContext
import com.limelight.binding.input.InputDeviceContext
import com.limelight.binding.input.DriverControllerContext
import com.limelight.nvstream.Ds5HapticsPcmFrame
import com.limelight.nvstream.jni.MoonBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns controller-haptics runtime policy for the Android client.
 *
 * [ControllerHandler] remains responsible for input-device lifecycle and controller numbering,
 * while this class owns source arbitration, target selection, output pacing, expiry, and stop
 * behavior. All mutable mixer and scheduler state is serialized on the handler's main looper.
 */
internal class ControllerHapticsCoordinator(
    private val handler: ControllerHandler
) {
    private data class DeviceOutput(
        val controllerNumber: Short,
        val output: ControllerRumbleState
    )

    private data class NativeHapticsBinding(
        val controllerNumber: Short,
        val sink: DualSenseNativeHapticsSink
    )

    private val mixer = ControllerHapticsMixer()
    private val deviceMixer = ControllerHapticsMixer()
    private val ds5HapticsBindings = ConcurrentHashMap<Int, NativeHapticsBinding>()
    private val ds5LifecycleLock = Any()
    private var ds5LifecycleGeneration = 0L
    private val ds5RequestedGenerations = mutableMapOf<Int, Long>()
    private val outputSlots = mutableMapOf<Short, RumbleOutputSlot<MixedRumbleState>>()
    private val hostStates = mutableMapOf<Short, ControllerRumbleState>()
    private val deviceOutputSlot: RumbleOutputSlot<DeviceOutput> by lazy {
        RumbleOutputSlot(
            pacingMs = { ANDROID_RUMBLE_INTERVAL_MS },
            edgeFloorMs = EDGE_DISPATCH_FLOOR_MS,
            isZero = { it.output.isZero },
            postDelayed = handler.mainThreadHandler::postDelayed,
            removeCallbacks = handler.mainThreadHandler::removeCallbacks,
            clockMs = SystemClock::elapsedRealtime,
            dispatch = ::dispatchDevice
        )
    }
    private val deviceCapabilities: DeviceHapticsCapabilities by lazy {
        DeviceHapticsCapabilities.probe(handler.deviceVibrator)
    }
    private val deviceEnvelope = RumbleEnvelopeAnalyzer()
    private var envelopeTickerScheduled = false
    private val envelopeTicker = Runnable { runEnvelopeTick() }
    private val deviceVibrationCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DeviceVibrationCoordinator(
            postDelayed = { callback, delayMs ->
                handler.mainThreadHandler.postDelayed(callback, delayMs)
            },
            removeCallback = handler.mainThreadHandler::removeCallbacks,
            vibrateDevice = { amplitude, durationMs ->
                handler.rumbleManager.vibrateSingleAmplitude(
                    amplitude = amplitude,
                    durationMs = durationMs
                )
            },
            cancelDeviceVibration = handler.deviceVibrator::cancel
        )
    }

    @Volatile
    private var primaryControllerNumber = NO_CONTROLLER
    private var audioController: Short? = null
    private var lastAudioContinuousState = ControllerRumbleState.ZERO
    private var lastAudioContinuousExpiresAtMs: Long? = null
    private var stopping = false
    private var stopped = false

    private val expiryRunnable = Runnable {
        if (stopped || stopping || handler.stopped) return@Runnable
        val nowMs = SystemClock.elapsedRealtime()
        mixer.pruneExpired(nowMs).forEach(::queue)
        deviceMixer.pruneExpired(nowMs).forEach { mixed ->
            queueDevice(DeviceOutput(mixed.controllerNumber, mixed.output))
        }
        scheduleNextExpiry(nowMs)
    }

    fun hasRumbleCapableController(): Boolean =
        primaryControllerNumber != NO_CONTROLLER

    fun submitLegacyDeviceRumble(lowFrequency: Short, highFrequency: Short) {
        deviceVibrationCoordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.LEGACY_OVERLAY,
            SingleMotorRumbleFold.amplitude(lowFrequency, highFrequency),
            handler.prefConfig.deviceRumbleStrength
        )
    }

    fun playDeviceTouchHaptic(lowFrequency: Short, highFrequency: Short, durationMs: Int) =
        deviceVibrationCoordinator.playTouchHaptic(lowFrequency, highFrequency, durationMs)

    fun claimDeviceVibratorForAudio(): Boolean = deviceVibrationCoordinator.claimForAudio()

    fun releaseDeviceVibratorFromAudio() = deviceVibrationCoordinator.releaseFromAudio()

    private fun primaryControllerNumber(): Short? =
        primaryControllerNumber
            .takeUnless { it == NO_CONTROLLER }
            ?.toShort()

    fun hasRumbleCapability(context: InputDeviceContext): Boolean {
        if (!context.external) return false
        if (handler.prefConfig.multiController && !context.assignedControllerNumber) return false
        val inputDevice = context.inputDevice ?: return false
        if (ControllerHandler.getMotionRangeForJoystickAxis(inputDevice, MotionEvent.AXIS_X) == null ||
            ControllerHandler.getMotionRangeForJoystickAxis(inputDevice, MotionEvent.AXIS_Y) == null
        ) {
            return false
        }
        return context.vibratorManager != null ||
            context.vibrator != null ||
            context.directDualSenseBluetoothOutput != null ||
            handler.sceManager.isRecognizedDevice(inputDevice)
    }

    fun hasRumbleCapability(context: DriverControllerContext): Boolean {
        if (handler.prefConfig.multiController && !context.assignedControllerNumber) return false
        val capabilities = context.device?.capabilities?.toInt() ?: return false
        return capabilities and MoonBridge.LI_CCAP_RUMBLE.toInt() != 0
    }

    fun noteControllerInput(context: GenericControllerContext) {
        when (context) {
            is InputDeviceContext -> if (hasRumbleCapability(context)) {
                updatePrimaryController(context.controllerNumber)
            }
            is DriverControllerContext -> if (hasRumbleCapability(context)) {
                updatePrimaryController(context.controllerNumber)
            }
        }
    }

    fun refreshPrimaryController() {
        runOnOutputThread { refreshPrimaryControllerOnOutputThread() }
    }

    private fun refreshPrimaryControllerOnOutputThread() {
        if (isStoppingOrStopped()) return

        val current = primaryControllerNumber()
        if (current != null && controllerHasRumble(current)) {
            return
        }

        var candidate = NO_CONTROLLER
        for (i in 0 until handler.inputDeviceContexts.size()) {
            val context = handler.inputDeviceContexts.valueAt(i)
            if (!hasRumbleCapability(context)) continue
            val number = if (context.assignedControllerNumber) {
                context.controllerNumber.toInt()
            } else {
                0
            }
            if (number == 0) {
                updatePrimaryController(0)
                return
            }
            if (candidate == NO_CONTROLLER) candidate = number
        }

        for (context in handler.driverControllerContexts.values) {
            if (!hasRumbleCapability(context)) continue
            val number = if (context.assignedControllerNumber) {
                context.controllerNumber.toInt()
            } else {
                0
            }
            if (number == 0) {
                updatePrimaryController(0)
                return
            }
            if (candidate == NO_CONTROLLER) candidate = number
        }

        if (candidate == NO_CONTROLLER) {
            primaryControllerNumber = NO_CONTROLLER
        } else {
            updatePrimaryController(candidate.toShort())
        }
    }

    private fun updatePrimaryController(controllerNumber: Short) {
        if (primaryControllerNumber == controllerNumber.toInt()) return
        primaryControllerNumber = controllerNumber.toInt()

        // USB input may arrive on its driver thread. Mixer-owned state is inspected only after
        // switching to the serialized output looper.
        runOnOutputThread {
            if (!isStoppingOrStopped() && audioController != null &&
                controllerHasRumble(controllerNumber)
            ) {
                switchAudioTarget(controllerNumber)
            }
        }
    }

    /** Submit authoritative base-motor rumble received from the streaming host. */
    fun submitHost(controllerNumber: Short, lowFrequency: Short, highFrequency: Short) {
        runOnOutputThread {
            if (isStoppingOrStopped()) return@runOnOutputThread
            val nowMs = SystemClock.elapsedRealtime()
            val state = ControllerRumbleState(
                lowFrequency = lowFrequency.toNormalizedRumble(),
                highFrequency = highFrequency.toNormalizedRumble()
            )
            if (state.isZero) {
                hostStates.remove(controllerNumber)
            } else {
                hostStates[controllerNumber] = state
            }
            routeGameRumble(controllerNumber, RumbleSource.HOST, state, nowMs)
            scheduleNextExpiry(nowMs)
        }
    }

    /** Submit a local diagnostic effect without overwriting HOST or AUDIO state. */
    fun submitTest(controllerNumber: Short, lowFrequency: Short, highFrequency: Short) {
        runOnOutputThread {
            if (isStoppingOrStopped()) return@runOnOutputThread
            routeGameRumble(
                controllerNumber,
                RumbleSource.TEST,
                ControllerRumbleState(
                    lowFrequency = lowFrequency.toNormalizedRumble(),
                    highFrequency = highFrequency.toNormalizedRumble()
                ),
                SystemClock.elapsedRealtime()
            )
        }
    }

    fun submitAudio(
        continuousLow: Float,
        continuousHigh: Float,
        transientLow: Float,
        transientHigh: Float,
        transientDurationMs: Int,
        hasTransient: Boolean,
        continuousChanged: Boolean
    ) {
        runOnOutputThread {
            if (isStoppingOrStopped()) return@runOnOutputThread
            val target = primaryControllerNumber()
            if (target == null || !controllerHasRumble(target)) {
                clearAudioNow()
                return@runOnOutputThread
            }

            val nowMs = SystemClock.elapsedRealtime()
            if (continuousChanged) {
                lastAudioContinuousState = ControllerRumbleState(continuousLow, continuousHigh)
            }
            lastAudioContinuousExpiresAtMs = if (lastAudioContinuousState.isZero) {
                null
            } else {
                nowMs + AUDIO_CONTINUOUS_WATCHDOG_MS
            }
            switchAudioTarget(target, nowMs)

            var mixed = mixer.submitAudioContinuous(
                target,
                lastAudioContinuousState,
                nowMs,
                lastAudioContinuousExpiresAtMs
            )
            if (hasTransient) {
                // A pulse must survive at least one device output interval, otherwise an expiry
                // callback can replace it before a rate-limited Android vibrator receives it.
                val transientLifetimeMs = transientDurationMs.toLong().coerceIn(
                    dispatchIntervalMs(target) + 1L,
                    120L
                )
                mixed = mixer.submitAudioTransient(
                    target,
                    ControllerRumbleState(transientLow, transientHigh),
                    nowMs,
                    nowMs + transientLifetimeMs
                )
            }
            queue(mixed)
            scheduleNextExpiry(nowMs)
        }
    }

    /** Compatibility input for the legacy bass-energy callback. */
    fun submitLegacyAudio(
        lowFrequency: Float,
        highFrequency: Float,
        durationMs: Int,
        continuous: Boolean
    ) {
        runOnOutputThread {
            if (isStoppingOrStopped()) return@runOnOutputThread
            val target = primaryControllerNumber()
            if (target == null || !controllerHasRumble(target)) {
                clearAudioNow()
                return@runOnOutputThread
            }

            val nowMs = SystemClock.elapsedRealtime()
            val state = ControllerRumbleState(lowFrequency, highFrequency)
            val expiresAtMs = nowMs + durationMs.toLong().coerceIn(
                dispatchIntervalMs(target) + 1L,
                350L
            )
            if (continuous) {
                lastAudioContinuousState = state
                lastAudioContinuousExpiresAtMs = expiresAtMs
            }
            switchAudioTarget(target, nowMs)

            val mixed = if (continuous) {
                mixer.submitAudioContinuous(target, state, nowMs, expiresAtMs)
            } else {
                mixer.submitAudioTransient(target, state, nowMs, expiresAtMs)
            }
            queue(mixed)
            scheduleNextExpiry(nowMs)
        }
    }

    fun stopAudio() {
        runOnOutputThread { clearAudioNow() }
    }

    fun refreshAudioWatchdog() {
        runOnOutputThread {
            if (isStoppingOrStopped() || lastAudioContinuousState.isZero) {
                return@runOnOutputThread
            }
            val target = audioController ?: return@runOnOutputThread
            if (!controllerHasRumble(target)) return@runOnOutputThread

            val nowMs = SystemClock.elapsedRealtime()
            lastAudioContinuousExpiresAtMs = nowMs + AUDIO_CONTINUOUS_WATCHDOG_MS
            queue(
                mixer.submitAudioContinuous(
                    target,
                    lastAudioContinuousState,
                    nowMs,
                    lastAudioContinuousExpiresAtMs
                )
            )
            scheduleNextExpiry(nowMs)
        }
    }

    fun submitHostTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        runOnOutputThread {
            if (!isStoppingOrStopped()) {
                // Trigger motors remain authoritative HOST output. Base motors flow through the
                // source mixer and therefore cannot erase this independently stored state.
                handler.rumbleManager.handleRumbleTriggers(
                    controllerNumber,
                    leftTrigger,
                    rightTrigger
                )
            }
        }
    }

    fun submitHostAdaptiveTriggers(
        controllerNumber: Short,
        eventFlags: Byte,
        typeLeft: Byte,
        typeRight: Byte,
        left: ByteArray,
        right: ByteArray
    ) {
        // Snapshot the payloads before deferring: the rumble manager queues these
        // arrays across threads and hands them to the USB output worker as-is.
        val leftSnapshot = left.copyOf()
        val rightSnapshot = right.copyOf()
        runOnOutputThread {
            if (!isStoppingOrStopped()) {
                handler.rumbleManager.handleAdaptiveTriggers(
                    controllerNumber,
                    eventFlags,
                    typeLeft,
                    typeRight,
                    leftSnapshot,
                    rightSnapshot
                )
            }
        }
    }

    fun submitDs5HapticsPcm(frame: Ds5HapticsPcmFrame) {
        var target: DualSenseNativeHapticsSink? = null
        for (binding in ds5HapticsBindings.values) {
            if (binding.controllerNumber == frame.controllerNumber) {
                target = binding.sink
                break
            }
        }
        target?.submit(frame)
    }

    fun attachDs5HapticsSink(
        controllerId: Int,
        controllerNumber: Short,
        sink: DualSenseNativeHapticsSink
    ) {
        val generation: Long
        synchronized(ds5LifecycleLock) {
            generation = ++ds5LifecycleGeneration
            ds5RequestedGenerations[controllerId] = generation
        }
        // A sink may arrive on the main thread. Its startup can perform transport I/O, so keep it
        // on the existing background worker.
        handler.backgroundThreadHandler.post {
            synchronized(ds5LifecycleLock) {
                if (ds5RequestedGenerations[controllerId] != generation) {
                    sink.stop()
                    return@post
                }
            }
            if (isStoppingOrStopped()) {
                sink.stop()
                return@post
            }

            if (!sink.start()) {
                synchronized(ds5LifecycleLock) {
                    if (ds5RequestedGenerations[controllerId] == generation) {
                        ds5RequestedGenerations.remove(controllerId)
                    }
                }
                sink.stop()
                return@post
            }

            val replaced = ArrayList<NativeHapticsBinding>()
            val accepted = synchronized(ds5LifecycleLock) {
                if (ds5RequestedGenerations[controllerId] != generation || isStoppingOrStopped()) {
                    false
                } else {
                    ds5RequestedGenerations.remove(controllerId)
                    ds5HapticsBindings.remove(controllerId)?.let(replaced::add)
                    // In single-controller mode several devices may share player 0. Preserve the
                    // historical latest-attached-wins policy without dropping the working sink
                    // until its replacement has started successfully.
                    ds5HapticsBindings.entries.toList().forEach { (existingId, binding) ->
                        if (binding.controllerNumber == controllerNumber &&
                            ds5HapticsBindings.remove(existingId, binding)
                        ) {
                            replaced.add(binding)
                        }
                    }
                    ds5HapticsBindings[controllerId] =
                        NativeHapticsBinding(controllerNumber, sink)
                    true
                }
            }
            if (!accepted) {
                sink.stop()
                return@post
            }
            replaced.forEach { it.sink.stop() }
        }
    }

    fun detachDs5HapticsSink(controllerId: Int) {
        val binding: NativeHapticsBinding?
        synchronized(ds5LifecycleLock) {
            ds5RequestedGenerations.remove(controllerId)
            ds5LifecycleGeneration++
            binding = ds5HapticsBindings.remove(controllerId)
        }
        binding?.let { stopSinksAsync(listOf(it)) }
    }

    private fun stopSinksAsync(bindings: List<NativeHapticsBinding>) {
        if (bindings.isEmpty()) return
        val cleanup = Runnable { bindings.forEach { it.sink.stop() } }
        if (Looper.myLooper() == handler.backgroundThreadHandler.looper) {
            cleanup.run()
        } else if (!handler.backgroundThreadHandler.post(cleanup)) {
            Thread(cleanup, "DualSenseHapticsCleanup").apply { isDaemon = true }.start()
        }
    }

    fun clearControllerIfUnavailable(controllerNumber: Short) {
        runOnOutputThread {
            if (stopped || controllerHasRumble(controllerNumber)) return@runOnOutputThread

            resetOutputSlot(controllerNumber)
            val nowMs = SystemClock.elapsedRealtime()
            routeGameRumble(
                controllerNumber,
                RumbleSource.HOST,
                hostStates[controllerNumber] ?: ControllerRumbleState.ZERO,
                nowMs
            )
            scheduleNextExpiry(nowMs)
        }
    }

    /** Replays current logical state when the physical sink for a controller changes. */
    fun onSinkChanged(controllerNumber: Short) {
        runOnOutputThread {
            if (isStoppingOrStopped()) return@runOnOutputThread

            resetOutputSlot(controllerNumber)

            val nowMs = SystemClock.elapsedRealtime()
            routeGameRumble(
                controllerNumber,
                RumbleSource.HOST,
                hostStates[controllerNumber] ?: ControllerRumbleState.ZERO,
                nowMs
            )
            scheduleNextExpiry(nowMs)
        }
    }

    /** Stops logical output immediately and releases transport sinks asynchronously. */
    fun stop() {
        if (stopped || stopping) return
        stopping = true
        stopAllNow()
        val bindings = synchronized(ds5LifecycleLock) {
            ds5RequestedGenerations.clear()
            ds5LifecycleGeneration++
            ds5HapticsBindings.values.toList().also { ds5HapticsBindings.clear() }
        }
        primaryControllerNumber = NO_CONTROLLER
        stopped = true
        stopping = false
        stopSinksAsync(bindings)
    }

    private fun switchAudioTarget(
        target: Short,
        nowMs: Long = SystemClock.elapsedRealtime()
    ) {
        val previous = audioController
        if (previous == target) return
        if (lastAudioContinuousExpiresAtMs?.let { it <= nowMs } == true) {
            lastAudioContinuousState = ControllerRumbleState.ZERO
            lastAudioContinuousExpiresAtMs = null
        }
        if (previous != null) {
            queue(mixer.clearSource(previous, RumbleSource.AUDIO, nowMs))
        }
        audioController = target
        queue(
            mixer.submitAudioContinuous(
                target,
                lastAudioContinuousState,
                nowMs,
                lastAudioContinuousExpiresAtMs
            )
        )
    }

    private fun clearAudioNow() {
        lastAudioContinuousState = ControllerRumbleState.ZERO
        lastAudioContinuousExpiresAtMs = null
        val target = audioController ?: return
        audioController = null
        queue(mixer.clearSource(target, RumbleSource.AUDIO, SystemClock.elapsedRealtime()))
        scheduleNextExpiry()
    }

    private fun routeGameRumble(
        controllerNumber: Short,
        source: RumbleSource,
        input: ControllerRumbleState,
        nowMs: Long
    ) {
        val hasController = controllerHasRumble(controllerNumber)
        // One-shot capability snapshot replaces a per-event Binder query. The tier gates body
        // eligibility for coordinated compensation: a body that can only switch on/off would
        // add PWM noise on top of a full-strength controller.
        val hasDevice = controllerNumber.toInt() == 0 && deviceCapabilities.hasVibrator
        val bodyEligible = hasDevice && deviceCapabilities.tier.supportsGradedOutput
        val coordinated = hasController && bodyEligible &&
            handler.prefConfig.gameRumbleMode == GameRumbleMode.COORDINATED

        // The body leg only tracks player one's signal. The envelope stays warm across
        // controller presence changes so onsets after a reconnect still read correctly.
        val decomposition = if (bodyEligible) {
            deviceEnvelope.decompose(input).also { result ->
                if (coordinated && result.hasUnsettledTransients) {
                    scheduleEnvelopeTicker()
                }
            }
        } else {
            null
        }

        val route = GameRumbleRouter.route(
            mode = handler.prefConfig.gameRumbleMode,
            input = input,
            hasController = hasController,
            hasDevice = hasDevice,
            deviceTier = deviceCapabilities.tier,
            decomposition = decomposition
        )

        val controllerOutput = route.controller?.let { routedState ->
            mixer.submit(controllerNumber, source, routedState, nowMs)
        } ?: mixer.clearSource(controllerNumber, source, nowMs)
        if (hasController) {
            queue(controllerOutput)
        }

        // The Android device is a single shared game-rumble sink, so only player one may own it.
        // Audio haptics never enter this path and retain their existing renderer and preferences.
        if (controllerNumber.toInt() == 0) {
            val deviceOutput = route.device?.let { routedState ->
                deviceMixer.submit(controllerNumber, source, routedState, nowMs)
            } ?: deviceMixer.clearSource(controllerNumber, source, nowMs)
            queueDevice(
                DeviceOutput(
                    controllerNumber = controllerNumber,
                    output = deviceOutput.output
                )
            )
        }
    }

    private fun controllerHasRumble(controllerNumber: Short): Boolean {
        for (i in 0 until handler.inputDeviceContexts.size()) {
            val context = handler.inputDeviceContexts.valueAt(i)
            if (context.controllerNumber == controllerNumber && hasRumbleCapability(context)) {
                return true
            }
        }
        for (context in handler.driverControllerContexts.values) {
            if (context.controllerNumber == controllerNumber && hasRumbleCapability(context)) {
                return true
            }
        }
        return false
    }

    private fun runOnOutputThread(action: () -> Unit) {
        if (Looper.myLooper() == handler.mainThreadHandler.looper) {
            action()
        } else {
            handler.mainThreadHandler.post(action)
        }
    }

    private fun resetOutputSlot(controllerNumber: Short) {
        // Removing the slot also forgets its dedupe history, forcing the next dispatch through.
        outputSlots.remove(controllerNumber)?.cancel()
    }

    private fun queue(mixed: MixedRumbleState) {
        if (isStoppingOrStopped()) return
        slotFor(mixed.controllerNumber).submit(mixed)
    }

    private fun slotFor(controllerNumber: Short): RumbleOutputSlot<MixedRumbleState> =
        outputSlots.getOrPut(controllerNumber) {
            RumbleOutputSlot(
                pacingMs = { dispatchIntervalMs(controllerNumber) },
                edgeFloorMs = EDGE_DISPATCH_FLOOR_MS,
                isZero = { it.isZero },
                postDelayed = handler.mainThreadHandler::postDelayed,
                removeCallbacks = handler.mainThreadHandler::removeCallbacks,
                clockMs = SystemClock::elapsedRealtime,
                dispatch = ::dispatchToSink
            )
        }

    private fun dispatchToSink(mixed: MixedRumbleState) {
        if (isStoppingOrStopped()) return
        if (controllerHasRumble(mixed.controllerNumber)) {
            handler.rumbleManager.handleRumble(
                mixed.controllerNumber,
                mixed.output.lowFrequency.toMotorShort(),
                mixed.output.highFrequency.toMotorShort()
            )
        }
    }

    private fun queueDevice(output: DeviceOutput) {
        if (isStoppingOrStopped()) return
        deviceOutputSlot.submit(output)
    }

    private fun dispatchDevice(output: DeviceOutput) {
        if (isStoppingOrStopped()) return
        // The two channels are folded exactly once, here; the coordinator applies only
        // strength, quantization and scheduling downstream.
        deviceVibrationCoordinator.submitGameRumble(
            DeviceVibrationCoordinator.GameSource.ROUTED_GAME,
            SingleMotorRumbleFold.amplitude(
                output.output.lowFrequency,
                output.output.highFrequency
            ),
            handler.prefConfig.deviceRumbleStrength
        )
    }

    /**
     * Advances the device envelope between host events: transients must decay into the
     * sustained level even when the host holds a constant rumble and sends nothing further.
     */
    private fun scheduleEnvelopeTicker() {
        if (envelopeTickerScheduled) return
        envelopeTickerScheduled = true
        handler.mainThreadHandler.postDelayed(envelopeTicker, ENVELOPE_TICK_INTERVAL_MS)
    }

    private fun runEnvelopeTick() {
        envelopeTickerScheduled = false
        if (isStoppingOrStopped()) return
        routeGameRumble(
            PLAYER_ONE,
            RumbleSource.HOST,
            hostStates[PLAYER_ONE] ?: ControllerRumbleState.ZERO,
            SystemClock.elapsedRealtime()
        )
    }

    private fun dispatchIntervalMs(controllerNumber: Short): Long {
        for (i in 0 until handler.inputDeviceContexts.size()) {
            val context = handler.inputDeviceContexts.valueAt(i)
            if (context.controllerNumber == controllerNumber && hasRumbleCapability(context)) {
                return ANDROID_RUMBLE_INTERVAL_MS
            }
        }
        return USB_RUMBLE_INTERVAL_MS
    }

    private fun scheduleNextExpiry(nowMs: Long = SystemClock.elapsedRealtime()) {
        handler.mainThreadHandler.removeCallbacks(expiryRunnable)
        val nextExpiryMs = listOfNotNull(
            mixer.nextExpiryAtMs(),
            deviceMixer.nextExpiryAtMs()
        ).minOrNull() ?: return
        handler.mainThreadHandler.postDelayed(
            expiryRunnable,
            (nextExpiryMs - nowMs).coerceAtLeast(1L)
        )
    }

    private fun stopAllNow() {
        handler.mainThreadHandler.removeCallbacks(expiryRunnable)
        handler.mainThreadHandler.removeCallbacks(envelopeTicker)
        envelopeTickerScheduled = false
        outputSlots.values.forEach { it.cancel() }
        deviceOutputSlot.cancel()

        val controllerNumbers = linkedSetOf<Short>()
        controllerNumbers.addAll(outputSlots.keys)
        controllerNumbers.addAll(mixer.clearAll().map { it.controllerNumber })
        controllerNumbers.addAll(deviceMixer.clearAll().map { it.controllerNumber })
        for (controllerNumber in 0 until ControllerHandler.MAX_GAMEPADS.toInt()) {
            controllerNumbers.add(controllerNumber.toShort())
        }

        outputSlots.clear()
        hostStates.clear()
        audioController = null
        lastAudioContinuousState = ControllerRumbleState.ZERO
        lastAudioContinuousExpiresAtMs = null

        controllerNumbers.forEach { controllerNumber ->
            handler.rumbleManager.handleRumble(
                controllerNumber,
                0,
                0
            )
            handler.rumbleManager.handleRumbleTriggers(controllerNumber, 0, 0)
            handler.rumbleManager.clearAdaptiveTriggers(controllerNumber)
        }
        deviceVibrationCoordinator.stop()
    }

    private fun isStoppingOrStopped(): Boolean =
        stopping || stopped || handler.stopped

    private fun Short.toNormalizedRumble(): Float =
        (toInt() and 0xFFFF) / 65535f

    private fun Float.toMotorShort(): Short =
        (coerceIn(0f, 1f) * 65535f).toInt().toShort()

    private companion object {
        const val NO_CONTROLLER = -1
        const val PLAYER_ONE: Short = 0
        const val ANDROID_RUMBLE_INTERVAL_MS = 33L
        const val USB_RUMBLE_INTERVAL_MS = 20L
        const val AUDIO_CONTINUOUS_WATCHDOG_MS = 5_000L

        // Bound on immediate boundary flushes in RumbleOutputSlot: alternating zero/non-zero
        // inputs cannot produce sink writes faster than this.
        const val EDGE_DISPATCH_FLOOR_MS = 10L

        // Half the envelope time constant: fast enough to trace the transient decay into the
        // sustained level, slow enough to keep re-routing off the hot path.
        const val ENVELOPE_TICK_INTERVAL_MS = 20L
    }
}
