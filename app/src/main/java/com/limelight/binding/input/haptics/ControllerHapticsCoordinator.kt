package com.limelight.binding.input.haptics

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.GenericControllerContext
import com.limelight.binding.input.InputDeviceContext
import com.limelight.binding.input.UsbDeviceContext
import com.limelight.nvstream.Ds5HapticsPcmFrame
import com.limelight.nvstream.jni.MoonBridge
import java.util.concurrent.CountDownLatch

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
    private class OutputSlot {
        var pending: MixedRumbleState? = null
        var runnable: Runnable? = null
        var lastDispatchMs: Long? = null
        var lastOutput: ControllerRumbleState? = null
    }

    private val mixer = ControllerHapticsMixer()
    @Volatile
    private var ds5HapticsPump: Ds5HapticsPump? = null
    @Volatile
    private var ds5HapticsPumpOwner = -1
    private var ds5HapticsControllerNumber: Short = NO_CONTROLLER
    private val ds5LifecycleLock = Any()
    private var ds5LifecycleGeneration = 0L
    private var ds5RequestedOwner = -1
    private val outputSlots = mutableMapOf<Short, OutputSlot>()

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

        for (context in handler.usbDeviceContexts.values) {
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
        if (frame.controllerNumber != ds5HapticsControllerNumber) return
        ds5HapticsPump?.submit(frame)
    }

    fun attachDs5HapticsPump(controllerId: Int, controllerNumber: Short, pump: Ds5HapticsPump) {
        val generation
        synchronized(ds5LifecycleLock) {
            generation = ++ds5LifecycleGeneration
            ds5RequestedOwner = controllerId
        }
        // The attach path arrives from the USB broadcast receiver (main thread);
        // pump startup issues interface and control transfers that must never
        // block it, so run it on the background thread.
        handler.backgroundThreadHandler.post {
            synchronized(ds5LifecycleLock) {
                if (generation != ds5LifecycleGeneration || ds5RequestedOwner != controllerId) {
                    pump.stop()
                    return@post
                }
            }
            if (isStoppingOrStopped()) {
                pump.stop()
                return@post
            }
            // Replace any pump owned by another controller before the new one
            // claims the audio interface.
            ds5HapticsPump?.stop()
            ds5HapticsPump = pump
            ds5HapticsPumpOwner = controllerId
            ds5HapticsControllerNumber = controllerNumber
            pump.start()
        }
    }

    fun detachDs5HapticsPump(controllerId: Int) {
        val shouldWait
        synchronized(ds5LifecycleLock) {
            shouldWait = ds5RequestedOwner == controllerId || ds5HapticsPumpOwner == controllerId
            if (ds5RequestedOwner == controllerId) {
                ds5RequestedOwner = -1
                ds5LifecycleGeneration++
            }
        }
        if (!shouldWait) return

        // Serialize removal with attach and wait for alt 0 restoration before
        // the controller closes its UsbDeviceConnection.
        runOnUsbWorkerAndWait {
            // Only the owning controller may stop the active pump; a stale
            // removal callback from a replaced controller must not kill the
            // new stream.
            if (ds5HapticsPumpOwner != controllerId) return@runOnUsbWorkerAndWait
            ds5HapticsPump?.stop()
            ds5HapticsPump = null
            ds5HapticsPumpOwner = -1
            ds5HapticsControllerNumber = NO_CONTROLLER
        }
    }

    private fun runOnUsbWorkerAndWait(action: () -> Unit) {
        if (Looper.myLooper() == handler.backgroundThreadHandler.looper) {
            action()
            return
        }
        val completed = CountDownLatch(1)
        if (!handler.backgroundThreadHandler.post {
                try {
                    action()
                } finally {
                    completed.countDown()
                }
            }
        ) {
            action()
            return
        }
        var interrupted = false
        while (true) {
            try {
                completed.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    fun clearControllerIfUnavailable(controllerNumber: Short) {
        runOnOutputThread {
            if (stopped || controllerHasRumble(controllerNumber)) return@runOnOutputThread

            resetOutputSlot(controllerNumber)
            handler.rumbleManager.clearDeviceFallback(controllerNumber)
            scheduleNextExpiry()
        }
    }

    /** Replays current logical state when the physical sink for a controller changes. */
    fun onSinkChanged(controllerNumber: Short) {
        runOnOutputThread {
            if (isStoppingOrStopped()) return@runOnOutputThread

            resetOutputSlot(controllerNumber)

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
        val pump = ds5HapticsPump
        ds5HapticsPump = null
        ds5HapticsPumpOwner = -1
        ds5HapticsControllerNumber = NO_CONTROLLER
        synchronized(ds5LifecycleLock) {
            ds5RequestedOwner = -1
            ds5LifecycleGeneration++
        }
        runOnUsbWorkerAndWait { pump?.stop() }
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
        for (context in handler.usbDeviceContexts.values) {
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
        outputSlots.remove(controllerNumber)?.runnable?.let(handler.mainThreadHandler::removeCallbacks)
    }

    private fun queue(mixed: MixedRumbleState) {
        if (isStoppingOrStopped()) return
        val controllerNumber = mixed.controllerNumber
        val slot = outputSlots.getOrPut(controllerNumber, ::OutputSlot)
        slot.pending = mixed
        if (slot.runnable != null) return

        val nowMs = SystemClock.elapsedRealtime()
        val intervalMs = dispatchIntervalMs(controllerNumber)
        val previousDispatchMs = slot.lastDispatchMs ?: (nowMs - intervalMs)
        val delayMs = (intervalMs - (nowMs - previousDispatchMs)).coerceAtLeast(0L)
        val runnable = Runnable {
            val currentSlot = outputSlots[controllerNumber] ?: return@Runnable
            currentSlot.runnable = null
            val latest = currentSlot.pending ?: return@Runnable
            currentSlot.pending = null
            dispatch(latest, currentSlot)
        }
        slot.runnable = runnable
        handler.mainThreadHandler.postDelayed(runnable, delayMs)
    }

    private fun dispatch(mixed: MixedRumbleState, slot: OutputSlot) {
        if (isStoppingOrStopped()) return
        val controllerNumber = mixed.controllerNumber
        val output = mixed.output
        if (slot.lastOutput == output) return

        if (controllerHasRumble(controllerNumber)) {
            handler.rumbleManager.handleRumble(
                controllerNumber,
                output.lowFrequency.toMotorShort(),
                output.highFrequency.toMotorShort(),
                allowDeviceFallback = false
            )
            slot.lastOutput = output
            slot.lastDispatchMs = SystemClock.elapsedRealtime()
        } else if (output.isZero) {
            // A zero HOST value must still cancel a fallback that was started before disconnect.
            handler.rumbleManager.handleRumble(
                controllerNumber,
                0,
                0,
                allowDeviceFallback = true
            )
            slot.lastOutput = null
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
        outputSlots.values.mapNotNull { it.runnable }.forEach(handler.mainThreadHandler::removeCallbacks)

        val controllerNumbers = linkedSetOf<Short>()
        controllerNumbers.addAll(outputSlots.keys)
        controllerNumbers.addAll(mixer.clearAll().map { it.controllerNumber })
        for (controllerNumber in 0 until ControllerHandler.MAX_GAMEPADS.toInt()) {
            controllerNumbers.add(controllerNumber.toShort())
        }

        outputSlots.clear()
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
            handler.rumbleManager.clearAdaptiveTriggers(controllerNumber)
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
