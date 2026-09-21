package com.limelight.binding.input.haptics

import com.limelight.LimeLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single distribution point for every phone-motor write owned by the controller subsystem.
 *
 * Game sources are mixed, touch feedback temporarily takes priority, and audio haptics can claim
 * the actuator exclusively. Native vibrator calls run on a dedicated latest-wins worker so a
 * blocked vendor Binder cannot stall the UI thread or create an unbounded executor queue.
 *
 * Start and stop edges are submitted without observing or replaying completed pulses. Held
 * level replacements remain paced for vendor safety. A blocked native call cannot be preempted;
 * newer state replaces pending work rather than accumulating late haptic events.
 */
internal class DeviceVibrationCoordinator(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallback: (Runnable) -> Unit,
    private val vibrateDevice: (Int, Long) -> Unit,
    private val cancelDeviceVibration: () -> Unit,
    executor: ScheduledExecutorService = newWorker(),
    minimumIntervalMs: Long = MINIMUM_DEVICE_INTERVAL_MS
) {
    enum class GameSource {
        ROUTED_GAME,
        LEGACY_OVERLAY
    }

    private data class MotorState(val amplitude: Int)

    private data class VibrationCommand(
        val amplitude: Int,
        val durationMs: Long,
        val generation: Long,
        val sequence: Long,
        val urgent: Boolean = false,
        val terminal: Boolean = false
    ) {
        val isZero: Boolean
            get() = amplitude == 0
    }

    private val lock = Any()
    private val gameSources = mutableMapOf<GameSource, MotorState>()
    private val dispatcher = LatestWinsDispatcher(
        minimumIntervalMs = minimumIntervalMs,
        executor = executor,
        dispatch = ::dispatch,
        onError = { error ->
            LimeLog.warning("Device vibration dispatch failed: ${error.message}")
        },
        isUrgent = { it.urgent }
    )

    @Volatile
    private var generation = 0L
    private var audioOwned = false
    private var touchActive = false
    private var touchEpoch = 0L
    private var closed = false
    private var touchCompletion: Runnable? = null
    private var gameRefreshCallback: Runnable? = null
    private var outputSequence = 0L
    private var lastGameCommandAmplitude = -1
    private var outputWriteInFlight = false
    private var touchAudioPreempted = false

    @Volatile
    private var onAudioTouchPreemptRequested: (() -> Boolean)? = null

    @Volatile
    private var onAudioTouchFinished: (() -> Unit)? = null

    private var levelAmplitude = 0

    fun setAudioTouchCallbacks(
        onPreemptRequested: (() -> Boolean)?,
        onFinished: (() -> Unit)?
    ) {
        onAudioTouchPreemptRequested = onPreemptRequested
        onAudioTouchFinished = onFinished
    }

    /**
     * [targetAmplitude] is 0-255 and already carries every routing gain: callers fold the two
     * motor channels through [SingleMotorRumbleFold] upstream, never here.
     */
    fun submitGameRumble(
        source: GameSource,
        targetAmplitude: Int,
        strengthPercent: Int
    ) {
        val command = synchronized(lock) {
            if (closed) return
            // Keep the API's full amplitude range. Transport pacing belongs to the dispatcher,
            // not a minimum level or history-dependent quantization of the authored signal.
            val state = MotorState(
                (targetAmplitude.coerceIn(0, 255) *
                    strengthPercent.coerceIn(0, 200) / 100.0).toInt().coerceIn(0, 255)
            )
            if (state.amplitude == 0) {
                gameSources.remove(source)
            } else {
                gameSources[source] = state
            }
            if (audioOwned || touchActive) {
                null
            } else {
                gameAmplitudeCommandLocked(mixedGameAmplitudeLocked())
            }
        }
        command?.let(dispatcher::submit)
    }

    fun playTouchHaptic(lowFrequency: Short, highFrequency: Short, durationMs: Int) {
        val duration = durationMs.toLong().coerceIn(1L, MAXIMUM_TOUCH_DURATION_MS)
        val audioWasOwned = synchronized(lock) {
            if (closed) return
            audioOwned
        }
        if (audioWasOwned && onAudioTouchPreemptRequested?.invoke() != true) return

        val command: VibrationCommand
        val completion: Runnable
        synchronized(lock) {
            if (closed) return
            if (audioOwned) {
                audioOwned = false
                generation++
                clearGameRefreshLocked()
            }
            touchAudioPreempted = touchAudioPreempted || audioWasOwned
            touchCompletion?.let(removeCallback)
            clearGameRefreshLocked()
            touchActive = true
            val epoch = ++touchEpoch
            val currentGeneration = ++generation
            command = VibrationCommand(
                amplitude = SingleMotorRumbleFold.amplitude(lowFrequency, highFrequency),
                durationMs = duration,
                generation = currentGeneration,
                sequence = ++outputSequence,
                // Touch feedback is a discrete edge, not a continuously refreshed game-rumble
                // level. It must not inherit the 250 ms pacing used for long-running effects.
                // The same single worker and latest-wins slot still serialize vendor calls and
                // keep a blocked vibrator from creating an unbounded queue.
                urgent = true
            )
            completion = Runnable { finishTouchHaptic(epoch) }
            touchCompletion = completion
        }
        dispatcher.clearPending()
        dispatcher.submit(command)
        postDelayed(completion, duration)
    }

    /** Audio renderers call this before their first phone-motor write. */
    fun claimForAudio(): Boolean {
        var ownershipChanged = false
        val readyForAudio = synchronized(lock) {
            if (closed || touchActive) return false
            if (!audioOwned) {
                audioOwned = true
                generation++
                touchActive = false
                touchEpoch++
                touchCompletion?.let(removeCallback)
                touchCompletion = null
                clearGameRefreshLocked()
                ownershipChanged = true
            }
            !outputWriteInFlight
        }
        if (ownershipChanged) dispatcher.clearPending()
        return readyForAudio
    }

    /** Restores the latest mixed game state after every audio backend has stopped. */
    fun releaseFromAudio() {
        val command = synchronized(lock) {
            if (closed || !audioOwned) return
            audioOwned = false
            generation++
            gameAmplitudeCommandLocked(mixedGameAmplitudeLocked(), forceWrite = true)
        }
        command?.let(dispatcher::submit)
    }

    /** Stops without waiting for a possibly wedged vibrator Binder transaction. */
    fun stop() {
        val finalCommand = synchronized(lock) {
            if (closed) return
            closed = true
            audioOwned = false
            touchActive = false
            touchAudioPreempted = false
            touchEpoch++
            touchCompletion?.let(removeCallback)
            touchCompletion = null
            clearGameRefreshLocked()
            gameSources.clear()
            VibrationCommand(
                amplitude = 0,
                durationMs = GAME_SOURCE_LEASE_MS,
                generation = ++generation,
                sequence = ++outputSequence,
                urgent = true,
                terminal = true
            )
        }
        dispatcher.clearPending()
        dispatcher.close(finalCommand)
    }

    private fun finishTouchHaptic(epoch: Long) {
        var restoreAudio = false
        val command = synchronized(lock) {
            if (closed || audioOwned || !touchActive || epoch != touchEpoch) return
            touchActive = false
            touchCompletion = null
            restoreAudio = touchAudioPreempted
            touchAudioPreempted = false
            generation++
            gameAmplitudeCommandLocked(mixedGameAmplitudeLocked(), forceWrite = true)
        }
        command?.let(dispatcher::submit)
        if (restoreAudio) onAudioTouchFinished?.invoke()
    }

    private fun mixedGameAmplitudeLocked(): Int {
        var amplitude = 0
        gameSources.values.forEach { state ->
            amplitude = maxOf(amplitude, state.amplitude)
        }
        return amplitude
    }

    /** Restores ownership explicitly; equal levels otherwise only renew their finite lease. */
    private fun gameAmplitudeCommandLocked(
        amplitude: Int,
        forceWrite: Boolean = false
    ): VibrationCommand? {
        val boundary = (levelAmplitude == 0) != (amplitude == 0)
        levelAmplitude = amplitude
        if (amplitude == 0) clearGameRefreshLocked()
        if (!forceWrite && lastGameCommandAmplitude == amplitude) {
            if (amplitude > 0) scheduleGameRefreshLocked(amplitude)
            return null
        }
        return levelCommandLocked(
            amplitude,
            forceResubmit = forceWrite,
            urgent = forceWrite || boundary || amplitude == 0
        )
    }

    private fun levelCommandLocked(
        amplitude: Int,
        forceResubmit: Boolean = false,
        urgent: Boolean = false
    ): VibrationCommand {
        if (forceResubmit || amplitude != lastGameCommandAmplitude) {
            outputSequence++
            lastGameCommandAmplitude = amplitude
        }
        if (amplitude > 0) {
            scheduleGameRefreshLocked(amplitude)
        }
        return VibrationCommand(
            amplitude = amplitude,
            durationMs = GAME_SOURCE_LEASE_MS,
            generation = generation,
            sequence = outputSequence,
            urgent = urgent
        )
    }

    private fun scheduleGameRefreshLocked(amplitude: Int) {
        if (amplitude == 0) {
            clearGameRefreshLocked()
            return
        }
        if (gameRefreshCallback != null) return

        val callback = Runnable { refreshGameLease() }
        gameRefreshCallback = callback
        postDelayed(callback, GAME_SOURCE_REFRESH_MS)
    }

    private fun clearGameRefreshLocked() {
        gameRefreshCallback?.let(removeCallback)
        gameRefreshCallback = null
    }

    private fun refreshGameLease() {
        val command = synchronized(lock) {
            gameRefreshCallback = null
            if (closed || audioOwned || touchActive) return
            if (levelAmplitude == 0) return
            levelCommandLocked(levelAmplitude, forceResubmit = true).takeUnless { it.isZero }
        }
        command?.let(dispatcher::submit)
    }

    private fun dispatch(command: VibrationCommand) {
        // Admit the write under the ownership lock, then release it before entering vendor Binder.
        // Audio only writes after claimForAudio() reports that no admitted game write is in flight.
        val admitted = synchronized(lock) {
            if (!command.terminal && (audioOwned || command.generation != generation)) {
                false
            } else {
                outputWriteInFlight = true
                true
            }
        }
        if (!admitted) return

        try {
            if (command.isZero) {
                cancelDeviceVibration()
            } else {
                vibrateDevice(command.amplitude, command.durationMs)
            }
        } finally {
            synchronized(lock) {
                outputWriteInFlight = false
            }
        }
    }

    private companion object {
        val THREAD_NUMBER = AtomicInteger()
        // Long one-shot effects are reprogrammed only four times per second. Some vendor
        // vibrator services deadlock when effects are replaced at controller packet rate.
        // Start/stop edges bypass level pacing. Native writes stay serialized; hardware
        // validation is still required for repeated edges on affected vendor services.
        const val MINIMUM_DEVICE_INTERVAL_MS = 250L
        const val GAME_SOURCE_LEASE_MS = 500L
        const val GAME_SOURCE_REFRESH_MS = 375L
        const val MAXIMUM_TOUCH_DURATION_MS = 1_000L

        fun newWorker(): ScheduledExecutorService {
            val threadNumber = THREAD_NUMBER.incrementAndGet()
            return Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "DeviceVibration-$threadNumber").apply { isDaemon = true }
            }
        }
    }
}
