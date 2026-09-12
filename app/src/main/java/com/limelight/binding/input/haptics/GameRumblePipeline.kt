package com.limelight.binding.input.haptics

/** Wires the three stages on one scheduler; Android only supplies capabilities and transports. */
internal class GameRumblePipeline(
    private val renderer: RumbleOutputRenderer,
    private val context: (Short) -> GameRumbleContext,
    private val clockMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit
) {
    private val trackers = mutableMapOf<Short, RumbleSignalTracker>()
    private var stopped = false
    private var tickScheduled = false
    private val ticker = Runnable {
        tickScheduled = false
        if (!stopped) replay(0)
    }

    fun submitHost(number: Short, state: ControllerRumbleState) {
        if (stopped) return
        val now = clockMs()
        val features = trackers.getOrPut(number) { RumbleSignalTracker() }.sample(state, now)
        route(number, RumbleSource.HOST, state, features, now)
    }

    fun submitTest(number: Short, state: ControllerRumbleState) {
        if (!stopped) route(number, RumbleSource.TEST, state, null, clockMs())
    }

    fun replay(number: Short) {
        if (stopped) return
        val now = clockMs()
        val features = trackers[number]?.advance(now)
        route(number, RumbleSource.HOST, features?.input ?: ControllerRumbleState.ZERO, features, now)
    }

    private fun route(
        number: Short, source: RumbleSource, input: ControllerRumbleState,
        features: RumbleSignalFeatures?, now: Long
    ) {
        // Enforce body ownership here as well as at output, independent of the adapter's probe.
        val targets = context(number).let { if (number.toInt() == 0) it else it.copy(hasDevice = false) }
        renderer.render(number, source, GameRumbleAllocator.allocate(targets, input, features), now)
        if (source == RumbleSource.HOST && number.toInt() == 0) {
            val needsTick = targets.mode == GameRumbleMode.COORDINATED && targets.hasController &&
                targets.hasDevice && targets.deviceTier.supportsGradedOutput &&
                features?.decomposition?.hasUnsettledTransients == true
            if (needsTick && !tickScheduled) {
                tickScheduled = true
                postDelayed(ticker, 20L)
            } else if (!needsTick && tickScheduled) {
                removeCallbacks(ticker)
                tickScheduled = false
            }
        }
    }

    fun stop() {
        stopped = true
        removeCallbacks(ticker)
        tickScheduled = false
        trackers.clear()
    }
}
