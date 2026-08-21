package com.limelight.binding.input.driver.wireless.dualsense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualSenseWirelessDiagnosticsTest {
    @Test
    fun aggregatesAWindowWithoutPerPacketOutput() {
        var nowMs = 0L
        val diagnostics = DualSenseWirelessDiagnostics(
            monotonicTimeMs = { nowMs },
            reportIntervalMs = 1_000L
        )
        assertNull(diagnostics.recordInput(accepted()))
        nowMs = 4L
        assertNull(diagnostics.recordInput(accepted(missing = 2)))
        diagnostics.recordDispatch(3L)
        diagnostics.recordQueueOverwrite()
        diagnostics.recordRejected(DualSenseBluetoothInputDisposition.INVALID_CRC)
        diagnostics.recordInvalidHidpFrame()
        diagnostics.recordOutputEvent(DualSenseBluetoothOutputEvent.SUBMITTED)
        diagnostics.recordOutputEvent(DualSenseBluetoothOutputEvent.SENT)
        diagnostics.recordOutputEvent(DualSenseBluetoothOutputEvent.COALESCED)
        diagnostics.recordOutputEvent(DualSenseBluetoothOutputEvent.FAILED)

        nowMs = 1_000L
        val snapshot = diagnostics.recordInput(
            accepted(DualSenseBluetoothInputDisposition.RESYNCHRONIZED)
        )!!
        assertEquals(3, snapshot.acceptedInputs)
        assertEquals(1, snapshot.dispatchedInputs)
        assertEquals(2, snapshot.missingReports)
        assertEquals(1, snapshot.resynchronizedInputs)
        assertEquals(1, snapshot.queueOverwrites)
        assertEquals(996L, snapshot.maxInputGapMs)
        assertEquals(3L, snapshot.maxDispatchMs)
        assertEquals(1, snapshot.rejectedInputs[DualSenseBluetoothInputDisposition.INVALID_CRC])
        assertEquals(1, snapshot.invalidHidpFrames)
        assertEquals(1, snapshot.outputSubmitted)
        assertEquals(1, snapshot.outputSent)
        assertEquals(1, snapshot.outputCoalesced)
        assertEquals(1, snapshot.outputFailures)
        assertTrue(snapshot.inputRateHz in 2.9..3.1)
    }

    @Test
    fun finishReturnsOnlyObservedPartialWindows() {
        var nowMs = 10L
        val diagnostics = DualSenseWirelessDiagnostics({ nowMs }, 1_000L)
        assertNull(diagnostics.finish())
        diagnostics.recordRejected(DualSenseBluetoothInputDisposition.INVALID_LENGTH)
        nowMs = 25L
        val snapshot = diagnostics.finish()!!
        assertEquals(15L, snapshot.windowMs)
        assertEquals(1, snapshot.rejectedInputs[DualSenseBluetoothInputDisposition.INVALID_LENGTH])
        assertNull(diagnostics.finish())
    }

    private fun accepted(
        disposition: DualSenseBluetoothInputDisposition = DualSenseBluetoothInputDisposition.ACCEPTED,
        missing: Int = 0
    ) = DualSenseBluetoothInputResult(
        disposition = disposition,
        state = null,
        missingReports = missing
    )
}
