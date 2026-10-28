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
    minimumIntervalMs: Long = MINIMUM_DEVICE_INTERVAL_MS,
    private val playDeviceTick: (() -> Unit)? = null,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    detailTickIntervalMs: Long = 90L,
    dispatcherClockNanos: () -> Long = System::nanoTime
) {
    private val detailIntervalMs = detailTickIntervalMs.coerceAtLeast(40L)
    enum class GameSource {
        ROUTED_GAME,
        LEGACY_OVERLAY,
        AUTHORED_V3
    }

    /** Result of an audio claim; ownership and write readiness are independent states. */
    enum class AudioClaimResult {
        REJECTED,
        OWNED_NOT_READY,
        OWNED_READY
    }

    private data class MotorState(val amplitude: Int, val expiresAtMs: Long = Long.MAX_VALUE)

    private data class VibrationCommand(
        val amplitude: Int,
        val durationMs: Long,
        val generation: Long,
        val sequence: Long,
        val urgent: Boolean = false,
        val terminal: Boolean = false,
        val tick: Boolean = false,
        val expiresAtMs: Long = Long.MAX_VALUE,
        val minimumIntervalMs: Long? = null
    ) {
        val isZero: Boolean
            get() = amplitude == 0 && !tick
    }

    private val lock = Any()
    private val gameSources = mutableMapOf<GameSource, MotorState>()
    private val dispatcher = LatestWinsDispatcher(
        minimumIntervalMs = minimumIntervalMs,
        executor = executor,
        clockNanos = dispatcherClockNanos,
        dispatch = ::dispatch,
        onError = { error ->
            LimeLog.warning("Device vibration dispatch failed: ${error.message}")
        },
        isUrgent = { it.urgent },
        intervalForValueMs = { it.minimumIntervalMs ?: minimumIntervalMs },
        intervalFromDispatchStart = { it.tick }
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
    private var detailOwned = false
    private var detailTickStarted = false
    private var detailInputAtMs = 0L
    private var detailWatchdog: Runnable? = null
    private var v3Owned = false

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

    fun resetAuthoredV3() {
        val stop = synchronized(lock) {
            if (closed) return
            if (!audioOwned && !touchActive) generation++
            gameSources.remove(GameSource.AUTHORED_V3)
            v3Owned = false
            clearGameRefreshLocked()
            detailOwned = false
            detailTickStarted = false
            detailWatchdog?.let(removeCallback)
            detailWatchdog = null
            lastGameCommandAmplitude = -1
            if (audioOwned || touchActive) null else
                currentGameCommandLocked(forceRefresh = true).copy(minimumIntervalMs = 0L)
        }
        if (stop != null) dispatcher.clearPending()
        stop?.let(dispatcher::submit)
    }

    /** V3 owns a separate source; ordinary latest states remain available when it releases. */
    fun submitAuthoredV3Body(low: Short, high: Short, strengthPercent: Int, active: Boolean,
                             expiresAtMs: Long = clockMs() + 200L) {
        val acquired = synchronized(lock) {
            if (closed) return
            val acquiring = active && !v3Owned
            if (acquiring) {
                v3Owned = true
                gameSources.remove(GameSource.AUTHORED_V3)
                clearGameRefreshLocked()
                lastGameCommandAmplitude = -1
                if (!audioOwned && !touchActive) generation++
            } else if (!active && v3Owned) {
                v3Owned = false
                gameSources.remove(GameSource.AUTHORED_V3)
                if (!audioOwned && !touchActive) generation++
            }
            acquiring && !audioOwned && !touchActive
        }
        if (acquired) dispatcher.clearPending()
        if (!active) {
            val stop = synchronized(lock) {
                if (audioOwned || touchActive || detailOwned) null else
                    currentGameCommandLocked(forceRefresh = true).copy(minimumIntervalMs = 0L)
            }
            stop?.let(dispatcher::submit)
            return
        }
        submitGameRumble(GameSource.AUTHORED_V3, low, high, strengthPercent, expiresAtMs)
    }

    /** Refresh the authored-detail lease on every IR frame, including silent frames. */
    fun submitAuthoredDetail(tick: Boolean, strengthPercent: Int, active: Boolean = true) {
        submitDetail(tick, strengthPercent, active, null)
    }

    /** V3 carries source-timed events. Sink backpressure replaces pending work, with no rate gate. */
    fun submitAuthoredV3(tick: Boolean, strengthPercent: Int, expiresAtMs: Long, active: Boolean = true) {
        submitDetail(tick, strengthPercent, active, expiresAtMs)
    }

    private fun submitDetail(tick: Boolean, strengthPercent: Int, active: Boolean, sourceExpiryMs: Long?) {
        val command = synchronized(lock) {
            if (closed || playDeviceTick == null) return
            if (!active) {
                if (!detailOwned) return
                detailOwned = false
                if (sourceExpiryMs == null || !audioOwned && !touchActive) generation++
                detailWatchdog?.let(removeCallback)
                detailWatchdog = null
                if (audioOwned || touchActive) null else currentGameCommandLocked(forceRefresh = true)
                    .let { if (sourceExpiryMs != null) it.copy(minimumIntervalMs = 0L) else it }
            } else {
                val acquired = !detailOwned
                if (acquired) {
                    detailOwned = true
                    detailTickStarted = false
                    if (sourceExpiryMs == null || !audioOwned && !touchActive) generation++
                    clearGameRefreshLocked()
                    // Authored PCM supersedes the previous game's ordinary-rumble snapshot.
                    // Otherwise ending the PCM lease could revive an old nonzero waveform.
                    if (sourceExpiryMs == null) gameSources.clear()
                    else gameSources.remove(GameSource.AUTHORED_V3)
                }
                detailInputAtMs = clockMs()
                scheduleDetailWatchdogLocked()
                if (audioOwned || touchActive) null
                else if (tick && strengthPercent > 0) VibrationCommand(
                    amplitude = 0,
                    durationMs = 0,
                    generation = generation,
                    sequence = ++outputSequence,
                    tick = true,
                    expiresAtMs = sourceExpiryMs ?: (clockMs() + 150L),
                    // Public predefined effects have fixed strength; lower user strength
                    // limits detail density instead of claiming unsupported amplitude scaling.
                    // A preceding cancel/ordinary waveform must not delay the first native
                    // point into the next source event. Pace only after a tick has begun.
                    minimumIntervalMs = if (sourceExpiryMs != null || !detailTickStarted) 0L else
                        (detailIntervalMs * 100 / strengthPercent.coerceIn(1, 100))
                )
                else if (acquired) VibrationCommand(0, 0, generation, ++outputSequence,
                    minimumIntervalMs = 0L)
                else null
            }
        }
        command?.let(dispatcher::submit)
    }

    private fun scheduleDetailWatchdogLocked() {
        if (detailWatchdog != null) return
        val callback = Runnable {
            val command = synchronized(lock) {
                detailWatchdog = null
                if (closed || !detailOwned) return@Runnable
                if (clockMs() - detailInputAtMs < 200L) {
                    scheduleDetailWatchdogLocked()
                    return@Runnable
                }
                detailOwned = false
                if (!audioOwned && !touchActive) generation++
                if (audioOwned || touchActive) null else currentGameCommandLocked(forceRefresh = true)
            }
            command?.let(dispatcher::submit)
        }
        detailWatchdog = callback
        postDelayed(callback, 200L)
    }

    /**
     * [targetAmplitude] is 0-255 and already carries every routing gain: callers fold the two
     * motor channels through [SingleMotorRumbleFold] upstream, never here.
     */
    fun submitGameRumble(source: GameSource, targetAmplitude: Int, strengthPercent: Int) =
        submitGameAmplitude(source, targetAmplitude, strengthPercent, Long.MAX_VALUE)

    private fun submitGameAmplitude(
        source: GameSource,
        targetAmplitude: Int,
        strengthPercent: Int,
        expiresAtMs: Long
    ) {
        val command = synchronized(lock) {
            if (closed) return
            // Keep the API's full amplitude range. Transport pacing belongs to the dispatcher,
            // not a minimum level or history-dependent quantization of the authored signal.
            val state = MotorState(
                (targetAmplitude.coerceIn(0, 255) *
                    strengthPercent.coerceIn(0, 200) / 100.0).toInt().coerceIn(0, 255), expiresAtMs
            )
            if (state.amplitude == 0) {
                gameSources.remove(source)
            } else {
                gameSources[source] = state
            }
            if (audioOwned || touchActive || detailOwned) {
                null
            } else {
                gameAmplitudeCommandLocked(mixedGameAmplitudeLocked())
            }
        }
        command?.let(dispatcher::submit)
    }

    fun submitGameRumble(source: GameSource, lowFrequency: Short, highFrequency: Short,
                         strengthPercent: Int, expiresAtMs: Long = Long.MAX_VALUE) =
        submitGameAmplitude(source, SingleMotorRumbleFold.amplitude(lowFrequency, highFrequency),
            strengthPercent, expiresAtMs)

    private fun currentGameCommandLocked(forceRefresh: Boolean = false): VibrationCommand =
        gameAmplitudeCommandLocked(mixedGameAmplitudeLocked(), forceWrite = forceRefresh)
            ?: levelCommandLocked(levelAmplitude)

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
    fun claimForAudio(): AudioClaimResult {
        var ownershipChanged = false
        val result = synchronized(lock) {
            if (closed || touchActive) return AudioClaimResult.REJECTED
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
            if (outputWriteInFlight) {
                AudioClaimResult.OWNED_NOT_READY
            } else {
                AudioClaimResult.OWNED_READY
            }
        }
        if (ownershipChanged) dispatcher.clearPending()
        return result
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
            detailOwned = false
            v3Owned = false
            detailWatchdog?.let(removeCallback)
            detailWatchdog = null
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
        if (detailOwned) return 0
        if (v3Owned) return gameSources[GameSource.AUTHORED_V3]?.amplitude ?: 0
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
            urgent = urgent,
            expiresAtMs = if (v3Owned) gameSources[GameSource.AUTHORED_V3]?.expiresAtMs ?: Long.MAX_VALUE else Long.MAX_VALUE
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
            if (!command.terminal && (audioOwned || command.generation != generation ||
                    !command.isZero && clockMs() > command.expiresAtMs ||
                    command.tick && (touchActive || !detailOwned || clockMs() > command.expiresAtMs))) {
                false
            } else {
                outputWriteInFlight = true
                if (command.tick) detailTickStarted = true
                true
            }
        }
        if (!admitted) return

        try {
            if (command.tick) {
                playDeviceTick?.invoke()
            } else if (command.isZero) {
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
