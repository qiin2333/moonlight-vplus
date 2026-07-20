package com.limelight.binding.input.haptics

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.GenericControllerContext
import com.limelight.binding.input.InputDeviceContext
import com.limelight.binding.input.UsbDeviceContext
import com.limelight.nvstream.jni.MoonBridge

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
    private val mixer = ControllerHapticsMixer()
    private val pendingStates = mutableMapOf<Short, MixedRumbleState>()
    private val pendingRunnables = mutableMapOf<Short, Runnable>()
    private val lastDispatchMs = mutableMapOf<Short, Long>()
    private val lastOutputs = mutableMapOf<Short, ControllerRumbleState>()

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
        scheduleNextExpiry(nowMs)
    }

    fun hasRumbleCapableController(): Boolean =
        primaryControllerNumber != NO_CONTROLLER

    fun primaryControllerNumber(): Short? =
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
            handler.sceManager.isRecognizedDevice(inputDevice)
    }

    fun hasRumbleCapability(context: UsbDeviceContext): Boolean {
        if (handler.prefConfig.multiController && !context.assignedControllerNumber) return false
        val capabilities = context.device?.capabilities?.toInt() ?: return false
        return capabilities and MoonBridge.LI_CCAP_RUMBLE.toInt() != 0
    }

    fun noteControllerInput(context: GenericControllerContext) {
        when (context) {
            is InputDeviceContext -> if (hasRumbleCapability(context)) {
                updatePrimaryController(context.controllerNumber)
            }
            is UsbDeviceContext -> if (hasRumbleCapability(context)) {
                updatePrimaryController(context.controllerNumber)
            }
        }
    }

    fun refreshPrimaryController() {
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

        for (i in 0 until handler.usbDeviceContexts.size()) {
            val context = handler.usbDeviceContexts.valueAt(i)
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
            val mixed = mixer.submit(
                controllerNumber,
                RumbleSource.HOST,
                ControllerRumbleState(
                    lowFrequency = lowFrequency.toNormalizedRumble(),
                    highFrequency = highFrequency.toNormalizedRumble()
                ),
                nowMs
            )
            if (!controllerHasRumble(controllerNumber)) {
                handler.rumbleManager.handleRumble(
                    controllerNumber,
                    lowFrequency,
                    highFrequency,
                    allowDeviceFallback = true
                )
                scheduleNextExpiry(nowMs)
                return@runOnOutputThread
            }

            queue(mixed)
            scheduleNextExpiry(nowMs)
        }
    }

    /** Submit a local diagnostic effect without overwriting HOST or AUDIO state. */
    fun submitTest(controllerNumber: Short, lowFrequency: Short, highFrequency: Short) {
        runOnOutputThread {
            if (isStoppingOrStopped()) return@runOnOutputThread
            if (!controllerHasRumble(controllerNumber)) {
                handler.rumbleManager.handleRumble(
                    controllerNumber,
                    lowFrequency,
                    highFrequency,
                    allowDeviceFallback = true
                )
                return@runOnOutputThread
            }
            queue(
                mixer.submit(
                    controllerNumber,
                    RumbleSource.TEST,
                    ControllerRumbleState(
                        lowFrequency = lowFrequency.toNormalizedRumble(),
                        highFrequency = highFrequency.toNormalizedRumble()
                    ),
                    SystemClock.elapsedRealtime()
                )
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

    fun clearControllerIfUnavailable(controllerNumber: Short) {
        runOnOutputThread {
            if (stopped || controllerHasRumble(controllerNumber)) return@runOnOutputThread

            pendingRunnables.remove(controllerNumber)?.let(handler.mainThreadHandler::removeCallbacks)
            pendingStates.remove(controllerNumber)
            lastDispatchMs.remove(controllerNumber)
            lastOutputs.remove(controllerNumber)
            handler.rumbleManager.clearDeviceFallback(controllerNumber)
            scheduleNextExpiry()
        }
    }

    /** Replays current logical state when the physical sink for a controller changes. */
    fun onSinkChanged(controllerNumber: Short) {
        runOnOutputThread {
            if (isStoppingOrStopped()) return@runOnOutputThread

            pendingRunnables.remove(controllerNumber)?.let(handler.mainThreadHandler::removeCallbacks)
            pendingStates.remove(controllerNumber)
            lastDispatchMs.remove(controllerNumber)
            lastOutputs.remove(controllerNumber)

            val nowMs = SystemClock.elapsedRealtime()
            val mixed = mixer.mixedState(controllerNumber, nowMs)
            val hasSink = controllerHasRumble(controllerNumber)
            if (hasSink) {
                handler.rumbleManager.clearDeviceFallback(controllerNumber)
            }
            if (!mixed.isZero && hasSink) {
                queue(mixed)
            }
            scheduleNextExpiry(nowMs)
        }
    }

    /** Stops all owned output synchronously. ControllerHandler lifecycle runs on the UI thread. */
    fun stop() {
        if (stopped || stopping) return
        stopping = true
        stopAllNow()
        primaryControllerNumber = NO_CONTROLLER
        stopped = true
        stopping = false
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

    private fun controllerHasRumble(controllerNumber: Short): Boolean {
        for (i in 0 until handler.inputDeviceContexts.size()) {
            val context = handler.inputDeviceContexts.valueAt(i)
            if (context.controllerNumber == controllerNumber && hasRumbleCapability(context)) {
                return true
            }
        }
        for (i in 0 until handler.usbDeviceContexts.size()) {
            val context = handler.usbDeviceContexts.valueAt(i)
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

    private fun queue(mixed: MixedRumbleState) {
        if (isStoppingOrStopped()) return
        val controllerNumber = mixed.controllerNumber
        pendingStates[controllerNumber] = mixed
        if (pendingRunnables.containsKey(controllerNumber)) return

        val nowMs = SystemClock.elapsedRealtime()
        val intervalMs = dispatchIntervalMs(controllerNumber)
        val previousDispatchMs = lastDispatchMs[controllerNumber] ?: (nowMs - intervalMs)
        val delayMs = (intervalMs - (nowMs - previousDispatchMs)).coerceAtLeast(0L)
        val runnable = Runnable {
            pendingRunnables.remove(controllerNumber)
            val latest = pendingStates.remove(controllerNumber) ?: return@Runnable
            dispatch(latest)
        }
        pendingRunnables[controllerNumber] = runnable
        handler.mainThreadHandler.postDelayed(runnable, delayMs)
    }

    private fun dispatch(mixed: MixedRumbleState) {
        if (isStoppingOrStopped()) return
        val controllerNumber = mixed.controllerNumber
        val output = mixed.output
        if (lastOutputs[controllerNumber] == output) return

        if (controllerHasRumble(controllerNumber)) {
            handler.rumbleManager.handleRumble(
                controllerNumber,
                output.lowFrequency.toMotorShort(),
                output.highFrequency.toMotorShort(),
                allowDeviceFallback = false
            )
            lastOutputs[controllerNumber] = output
            lastDispatchMs[controllerNumber] = SystemClock.elapsedRealtime()
        } else if (output.isZero) {
            // A zero HOST value must still cancel a fallback that was started before disconnect.
            handler.rumbleManager.handleRumble(
                controllerNumber,
                0,
                0,
                allowDeviceFallback = true
            )
            lastOutputs.remove(controllerNumber)
        }
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
        val nextExpiryMs = mixer.nextExpiryAtMs() ?: return
        handler.mainThreadHandler.postDelayed(
            expiryRunnable,
            (nextExpiryMs - nowMs).coerceAtLeast(1L)
        )
    }

    private fun stopAllNow() {
        handler.mainThreadHandler.removeCallbacks(expiryRunnable)
        pendingRunnables.values.forEach(handler.mainThreadHandler::removeCallbacks)

        val controllerNumbers = linkedSetOf<Short>()
        controllerNumbers.addAll(lastOutputs.keys)
        controllerNumbers.addAll(pendingStates.keys)
        controllerNumbers.addAll(mixer.clearAll().map { it.controllerNumber })
        for (controllerNumber in 0 until ControllerHandler.MAX_GAMEPADS.toInt()) {
            controllerNumbers.add(controllerNumber.toShort())
        }

        pendingStates.clear()
        pendingRunnables.clear()
        lastDispatchMs.clear()
        lastOutputs.clear()
        audioController = null
        lastAudioContinuousState = ControllerRumbleState.ZERO
        lastAudioContinuousExpiresAtMs = null

        controllerNumbers.forEach { controllerNumber ->
            handler.rumbleManager.handleRumble(
                controllerNumber,
                0,
                0,
                allowDeviceFallback = true
            )
            handler.rumbleManager.handleRumbleTriggers(controllerNumber, 0, 0)
        }
    }

    private fun isStoppingOrStopped(): Boolean =
        stopping || stopped || handler.stopped

    private fun Short.toNormalizedRumble(): Float =
        (toInt() and 0xFFFF) / 65535f

    private fun Float.toMotorShort(): Short =
        (coerceIn(0f, 1f) * 65535f).toInt().toShort()

    private companion object {
        const val NO_CONTROLLER = -1
        const val ANDROID_RUMBLE_INTERVAL_MS = 33L
        const val USB_RUMBLE_INTERVAL_MS = 20L
        const val AUDIO_CONTINUOUS_WATCHDOG_MS = 5_000L
    }
}
