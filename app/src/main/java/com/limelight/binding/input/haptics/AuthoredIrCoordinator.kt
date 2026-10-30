package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrFrame
import com.limelight.nvstream.Ds5HapticsIrExtendedFrame
import kotlin.math.sqrt

/** Authored-IR playback stage. Android supplies capabilities, lifecycle and a serial output queue.
 * SDK analysis stays on the host; ordinary rumble keeps its existing GameRumblePipeline.
 * Ingestion is bounded and may run on the receive thread; output always runs on the supplied queue.
 */
internal class AuthoredIrCoordinator(
    private val mixer: ControllerHapticsMixer,
    private val renderer: RumbleOutputRenderer,
    private val phone: DeviceVibrationCoordinator,
    private val context: (Short) -> GameRumbleContext,
    private val supportsTick: () -> Boolean,
    private val strengthPercent: () -> Int,
    private val clockMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val scheduleExpiry: () -> Unit,
    private val enabled: () -> Boolean = { true },
    private val onOutput: (Short, AuthoredIrTimeline.Output) -> Unit = { _, _ -> }
) {
    private data class Legacy(val controllerNumber: Short,
                              val output: AuthoredHapticsRenderer.Output, val tickAt: Long?)
    private val lock = Any()
    private val timelines = mutableMapOf<Short, AuthoredIrTimeline>()
    private val last = mutableMapOf<Short, AuthoredIrTimeline.Output>()
    private val pulses = mutableMapOf<Short, Pair<Float, Long>>()
    private val policies = mutableMapOf<Short, AuthoredIrPhonePolicy>()
    private val legacyRenderers = mutableMapOf<Short, AuthoredHapticsRenderer>()
    private val pendingLegacy = mutableMapOf<Short, Legacy>()
    private var queued = false
    private var stopped = false
    private var nativePhone = false
    private val drainTask = Runnable { drain() }

    val queuedEvents: Int get() = synchronized(lock) { timelines.values.sumOf { it.queuedEvents } }

    fun submit(frame: Ds5HapticsIrExtendedFrame) = synchronized(lock) {
        if (stopped || !enabled() || frame.controllerNumber.toInt() !in 0 until 16) return@synchronized
        if (!timelines.getOrPut(frame.controllerNumber, ::AuthoredIrTimeline)
                .accept(frame, clockMs() * 1000)) return@synchronized
        pendingLegacy.remove(frame.controllerNumber)
        wakeLocked()
    }

    fun submit(frame: Ds5HapticsIrFrame) = synchronized(lock) {
        if (stopped || !enabled() || frame.controllerNumber.toInt() !in 0 until 16) return@synchronized
        val output = legacyRenderers.getOrPut(frame.controllerNumber, ::AuthoredHapticsRenderer)
            .render(frame) ?: return@synchronized
        val tickAt = if (output.tick) clockMs() else pendingLegacy[frame.controllerNumber]?.tickAt
        pendingLegacy[frame.controllerNumber] = Legacy(frame.controllerNumber, output, tickAt)
        wakeLocked()
    }

    private fun wakeLocked() {
        removeCallbacks(drainTask)
        queued = true
        postDelayed(drainTask, 0)
    }

    private fun drain() {
        if (stopped || !enabled()) return
        val now = clockMs()
        val outputs: MutableList<Pair<Short, AuthoredIrTimeline.Output>>
        val legacy: List<Legacy>
        synchronized(lock) {
            queued = false
            outputs = timelines.mapNotNull { (number, timeline) ->
                timeline.poll(now * 1000)?.let { number to it }
            }.toMutableList()
            legacy = pendingLegacy.values.toList()
            pendingLegacy.clear()
        }
        pulses.toMap().forEach { (number, pulse) ->
            if (pulse.second <= now) {
                pulses.remove(number)
                if (outputs.none { it.first == number }) last[number]?.let {
                    outputs += number to it.copy(event = null, reset = false)
                }
            }
        }
        outputs.forEach { (number, output) ->
            last[number] = output.copy(event = null, reset = false)
            route(number, output, now)
        }
        legacy.forEach { routeLegacy(it, now) }
        synchronized(lock) {
            if (!queued && !stopped && enabled()) {
                val next = (timelines.values.mapNotNull { it.nextWakeUs()?.let { us -> (us + 999) / 1000 } } +
                    pulses.values.map { it.second }).minOrNull()
                if (next != null) {
                    queued = true
                    postDelayed(drainTask, (next - now).coerceAtLeast(1))
                }
            }
        }
    }

    private fun route(number: Short, output: AuthoredIrTimeline.Output, now: Long) {
        if (output.reset) {
            pulses.remove(number)
            policies.remove(number)
            mixer.clearSource(number, RumbleSource.AUTHORED_TRANSIENT, now)
        }
        if (number.toInt() == 0 && (output.reset || output.ended)) {
            renderer.flushDevice()
            if (output.reset) phone.resetAuthoredIr()
        }
        val targets = context(number).let { if (number.toInt() == 0) it else it.copy(hasDevice = false) }
        val pointOnly = targets.hasDevice && policies.getOrPut(number, ::AuthoredIrPhonePolicy)
            .useDetails(output.body, now, output.ended, targets.mode, targets.hasController)
        val nativeContinuous = phone.supportsNativeContinuous
        val native = targets.hasDevice && targets.mode != GameRumbleMode.CONTROLLER && phone.supportsNativeAuthored &&
            (nativeContinuous || pointOnly)
        if (number.toInt() == 0 && nativePhone != native) {
            phone.resetAuthoredIr()
            nativePhone = native
        }
        val eventPower = output.event?.value?.let { maxOf(it.leftStrength, it.rightStrength) } ?: 0f
        if (output.ended) pulses.remove(number)
        else if (eventPower > 0) pulses[number] = eventPower to (now + 60)
        val pulse = transientAmplitude(pulses[number]?.first ?: 0f)
        val details = native || pointOnly && supportsTick()
        val plan = AuthoredIrAllocator.allocate(targets, output.body, pulse, details)
        val body = if (targets.hasController && targets.mode != GameRumbleMode.DEVICE) output.body else ControllerRumbleState.ZERO
        mixer.submit(number, RumbleSource.AUTHORED_BODY, body, now, output.bodyValidUntilUs / 1000)
        val transient = if (!details && targets.mode != GameRumbleMode.DEVICE && targets.hasController)
            ControllerRumbleState(0f, pulse) else ControllerRumbleState.ZERO
        val mixed = mixer.submit(number, RumbleSource.AUTHORED_TRANSIENT, transient, now,
            pulses[number]?.second ?: now)
        if (targets.hasController) renderer.queueController(mixed)
        if (number.toInt() == 0) {
            if (native) {
                val nativeBody = if (!nativeContinuous ||
                    targets.mode == GameRumbleMode.COORDINATED && targets.hasController)
                    ControllerRumbleState.ZERO else output.body
                val gain = strengthPercent().coerceIn(0, 200) / 100f
                // Merge band energy into the carrier's peak amplitude; point strength is never
                // reduced to the fixed-strength TICK used by the public vibrator fallback.
                val bodyGain = sqrt(2f * (nativeBody.lowFrequency * nativeBody.lowFrequency +
                    nativeBody.highFrequency * nativeBody.highFrequency)) * gain
                phone.submitNativeAuthored(bodyGain,
                    // Profile 1 carries early band-peak evidence, not the finished PCM crest.
                    // Native playback needs its own makeup gain; otherwise rain collapses
                    // into the bank's weakest level. The backend limits physical sample gain.
                    transientAmplitude(eventPower) * 2f * gain,
                    output.bodyValidUntilUs / 1000, output.event?.expiresUs?.div(1000) ?: now,
                    active = !output.ended && gain > 0)
            } else {
                val deviceBody = plan.device ?: ControllerRumbleState.ZERO
                phone.submitAuthoredIrBody(deviceBody.lowFrequency.toMotorShort(), deviceBody.highFrequency.toMotorShort(),
                    strengthPercent(), !output.ended, output.bodyValidUntilUs / 1000)
                phone.submitAuthoredIrDetail(details && output.event != null, if (details) strengthPercent() else 0,
                    output.event?.expiresUs?.div(1000) ?: now, details && !output.ended)
            }
        }
        onOutput(number, output)
        scheduleExpiry()
    }

    // Match v2's perceptual makeup before 8-bit motor output is quantized.
    private fun transientAmplitude(strength: Float): Float =
        sqrt(ControllerRumbleState.normalizeAmplitude(strength))

    private fun routeLegacy(pending: Legacy, now: Long) {
        val number = pending.controllerNumber
        if (number.toInt() == 0 && nativePhone) { phone.resetAuthoredIr(); nativePhone = false }
        val targets = context(number).let { if (number.toInt() == 0) it else it.copy(hasDevice = false) }
        val details = pending.output.usesDeviceDetails(targets.mode, targets.hasController,
            targets.hasDevice, supportsTick())
        val state = if (details) pending.output.body else pending.output.rumble
        val plan = AuthoredIrAllocator.allocate(targets, state, 0f, details)
        val mixed = mixer.submit(number, RumbleSource.AUTHORED_BODY,
            plan.controller ?: ControllerRumbleState.ZERO, now, now + 200)
        if (targets.hasController) renderer.queueController(mixed)
        if (number.toInt() == 0) {
            if (details) phone.submitAuthoredDetail(pending.tickAt?.let { now - it <= 100 } == true,
                strengthPercent(), !pending.output.ended)
            else {
                phone.submitAuthoredDetail(false, 0, false)
                val device = plan.device ?: ControllerRumbleState.ZERO
                phone.submitAuthoredIrBody(device.lowFrequency.toMotorShort(), device.highFrequency.toMotorShort(),
                    strengthPercent(), !pending.output.ended, now + 200)
            }
        }
        scheduleExpiry()
    }

    /** Called on the serial output queue after the parent has retired the controller slot. */
    fun retire(number: Short) {
        synchronized(lock) { timelines.remove(number); pendingLegacy.remove(number); legacyRenderers.remove(number) }
        last.remove(number)
        pulses.remove(number)
        policies.remove(number)
        mixer.clearSource(number, RumbleSource.AUTHORED_BODY, clockMs())
        mixer.clearSource(number, RumbleSource.AUTHORED_TRANSIENT, clockMs())
        if (number.toInt() == 0) phone.resetAuthoredIr()
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            removeCallbacks(drainTask)
            timelines.clear(); pendingLegacy.clear(); legacyRenderers.clear()
        }
        last.clear(); pulses.clear(); policies.clear()
    }
}

/** Allocation of already-decomposed authored IR; ordinary rumble retains its own allocator. */
internal object AuthoredIrAllocator {
    fun allocate(context: GameRumbleContext, body: ControllerRumbleState, pulse: Float,
                 details: Boolean): GameRumbleRoute {
        val fallback = ControllerRumbleState(body.lowFrequency, maxOf(body.highFrequency, pulse))
        val controller = (if (details) body else fallback).takeIf {
            context.hasController && context.mode != GameRumbleMode.DEVICE
        }
        val device = if (!context.hasDevice || context.mode == GameRumbleMode.CONTROLLER || details) null
            else if (context.mode == GameRumbleMode.COORDINATED && context.hasController) {
                ControllerRumbleState(0f, pulse).takeIf { context.deviceTier.supportsGradedOutput }
            } else fallback
        return GameRumbleRoute(controller, device)
    }
}
