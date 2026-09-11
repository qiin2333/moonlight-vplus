package com.limelight.binding.input.haptics

import android.os.SystemClock
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
 * Game amplitude follows a pulse/level state machine. An onset from silence is observed for
 * [OBSERVATION_WINDOW_MS] before it is committed as a sustained level write. If the signal
 * returns to zero inside the window, the measured pulse is emitted once as an urgent short
 * one-shot which bypasses the level pacing - collapsing it into the latest-wins level path is
 * how short pulses used to disappear entirely. Zero input produces zero output: a pulse ends
 * by finishing its one-shot and no synthetic tail is appended.
 */
internal class DeviceVibrationCoordinator(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallback: (Runnable) -> Unit,
    private val vibrateDevice: (Int, Long) -> Unit,
    private val cancelDeviceVibration: () -> Unit,
    executor: ScheduledExecutorService = newWorker(),
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
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
        minimumIntervalMs = MINIMUM_DEVICE_INTERVAL_MS,
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

    private var observationActive = false
    private var observationStartMs = 0L
    private var observationPeak = 0
    private var observationLatest = 0
    private var observationDeadline: Runnable? = null
    private var levelAmplitude = 0

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
            val previousAmplitude = gameSources[source]?.amplitude
            val state = MotorState(
                quantizeGameAmplitude(
                    (targetAmplitude.coerceIn(0, 255) *
                        strengthPercent.coerceIn(0, 200) / 100.0).toInt(),
                    previousAmplitude
                )
            )
            if (state.amplitude == 0) {
                gameSources.remove(source)
            } else {
                gameSources[source] = state
            }
            if (audioOwned || touchActive) {
                null
            } else {
                gameAmplitudeCommandLocked(clockMs(), mixedGameAmplitudeLocked())
            }
        }
        command?.let(dispatcher::submit)
    }

    fun playTouchHaptic(lowFrequency: Short, highFrequency: Short, durationMs: Int) {
        val duration = durationMs.toLong().coerceIn(1L, MAXIMUM_TOUCH_DURATION_MS)
        val command: VibrationCommand
        val completion: Runnable
        synchronized(lock) {
            if (closed || audioOwned) return
            discardObservationLocked()
            touchCompletion?.let(removeCallback)
            clearGameRefreshLocked()
            touchActive = true
            val epoch = ++touchEpoch
            val currentGeneration = ++generation
            command = VibrationCommand(
                amplitude = SingleMotorRumbleFold.amplitude(lowFrequency, highFrequency),
                durationMs = duration,
                generation = currentGeneration,
                sequence = ++outputSequence
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
            if (closed) return false
            if (!audioOwned) {
                audioOwned = true
                generation++
                touchActive = false
                touchEpoch++
                touchCompletion?.let(removeCallback)
                touchCompletion = null
                discardObservationLocked()
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
            gameAmplitudeCommandLocked(clockMs(), mixedGameAmplitudeLocked(), forceWrite = true)
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
            touchEpoch++
            touchCompletion?.let(removeCallback)
            touchCompletion = null
            discardObservationLocked()
            clearGameRefreshLocked()
            gameSources.clear()
            VibrationCommand(
                amplitude = 0,
                durationMs = GAME_SOURCE_LEASE_MS,
                generation = ++generation,
                sequence = ++outputSequence,
                terminal = true
            )
        }
        dispatcher.clearPending()
        dispatcher.close(finalCommand)
    }

    private fun finishTouchHaptic(epoch: Long) {
        val command = synchronized(lock) {
            if (closed || audioOwned || !touchActive || epoch != touchEpoch) return
            touchActive = false
            touchCompletion = null
            generation++
            gameAmplitudeCommandLocked(clockMs(), mixedGameAmplitudeLocked(), forceWrite = true)
        }
        command?.let(dispatcher::submit)
    }

    private fun mixedGameAmplitudeLocked(): Int {
        var amplitude = 0
        gameSources.values.forEach { state ->
            amplitude = maxOf(amplitude, state.amplitude)
        }
        return amplitude
    }

    /**
     * Maps a mixed game amplitude to the next command, or null when no write is due yet:
     * onsets from silence are observed for a short window before committing as a level, and a
     * pulse measured inside that window is returned as an urgent one-shot.
     *
     * [forceWrite] is set by ownership-release paths: audio or touch stopped the motor
     * underneath, so an unchanged nonzero level must be reprogrammed, not suppressed.
     */
    private fun gameAmplitudeCommandLocked(
        nowMs: Long,
        amplitude: Int,
        forceWrite: Boolean = false
    ): VibrationCommand? {
        if (amplitude == 0) {
            val pulse = stopObservationLocked(nowMs)
            clearGameRefreshLocked()
            levelAmplitude = 0
            if (pulse != null) {
                return pulse
            }
            if (lastGameCommandAmplitude == 0) {
                return null
            }
            return levelCommandLocked(0)
        }

        if (observationActive) {
            observationPeak = maxOf(observationPeak, amplitude)
            observationLatest = amplitude
            return null
        }
        if (levelAmplitude == amplitude) {
            if (!forceWrite) {
                scheduleGameRefreshLocked(amplitude)
                return null
            }
            return levelCommandLocked(amplitude, forceResubmit = true)
        }
        if (levelAmplitude > 0) {
            // The motor is already running; rewrite the level without observing.
            levelAmplitude = amplitude
            return levelCommandLocked(amplitude)
        }
        startObservationLocked(nowMs, amplitude)
        return null
    }

    private fun levelCommandLocked(
        amplitude: Int,
        forceResubmit: Boolean = false
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
            sequence = outputSequence
        )
    }

    private fun startObservationLocked(nowMs: Long, amplitude: Int) {
        observationActive = true
        observationStartMs = nowMs
        observationPeak = amplitude
        observationLatest = amplitude
        val deadline = Runnable { commitObservation() }
        observationDeadline = deadline
        postDelayed(deadline, OBSERVATION_WINDOW_MS)
    }

    /** Ends the observation, returning the measured pulse, or null when it was too short to feel. */
    private fun stopObservationLocked(nowMs: Long): VibrationCommand? {
        val deadline = observationDeadline ?: return null
        removeCallback(deadline)
        observationDeadline = null
        observationActive = false
        val measuredMs = nowMs - observationStartMs
        if (measuredMs < MINIMUM_PULSE_MS) {
            return null
        }
        outputSequence++
        return VibrationCommand(
            amplitude = observationPeak,
            durationMs = measuredMs.coerceAtMost(OBSERVATION_WINDOW_MS),
            generation = generation,
            sequence = outputSequence,
            urgent = true
        )
    }

    private fun discardObservationLocked() {
        observationDeadline?.let(removeCallback)
        observationDeadline = null
        observationActive = false
    }

    private fun commitObservation() {
        val command = synchronized(lock) {
            if (closed || !observationActive) return
            observationActive = false
            observationDeadline = null
            levelAmplitude = observationLatest
            levelCommandLocked(levelAmplitude)
        }
        dispatcher.submit(command)
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
            if (closed || audioOwned || touchActive || observationActive) return
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

    private fun quantizeGameAmplitude(rawAmplitude: Int, previousAmplitude: Int?): Int {
        if (rawAmplitude == 0) return 0
        if (previousAmplitude != null &&
            kotlin.math.abs(rawAmplitude - previousAmplitude) < GAME_AMPLITUDE_HYSTERESIS
        ) {
            return previousAmplitude
        }
        return (
            (rawAmplitude + GAME_AMPLITUDE_STEP / 2) / GAME_AMPLITUDE_STEP *
                GAME_AMPLITUDE_STEP
            ).coerceIn(GAME_AMPLITUDE_STEP, 255)
    }

    private companion object {
        val THREAD_NUMBER = AtomicInteger()
        // Long one-shot effects are reprogrammed only four times per second. Some vendor
        // vibrator services deadlock when effects are replaced at controller packet rate.
        // Measured pulses bypass this via the dispatcher's urgent path: they are separate
        // short one-shots, not reprogramming of a running effect.
        const val MINIMUM_DEVICE_INTERVAL_MS = 250L
        const val GAME_SOURCE_LEASE_MS = 500L
        const val GAME_SOURCE_REFRESH_MS = 375L
        const val MAXIMUM_TOUCH_DURATION_MS = 1_000L
        const val GAME_AMPLITUDE_STEP = 16
        const val GAME_AMPLITUDE_HYSTERESIS = 12
        const val OBSERVATION_WINDOW_MS = 60L
        const val MINIMUM_PULSE_MS = 10L

        fun newWorker(): ScheduledExecutorService {
            val threadNumber = THREAD_NUMBER.incrementAndGet()
            return Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "DeviceVibration-$threadNumber").apply { isDaemon = true }
            }
        }
    }
}
