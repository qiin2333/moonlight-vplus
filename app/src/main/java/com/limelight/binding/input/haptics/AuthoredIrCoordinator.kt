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
    // A zero-delay drain is already posted; further frames before it runs need no repost.
    private var wakePosted = false
    private var stopped = false
    private var nativePhone = false
    // Bumped on every receive-thread phone release; outputs polled before a release carry
    // the then-current token and must not re-acquire the phone afterwards.
    private var phoneReleaseToken = 0L
    private val drainTask = Runnable { drain() }
    private val drainOutputs = ArrayList<Triple<Short, AuthoredIrTimeline.Output, Long>>()
    private val drainLegacy = ArrayList<Pair<Legacy, Long>>()
    private val expiredPulses = ArrayList<Short>()

    val queuedEvents: Int get() = synchronized(lock) { timelines.values.sumOf { it.queuedEvents } }

    fun submit(frame: Ds5HapticsIrExtendedFrame) = synchronized(lock) {
        if (stopped || !enabled() || frame.controllerNumber.toInt() !in 0 until 16) return@synchronized
        if (!timelines.getOrPut(frame.controllerNumber, ::AuthoredIrTimeline)
                .accept(frame, clockMs() * 1000)) return@synchronized
        if (frame.ended && frame.controllerNumber.toInt() == 0) {
            // Release the phone on the receive thread so the stop never queues behind the
            // main loop; the token invalidates outputs drain() already polled.
            phoneReleaseToken++
            phone.resetAuthoredIr()
        }
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
        if (wakePosted) return
        removeCallbacks(drainTask)
        queued = true
        wakePosted = true
        postDelayed(drainTask, 0)
    }

    private fun drain() {
        if (stopped || !enabled()) {
            synchronized(lock) { queued = false; wakePosted = false }
            return
        }
        val now = clockMs()
        val outputs = drainOutputs
        val legacy = drainLegacy
        outputs.clear()
        legacy.clear()
        var token = 0L
        synchronized(lock) {
            queued = false
            wakePosted = false
            token = phoneReleaseToken
            for ((number, timeline) in timelines) {
                timeline.poll(now * 1000)?.let { outputs += Triple(number, it, token) }
            }
            pendingLegacy.values.forEach { legacy += it to token }
            pendingLegacy.clear()
        }
        if (pulses.isNotEmpty()) {
            expiredPulses.clear()
            for ((number, pulse) in pulses) if (pulse.second <= now) expiredPulses += number
            for (number in expiredPulses) {
                pulses.remove(number)
                if (outputs.none { it.first == number }) last[number]?.let {
                    outputs += Triple(number, it.copy(event = null, reset = false), token)
                }
            }
        }
        for ((number, output, outputToken) in outputs) {
            last[number] = if (output.event == null && !output.reset) output
                else output.copy(event = null, reset = false)
            route(number, output, now, outputToken)
        }
        for ((pending, pendingToken) in legacy) routeLegacy(pending, now, pendingToken)
        outputs.clear()
        legacy.clear()
        synchronized(lock) {
            if (!queued && !stopped && enabled()) {
                var next = Long.MAX_VALUE
                for (timeline in timelines.values) {
                    val us = timeline.nextWakeUs()
                    if (us != null) next = minOf(next, (us + 999) / 1000)
                }
                for (pulse in pulses.values) next = minOf(next, pulse.second)
                if (next != Long.MAX_VALUE) {
                    queued = true
                    postDelayed(drainTask, (next - now).coerceAtLeast(1))
                }
            }
        }
    }

    private fun route(number: Short, output: AuthoredIrTimeline.Output, now: Long, token: Long) {
        if (output.reset) {
            pulses.remove(number)
            policies.remove(number)
            mixer.clearSource(number, RumbleSource.AUTHORED_TRANSIENT, now)
        }
        if (number.toInt() == 0 && (output.reset || output.ended)) {
            renderer.flushDevice()
            if (output.reset) phone.resetAuthoredIr()
        }
        val targets = targetsFor(number)
        val pointOnly = targets.hasDevice && policies.getOrPut(number, ::AuthoredIrPhonePolicy)
            .useDetails(output.body, now, output.ended, targets.mode, targets.hasController)
        val native = targets.hasDevice && targets.mode != GameRumbleMode.CONTROLLER && phone.supportsNativeAuthored
        val eventPower = output.event?.value?.let { maxOf(it.leftStrength, it.rightStrength) } ?: 0f
        if (output.ended) pulses.remove(number)
        else if (eventPower > 0) pulses[number] = eventPower to (now + 60)
        val pulse = motorTransientAmplitude(pulses[number]?.first ?: 0f)
        val details = native || pointOnly && supportsTick()
        val nativePlan = if (native) AuthoredIrAllocator.allocateNativeBody(targets, output.body) else null
        val body = if (nativePlan != null) nativePlan.controller ?: ControllerRumbleState.ZERO
            else if (targets.hasController && targets.mode != GameRumbleMode.DEVICE) output.body else ControllerRumbleState.ZERO
        mixer.submit(number, RumbleSource.AUTHORED_BODY, body, now, output.bodyValidUntilUs / 1000)
        val transient = if (!details && targets.mode != GameRumbleMode.DEVICE && targets.hasController)
            ControllerRumbleState(0f, pulse) else ControllerRumbleState.ZERO
        val mixed = mixer.submit(number, RumbleSource.AUTHORED_TRANSIENT, transient, now,
            pulses[number]?.second ?: now)
        if (targets.hasController) renderer.queueController(mixed)
        if (number.toInt() == 0) synchronized(lock) {
            // The token check and these submissions are atomic against the receive-thread
            // end release (same lock): a polled-before-reset output must not re-acquire the
            // phone afterwards, and a release landing mid-route stops whatever reacquired.
            if (token == phoneReleaseToken) {
                if (nativePhone != native) {
                    phone.resetAuthoredIr()
                    nativePhone = native
                }
                if (native) {
                    val nativeBody = nativePlan?.device ?: ControllerRumbleState.ZERO
                    val gain = strengthPercent().coerceIn(0, 200) / 100f
                    phone.submitNativeAuthored(NativeAuthoredBody.fromBands(
                        nativeBody.lowFrequency * gain, nativeBody.highFrequency * gain, output.frequencyHintHz),
                        // Profile 1 strength is a linear local peak, not power. Preserve its
                        // contrast: sqrt(strength) disproportionately boosted weak points,
                        // especially when coordinated mode removed the phone's background.
                        // Keep the old 100%-strength saturation point (2*sqrt(.09) == .6)
                        // and the native short waveform, without adding a floor or rate gate.
                        ControllerRumbleState.normalizeAmplitude(eventPower) / SOURCE_POINT_FULL_SCALE *
                            NATIVE_POINT_FULL_SCALE * gain,
                        output.bodyValidUntilUs / 1000, output.event?.expiresUs?.div(1000) ?: now,
                        active = !output.ended && gain > 0)
                } else {
                    val deviceBody = AuthoredIrAllocator.allocate(targets, output.body, pulse, details)
                        .device ?: ControllerRumbleState.ZERO
                    phone.submitAuthoredIrBody(deviceBody.lowFrequency.toMotorShort(), deviceBody.highFrequency.toMotorShort(),
                        strengthPercent(), !output.ended, output.bodyValidUntilUs / 1000)
                    phone.submitAuthoredIrDetail(details && output.event != null, if (details) strengthPercent() else 0,
                        output.event?.expiresUs?.div(1000) ?: now, details && !output.ended)
                }
            }
        }
        onOutput(number, output)
        scheduleExpiry()
    }

    // Compatibility curve for ordinary motor fallback, not native waveform amplitude.
    private fun motorTransientAmplitude(strength: Float): Float =
        sqrt(ControllerRumbleState.normalizeAmplitude(strength))

    private companion object {
        // Retain the existing native backend range; these are rendering units, not
        // calibrated DS5 force or a minimum perceptible event threshold.
        const val SOURCE_POINT_FULL_SCALE = .09f
        const val NATIVE_POINT_FULL_SCALE = .6f
    }

    private fun targetsFor(number: Short): GameRumbleContext = context(number).let {
        // A muted phone cannot accept the details removed from the controller signal.
        if (number.toInt() == 0 && strengthPercent() > 0) it else it.copy(hasDevice = false)
    }

    private fun routeLegacy(pending: Legacy, now: Long, token: Long) {
        val number = pending.controllerNumber
        if (number.toInt() == 0 && nativePhone) { phone.resetAuthoredIr(); nativePhone = false }
        val targets = targetsFor(number)
        val details = pending.output.usesDeviceDetails(targets.mode, targets.hasController,
            targets.hasDevice, supportsTick())
        val state = if (details) pending.output.body else pending.output.rumble
        val plan = AuthoredIrAllocator.allocate(targets, state, 0f, details)
        val mixed = mixer.submit(number, RumbleSource.AUTHORED_BODY,
            plan.controller ?: ControllerRumbleState.ZERO, now, now + 200)
        if (targets.hasController) renderer.queueController(mixed)
        if (number.toInt() == 0) {
            // Same release-token gate as route(): a legacy output polled before an
            // end-frame reset must not re-acquire the phone afterwards.
            synchronized(lock) {
                if (token == phoneReleaseToken) {
                    if (details) phone.submitAuthoredDetail(pending.tickAt?.let { now - it <= 100 } == true,
                        strengthPercent(), !pending.output.ended)
                    else {
                        phone.submitAuthoredDetail(false, 0, false)
                        val device = plan.device ?: ControllerRumbleState.ZERO
                        phone.submitAuthoredIrBody(device.lowFrequency.toMotorShort(), device.highFrequency.toMotorShort(),
                            strengthPercent(), !pending.output.ended, now + 200)
                    }
                }
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
            wakePosted = false
            removeCallbacks(drainTask)
            timelines.clear(); pendingLegacy.clear(); legacyRenderers.clear()
        }
        last.clear(); pulses.clear(); policies.clear()
    }
}

/** Allocation of already-decomposed authored IR; ordinary rumble retains its own allocator. */
internal object AuthoredIrAllocator {
    /** This route is the uncalibrated two-amplitude rumble API, not a waveform sink.
     * Native controller PCM playback is routed independently by AuthoredPcmOutputRouter;
     * ControllerHapticsCoordinator suppresses motor writes while that sink owns output.
     * No existing rumble route supplies a calibrated frequency/force transfer function.
     * A two-motor command cannot reproduce authored carrier frequency.
     * In native cooperation the phone owns the complete authored continuous signal.
     * Ordinary HOST rumble still reaches the controller through its independent mixer
     * source. Do not subtract a fictitious ERM energy contribution from the phone.
     */
    fun allocateNativeBody(context: GameRumbleContext, body: ControllerRumbleState): GameRumbleRoute {
        if (context.mode == GameRumbleMode.COORDINATED && context.hasController && context.hasDevice) {
            return GameRumbleRoute(
                controller = ControllerRumbleState.ZERO,
                device = body
            )
        }
        return GameRumbleRoute(
            controller = body.takeIf { context.hasController && context.mode != GameRumbleMode.DEVICE },
            device = body.takeIf { context.hasDevice && context.mode != GameRumbleMode.CONTROLLER }
        )
    }

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
