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
 */
internal class DeviceVibrationCoordinator(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallback: (Runnable) -> Unit,
    private val vibrateDevice: (Int, Long) -> Unit,
    private val cancelDeviceVibration: () -> Unit,
    executor: ScheduledExecutorService = newWorker()
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
        }
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

    fun submitGameRumble(
        source: GameSource,
        lowFrequency: Short,
        highFrequency: Short,
        strengthPercent: Int
    ) {
        val command = synchronized(lock) {
            if (closed) return
            val previousAmplitude = gameSources[source]?.amplitude
            val state = MotorState(
                quantizeGameAmplitude(
                    simulatedAmplitude(
                        lowFrequency.toInt() and 0xFFFF,
                        highFrequency.toInt() and 0xFFFF,
                        strengthPercent
                    ),
                    previousAmplitude
                )
            )
            if (state.amplitude == 0) {
                gameSources.remove(source)
            } else {
                gameSources[source] = state
            }
            if (audioOwned || touchActive) null else currentGameCommandLocked()
        }
        command?.let(dispatcher::submit)
    }

    fun playTouchHaptic(lowFrequency: Short, highFrequency: Short, durationMs: Int) {
        val duration = durationMs.toLong().coerceIn(1L, MAXIMUM_TOUCH_DURATION_MS)
        val command: VibrationCommand
        val completion: Runnable
        synchronized(lock) {
            if (closed || audioOwned) return
            touchCompletion?.let(removeCallback)
            clearGameRefreshLocked()
            touchActive = true
            val epoch = ++touchEpoch
            val currentGeneration = ++generation
            command = VibrationCommand(
                amplitude = simulatedAmplitude(
                    lowFrequency.toInt() and 0xFFFF,
                    highFrequency.toInt() and 0xFFFF
                ),
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
    fun claimForAudio() {
        synchronized(lock) {
            if (closed || audioOwned) return
            audioOwned = true
            generation++
            touchActive = false
            touchEpoch++
            touchCompletion?.let(removeCallback)
            touchCompletion = null
            clearGameRefreshLocked()
        }
        dispatcher.clearPending()
    }

    /** Restores the latest mixed game state after every audio backend has stopped. */
    fun releaseFromAudio() {
        val command = synchronized(lock) {
            if (closed || !audioOwned) return
            audioOwned = false
            generation++
            currentGameCommandLocked(forceRefresh = true)
        }
        dispatcher.submit(command)
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
            currentGameCommandLocked(forceRefresh = true)
        }
        dispatcher.submit(command)
    }

    private fun currentGameCommandLocked(forceRefresh: Boolean = false): VibrationCommand {
        var amplitude = 0
        gameSources.values.forEach { state ->
            amplitude = maxOf(amplitude, state.amplitude)
        }
        scheduleGameRefreshLocked(amplitude)
        if (forceRefresh || amplitude != lastGameCommandAmplitude) {
            outputSequence++
            lastGameCommandAmplitude = amplitude
        }
        return VibrationCommand(
            amplitude = amplitude,
            durationMs = GAME_SOURCE_LEASE_MS,
            generation = generation,
            sequence = outputSequence
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
            currentGameCommandLocked(forceRefresh = true).takeUnless { it.isZero }
        }
        command?.let(dispatcher::submit)
    }

    private fun dispatch(command: VibrationCommand) {
        // The generation check invalidates work captured before an audio/touch ownership change.
        if (!command.terminal && command.generation != generation) return
        if (command.isZero) {
            cancelDeviceVibration()
        } else {
            vibrateDevice(command.amplitude, command.durationMs)
        }
    }

    private fun simulatedAmplitude(
        lowFrequency: Int,
        highFrequency: Int,
        strengthPercent: Int = 100
    ): Int {
        val mixedAmplitude =
            ((lowFrequency ushr 8) * 0.80) + ((highFrequency ushr 8) * 0.33)
        return minOf(
            255,
            (mixedAmplitude * strengthPercent.coerceIn(0, 200) / 100.0).toInt()
        )
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
        const val MINIMUM_DEVICE_INTERVAL_MS = 250L
        const val GAME_SOURCE_LEASE_MS = 500L
        const val GAME_SOURCE_REFRESH_MS = 375L
        const val MAXIMUM_TOUCH_DURATION_MS = 1_000L
        const val GAME_AMPLITUDE_STEP = 16
        const val GAME_AMPLITUDE_HYSTERESIS = 12

        fun newWorker(): ScheduledExecutorService {
            val threadNumber = THREAD_NUMBER.incrementAndGet()
            return Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "DeviceVibration-$threadNumber").apply { isDaemon = true }
            }
        }
    }
}
