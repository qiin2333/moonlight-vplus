package com.limelight.binding.input.haptics

/**
 * Serial output stage shared by the Android adapter and deterministic pipeline tests.
 * Owns source mixing, pacing and the single-motor fold; it does not classify the signal.
 */
internal class RumbleOutputRenderer(
    private val controllerMixer: ControllerHapticsMixer,
    private val deviceMixer: ControllerHapticsMixer,
    private val clockMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val controllerAvailable: (Short) -> Boolean,
    private val controllerIntervalMs: (Short) -> Long,
    private val writeController: (MixedRumbleState) -> Unit,
    private val writeDeviceAmplitude: (Int) -> Unit,
    private val enabled: () -> Boolean = { true }
) {
    private var stopped = false
    private val slots = mutableMapOf<Short, RumbleOutputSlot<MixedRumbleState>>()
    private val deviceSlot = RumbleOutputSlot<MixedRumbleState>(
        pacingMs = { 33L }, edgeFloorMs = 10L, isZero = { it.isZero },
        postDelayed = postDelayed, removeCallbacks = removeCallbacks, clockMs = clockMs,
        dispatch = {
            if (active()) writeDeviceAmplitude(SingleMotorRumbleFold.amplitude(
                it.output.lowFrequency, it.output.highFrequency
            ))
        }
    )

    fun render(number: Short, source: RumbleSource, plan: GameRumbleRoute, nowMs: Long) {
        if (!active()) return
        val controller = plan.controller?.let { controllerMixer.submit(number, source, it, nowMs) }
            ?: controllerMixer.clearSource(number, source, nowMs)
        if (controllerAvailable(number)) queueController(controller)
        // The body is physically shared. Another player must never clear player one's output.
        if (number.toInt() == 0) {
            val device = plan.device?.let { deviceMixer.submit(number, source, it, nowMs) }
                ?: deviceMixer.clearSource(number, source, nowMs)
            queueDevice(device)
        }
    }

    fun queueController(mixed: MixedRumbleState) {
        if (!active()) return
        slots.getOrPut(mixed.controllerNumber) {
            RumbleOutputSlot(
                pacingMs = { controllerIntervalMs(mixed.controllerNumber) },
                edgeFloorMs = 10L, isZero = { it.isZero },
                postDelayed = postDelayed, removeCallbacks = removeCallbacks, clockMs = clockMs,
                dispatch = {
                    if (active() && controllerAvailable(it.controllerNumber)) writeController(it)
                }
            )
        }.submit(mixed)
    }

    fun queueDevice(mixed: MixedRumbleState) {
        if (active() && mixed.controllerNumber.toInt() == 0) deviceSlot.submit(mixed)
    }

    /** Forget dedupe history when the physical controller sink changes. */
    fun resetController(number: Short) { slots.remove(number)?.cancel() }

    /** Cancel logical output; the adapter sends terminal zero and closes its physical sinks. */
    fun stop(): Set<Short> {
        stopped = true
        slots.values.forEach { it.cancel() }
        deviceSlot.cancel()
        val numbers = slots.keys.toMutableSet()
        numbers.addAll(controllerMixer.clearAll().map { it.controllerNumber })
        numbers.addAll(deviceMixer.clearAll().map { it.controllerNumber })
        slots.clear()
        return numbers
    }

    private fun active() = !stopped && enabled()
}
