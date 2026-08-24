package com.limelight.binding.input.driver.wireless.dualsense

import java.util.EnumMap

internal data class DualSenseWirelessDiagnosticSnapshot(
    val windowMs: Long,
    val acceptedInputs: Int,
    val dispatchedInputs: Int,
    val missingReports: Int,
    val resynchronizedInputs: Int,
    val queueOverwrites: Int,
    val maxInputGapMs: Long,
    val maxDispatchMs: Long,
    val rejectedInputs: Map<DualSenseBluetoothInputDisposition, Int>,
    val invalidHidpFrames: Int,
    val outputSubmitted: Int,
    val outputSent: Int,
    val outputCoalesced: Int,
    val outputFailures: Int
) {
    val inputRateHz: Double
        get() = if (windowMs > 0) acceptedInputs * 1_000.0 / windowMs else 0.0
}

/** Low-frequency evidence for physical adapter validation; never logs per input packet. */
internal class DualSenseWirelessDiagnostics(
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val reportIntervalMs: Long = DEFAULT_REPORT_INTERVAL_MS
) {
    private var windowStartedAtMs = monotonicTimeMs()
    private var lastAcceptedAtMs: Long? = null
    private var acceptedInputs = 0
    private var dispatchedInputs = 0
    private var missingReports = 0
    private var resynchronizedInputs = 0
    private var queueOverwrites = 0
    private var maxInputGapMs = 0L
    private var maxDispatchMs = 0L
    private val rejectedInputs = EnumMap<DualSenseBluetoothInputDisposition, Int>(
        DualSenseBluetoothInputDisposition::class.java
    )
    private var invalidHidpFrames = 0
    private var outputSubmitted = 0
    private var outputSent = 0
    private var outputCoalesced = 0
    private var outputFailures = 0

    init {
        require(reportIntervalMs > 0)
    }

    @Synchronized
    fun recordInput(metadata: DualSenseBluetoothInputResult): DualSenseWirelessDiagnosticSnapshot? {
        val now = monotonicTimeMs()
        lastAcceptedAtMs?.let { previous ->
            maxInputGapMs = maxOf(maxInputGapMs, (now - previous).coerceAtLeast(0L))
        }
        lastAcceptedAtMs = now
        acceptedInputs++
        missingReports += metadata.missingReports
        if (metadata.disposition == DualSenseBluetoothInputDisposition.RESYNCHRONIZED) {
            resynchronizedInputs++
        }
        return snapshotIfDue(now)
    }

    @Synchronized
    fun recordDispatch(durationMs: Long): DualSenseWirelessDiagnosticSnapshot? {
        dispatchedInputs++
        maxDispatchMs = maxOf(maxDispatchMs, durationMs.coerceAtLeast(0L))
        return snapshotIfDue(monotonicTimeMs())
    }

    @Synchronized
    fun recordQueueOverwrite(): DualSenseWirelessDiagnosticSnapshot? {
        queueOverwrites++
        return snapshotIfDue(monotonicTimeMs())
    }

    @Synchronized
    fun recordRejected(
        disposition: DualSenseBluetoothInputDisposition
    ): DualSenseWirelessDiagnosticSnapshot? {
        rejectedInputs[disposition] = (rejectedInputs[disposition] ?: 0) + 1
        return snapshotIfDue(monotonicTimeMs())
    }

    @Synchronized
    fun recordInvalidHidpFrame(): DualSenseWirelessDiagnosticSnapshot? {
        invalidHidpFrames++
        return snapshotIfDue(monotonicTimeMs())
    }

    @Synchronized
    fun recordOutputEvent(event: DualSenseBluetoothOutputEvent): DualSenseWirelessDiagnosticSnapshot? {
        when (event) {
            DualSenseBluetoothOutputEvent.SUBMITTED -> outputSubmitted++
            DualSenseBluetoothOutputEvent.SENT -> outputSent++
            DualSenseBluetoothOutputEvent.COALESCED -> outputCoalesced++
            DualSenseBluetoothOutputEvent.FAILED -> outputFailures++
        }
        return snapshotIfDue(monotonicTimeMs())
    }

    @Synchronized
    fun finish(): DualSenseWirelessDiagnosticSnapshot? = snapshot(monotonicTimeMs(), force = true)

    private fun snapshotIfDue(now: Long): DualSenseWirelessDiagnosticSnapshot? =
        snapshot(now, force = false)

    private fun snapshot(now: Long, force: Boolean): DualSenseWirelessDiagnosticSnapshot? {
        val elapsed = (now - windowStartedAtMs).coerceAtLeast(0L)
        if (!force && elapsed < reportIntervalMs) return null
        if (!hasObservations()) {
            windowStartedAtMs = now
            return null
        }
        val result = DualSenseWirelessDiagnosticSnapshot(
            windowMs = elapsed,
            acceptedInputs = acceptedInputs,
            dispatchedInputs = dispatchedInputs,
            missingReports = missingReports,
            resynchronizedInputs = resynchronizedInputs,
            queueOverwrites = queueOverwrites,
            maxInputGapMs = maxInputGapMs,
            maxDispatchMs = maxDispatchMs,
            rejectedInputs = rejectedInputs.toMap(),
            invalidHidpFrames = invalidHidpFrames,
            outputSubmitted = outputSubmitted,
            outputSent = outputSent,
            outputCoalesced = outputCoalesced,
            outputFailures = outputFailures
        )
        windowStartedAtMs = now
        acceptedInputs = 0
        dispatchedInputs = 0
        missingReports = 0
        resynchronizedInputs = 0
        queueOverwrites = 0
        maxInputGapMs = 0L
        maxDispatchMs = 0L
        rejectedInputs.clear()
        invalidHidpFrames = 0
        outputSubmitted = 0
        outputSent = 0
        outputCoalesced = 0
        outputFailures = 0
        return result
    }

    private fun hasObservations(): Boolean = acceptedInputs != 0 || dispatchedInputs != 0 ||
        missingReports != 0 || resynchronizedInputs != 0 || queueOverwrites != 0 ||
        rejectedInputs.isNotEmpty() || invalidHidpFrames != 0 || outputSubmitted != 0 ||
        outputSent != 0 || outputCoalesced != 0 || outputFailures != 0

    companion object {
        private const val DEFAULT_REPORT_INTERVAL_MS = 5_000L
    }
}
