package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.LimeLog
import com.limelight.binding.input.driver.AbstractController
import com.limelight.binding.input.driver.ControllerDriverListener
import com.limelight.binding.input.driver.DualSenseAdaptiveTriggerEffect
import com.limelight.binding.input.driver.DualSenseInputSession
import com.limelight.binding.input.driver.DualSenseInputState
import com.limelight.binding.input.driver.wireless.hidp.HidpFailure
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge
import java.util.concurrent.ArrayBlockingQueue
import java.util.Locale

/** Standard controller lifecycle adapter for an application-owned DualSense HIDP session. */
internal class DualSenseWirelessController(
    deviceId: Int,
    listener: ControllerDriverListener,
    productId: Int = PRODUCT_DUALSENSE,
    private val openSession: (DualSenseHidpListener) -> Boolean,
    private val closeSession: () -> Boolean,
    sendOutputReport: (ByteArray) -> Boolean,
    private val onReady: () -> Unit = {},
    private val onLinkTerminated: () -> Unit = {}
) : AbstractController(deviceId, listener, SONY_VENDOR_ID, productId), DualSenseHidpListener {
    private val lifecycleLock = Any()
    private var started = false
    private var stopped = false
    private var announced = false
    private val inputQueue = ArrayBlockingQueue<DualSenseInputState>(1)
    private var inputThread: Thread? = null
    private val diagnostics = DualSenseWirelessDiagnostics()
    private val outputWriter = DualSenseBluetoothOutputWriter(
        sendOutputReport,
        onOutputEvent = { logDiagnostics(diagnostics.recordOutputEvent(it)) }
    )

    private val inputSession = DualSenseInputSession(
        isControllerReady = ::isControllerReady,
        reportBattery = ::notifyBatteryState,
        reportTouch = ::notifyControllerTouch
    )

    init {
        type = MoonBridge.LI_CTYPE_PS
        capabilities = (
            MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt() or
                MoonBridge.LI_CCAP_TOUCHPAD.toInt() or
                MoonBridge.LI_CCAP_ACCEL.toInt() or
                MoonBridge.LI_CCAP_GYRO.toInt() or
                MoonBridge.LI_CCAP_BATTERY_STATE.toInt() or
                MoonBridge.LI_CCAP_RUMBLE.toInt() or
                MoonBridge.LI_CCAP_TRIGGER_RUMBLE.toInt() or
                MoonBridge.LI_CCAP_RGB_LED.toInt() or
                MoonBridge.LI_CCAP_PREFER_DS5.toInt()
            ).toShort()
        supportedButtonFlags = ControllerPacket.A_FLAG or ControllerPacket.B_FLAG or
            ControllerPacket.X_FLAG or ControllerPacket.Y_FLAG or ControllerPacket.UP_FLAG or
            ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG or
            ControllerPacket.LB_FLAG or ControllerPacket.RB_FLAG or ControllerPacket.LS_CLK_FLAG or
            ControllerPacket.RS_CLK_FLAG or ControllerPacket.BACK_FLAG or ControllerPacket.PLAY_FLAG or
            ControllerPacket.SPECIAL_BUTTON_FLAG or ControllerPacket.TOUCHPAD_FLAG or
            ControllerPacket.MISC_FLAG
    }

    override fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (stopped) return false
            if (started) return true
            started = true
        }
        startInputDispatcher()
        val accepted = runCatching { openSession(this) }.getOrDefault(false)
        var stopDispatcher = false
        val result: Boolean
        synchronized(lifecycleLock) {
            if (!accepted) {
                started = false
                stopDispatcher = true
            }
            result = accepted && !stopped
        }
        if (stopDispatcher) stopInputDispatcher()
        return result
    }

    override fun stop() = stop(sendNeutral = true)

    internal fun stop(sendNeutral: Boolean) {
        val shouldClose: Boolean
        val shouldRemove: Boolean
        synchronized(lifecycleLock) {
            if (stopped) return
            stopped = true
            shouldClose = started
            started = false
            shouldRemove = announced
            announced = false
        }
        stopInputDispatcher()
        inputSession.releaseTouches()
        outputWriter.close(sendNeutral = shouldClose && sendNeutral)
        logDiagnostics(diagnostics.finish())
        if (shouldClose) runCatching { closeSession() }
        if (shouldRemove) notifyDeviceRemoved()
    }

    override fun onInput(
        state: DualSenseInputState,
        metadata: DualSenseBluetoothInputResult
    ) {
        var queueOverwritten = false
        synchronized(lifecycleLock) {
            if (stopped || !started) return
            if (!inputQueue.offer(state)) {
                // A report is a complete controller snapshot. If the network/input consumer
                // stalls, prefer the newest state instead of replaying stale button positions.
                inputQueue.poll()
                inputQueue.offer(state)
                queueOverwritten = true
            }
        }
        var snapshot = diagnostics.recordInput(metadata)
        if (queueOverwritten) {
            snapshot = diagnostics.recordQueueOverwrite() ?: snapshot
        }
        logDiagnostics(snapshot)
    }

    private fun processInput(state: DualSenseInputState) {
        val startedAtNs = System.nanoTime()
        synchronized(lifecycleLock) {
            if (stopped || !started) return
            if (!announced) {
                announced = true
                // Queue this before device announcement so any immediate host
                // RGB update follows the controller's required startup release.
                outputWriter.initializeLightbar()
                notifyDeviceAdded()
                runCatching(onReady)
            }
        }

        // Keep network/input delivery off the HCI reader and outside the lifecycle lock. stop()
        // marks the controller stopped, interrupts this worker, and joins it before removal.
        val normalized = inputSession.accept(state)
        buttonFlags = normalized.buttonFlags
        leftStickX = normalized.leftStickX
        leftStickY = normalized.leftStickY
        rightStickX = normalized.rightStickX
        rightStickY = normalized.rightStickY
        leftTrigger = normalized.leftTrigger
        rightTrigger = normalized.rightTrigger
        reportInput()
        notifyControllerMotion(
            MoonBridge.LI_MOTION_TYPE_GYRO,
            normalized.gyro.x,
            normalized.gyro.y,
            normalized.gyro.z
        )
        notifyControllerMotion(
            MoonBridge.LI_MOTION_TYPE_ACCEL,
            normalized.acceleration.x,
            normalized.acceleration.y,
            normalized.acceleration.z
        )
        logDiagnostics(
            diagnostics.recordDispatch((System.nanoTime() - startedAtNs) / 1_000_000L)
        )
    }

    private fun startInputDispatcher() {
        val thread = Thread(::inputDispatchLoop, "dualsense-wireless-input").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
        synchronized(lifecycleLock) {
            inputQueue.clear()
            inputThread = thread
        }
        thread.start()
    }

    private fun inputDispatchLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                var latest = inputQueue.take()
                while (true) latest = inputQueue.poll() ?: break
                processInput(latest)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Throwable) {
                LimeLog.warning("DualSense wireless input dispatch failed: ${failure.message}")
            }
        }
    }

    private fun stopInputDispatcher() {
        val thread = synchronized(lifecycleLock) {
            inputThread.also { inputThread = null }
        }
        inputQueue.clear()
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(INPUT_THREAD_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun onClosed() = detachFromLink()

    override fun onFailure(failure: HidpFailure) = detachFromLink()

    override fun onInputRejected(disposition: DualSenseBluetoothInputDisposition) {
        logDiagnostics(diagnostics.recordRejected(disposition))
    }

    override fun onInvalidHidpFrame(header: Int?) {
        logDiagnostics(diagnostics.recordInvalidHidpFrame())
    }

    override fun resetTouchState() = inputSession.resetTouchState()

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        outputWriter.updateRumble(lowFreqMotor, highFreqMotor)
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        val (leftType, leftPayload) = DualSenseAdaptiveTriggerEffect.triggerRumble(leftTrigger)
        val (rightType, rightPayload) = DualSenseAdaptiveTriggerEffect.triggerRumble(rightTrigger)
        outputWriter.updateAdaptiveTriggers(
            DualSenseAdaptiveTriggerEffect.BOTH_FLAGS.toByte(),
            leftType,
            rightType,
            leftPayload,
            rightPayload
        )
    }

    override val supportsAdaptiveTriggers: Boolean = true

    override fun setAdaptiveTriggers(
        eventFlags: Byte,
        typeLeft: Byte,
        typeRight: Byte,
        left: ByteArray,
        right: ByteArray
    ) {
        if (eventFlags.toInt() and DualSenseAdaptiveTriggerEffect.PLAYER_LED_FLAG != 0 &&
            left.isNotEmpty()
        ) {
            outputWriter.updatePlayerLeds(left[0].toInt() and 0x1F)
        }
        outputWriter.updateAdaptiveTriggers(eventFlags, typeLeft, typeRight, left, right)
    }

    override fun setControllerLED(r: Byte, g: Byte, b: Byte) {
        outputWriter.updateLightbar(r, g, b)
    }

    private fun detachFromLink() {
        val shouldRemove: Boolean
        synchronized(lifecycleLock) {
            if (stopped) return
            stopped = true
            started = false
            shouldRemove = announced
            announced = false
        }
        stopInputDispatcher()
        inputSession.releaseTouches()
        outputWriter.close(sendNeutral = false)
        logDiagnostics(diagnostics.finish())
        if (shouldRemove) notifyDeviceRemoved()
        runCatching(onLinkTerminated)
    }

    private fun logDiagnostics(snapshot: DualSenseWirelessDiagnosticSnapshot?) {
        if (snapshot == null) return
        val rejected = snapshot.rejectedInputs.entries.joinToString(",") {
            "${it.key.name}=${it.value}"
        }.ifEmpty { "none" }
        LimeLog.info(
            String.format(
                Locale.US,
                "DualSense wireless diagnostics: window=%dms input=%d rate=%.1fHz " +
                    "dispatched=%d missing=%d resync=%d queue_overwrite=%d " +
                    "max_gap=%dms max_dispatch=%dms rejected=%s invalid_hidp=%d " +
                    "output_submitted=%d output_sent=%d output_coalesced=%d output_failed=%d",
                snapshot.windowMs,
                snapshot.acceptedInputs,
                snapshot.inputRateHz,
                snapshot.dispatchedInputs,
                snapshot.missingReports,
                snapshot.resynchronizedInputs,
                snapshot.queueOverwrites,
                snapshot.maxInputGapMs,
                snapshot.maxDispatchMs,
                rejected,
                snapshot.invalidHidpFrames,
                snapshot.outputSubmitted,
                snapshot.outputSent,
                snapshot.outputCoalesced,
                snapshot.outputFailures
            )
        )
    }

    companion object {
        private const val SONY_VENDOR_ID = 0x054C
        const val PRODUCT_DUALSENSE = 0x0CE6
        const val PRODUCT_DUALSENSE_EDGE = 0x0DF2
        private const val INPUT_THREAD_JOIN_TIMEOUT_MS = 1_000L
    }
}
