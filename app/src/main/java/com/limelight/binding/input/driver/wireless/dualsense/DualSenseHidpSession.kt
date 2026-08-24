package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.binding.input.driver.DualSenseInputState
import com.limelight.binding.input.driver.wireless.hidp.HidpFailure
import com.limelight.binding.input.driver.wireless.hidp.HidpListener
import com.limelight.binding.input.driver.wireless.hidp.HidpSession
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidListener

internal enum class DualSenseHidpState {
    WAITING_FOR_REPORT_PROTOCOL,
    WAITING_FOR_VALID_INPUT,
    READY,
    CLOSING,
    CLOSED,
    FAILED
}

internal interface DualSenseHidpListener {
    fun onReportProtocolReady() = Unit
    fun onInput(state: DualSenseInputState, metadata: DualSenseBluetoothInputResult)
    fun onInputRejected(disposition: DualSenseBluetoothInputDisposition) = Unit
    fun onInvalidHidpFrame(header: Int?) = Unit
    fun onVirtualCableUnplug() = Unit
    fun onClosed()
    fun onFailure(failure: HidpFailure)
}

/** Binds the generic HIDP transaction layer to the DualSense Bluetooth report codec. */
internal class DualSenseHidpSession(
    sendControl: (ByteArray) -> Boolean,
    sendInterrupt: (ByteArray) -> Boolean,
    private val listener: DualSenseHidpListener,
    monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    protocolTimeoutMs: Long = DEFAULT_PROTOCOL_TIMEOUT_MS,
    sequenceResyncTimeoutMs: Long = DEFAULT_SEQUENCE_RESYNC_TIMEOUT_MS
) : HidpListener {
    @Volatile
    var state = DualSenseHidpState.WAITING_FOR_REPORT_PROTOCOL
        private set

    private val inputCodec = DualSenseBluetoothInputCodec(
        monotonicTimeMs,
        sequenceResyncTimeoutMs
    )
    private val hidpSession = HidpSession(
        sendControl,
        sendInterrupt,
        this,
        monotonicTimeMs,
        protocolTimeoutMs
    )

    val l2capListener: L2capHidListener
        get() = hidpSession

    override fun onReportProtocolReady() {
        if (state != DualSenseHidpState.WAITING_FOR_REPORT_PROTOCOL) return
        // A Bluetooth DualSense starts in the compact 0x01 input mode. Reading
        // calibration Feature Report 0x05 switches it to the full CRC-protected
        // 0x31 stream containing touch, motion and battery state.
        if (!hidpSession.requestFeatureReport(FULL_INPUT_ENABLE_FEATURE_REPORT)) return
        state = DualSenseHidpState.WAITING_FOR_VALID_INPUT
        runCatching { listener.onReportProtocolReady() }
    }

    override fun onInputReport(report: ByteArray) {
        if (state != DualSenseHidpState.WAITING_FOR_VALID_INPUT &&
            state != DualSenseHidpState.READY
        ) {
            return
        }
        val result = inputCodec.decode(report)
        val input = result.state
        if (input == null) {
            runCatching { listener.onInputRejected(result.disposition) }
            return
        }
        if (state == DualSenseHidpState.WAITING_FOR_VALID_INPUT) {
            state = DualSenseHidpState.READY
        }
        runCatching { listener.onInput(input, result) }
    }

    override fun onInvalidFrame(header: Int?) {
        runCatching { listener.onInvalidHidpFrame(header) }
    }

    override fun onVirtualCableUnplug() {
        if (state.isTerminal()) return
        state = DualSenseHidpState.CLOSING
        inputCodec.reset()
        runCatching { listener.onVirtualCableUnplug() }
    }

    override fun onClosed() {
        if (state == DualSenseHidpState.CLOSED) return
        if (state != DualSenseHidpState.FAILED) {
            state = DualSenseHidpState.CLOSED
            inputCodec.reset()
            runCatching { listener.onClosed() }
        }
    }

    override fun onFailure(failure: HidpFailure) {
        if (state.isTerminal()) return
        state = DualSenseHidpState.FAILED
        inputCodec.reset()
        runCatching { listener.onFailure(failure) }
    }

    fun sendOutputReport(report: ByteArray): Boolean = hidpSession.sendOutputReport(report)

    fun beginClose(): Boolean {
        if (state == DualSenseHidpState.CLOSED) return true
        if (state == DualSenseHidpState.FAILED) return false
        state = DualSenseHidpState.CLOSING
        inputCodec.reset()
        return hidpSession.beginClose()
    }

    fun checkTimeout(nowMs: Long = System.nanoTime() / 1_000_000L): Boolean =
        hidpSession.checkTimeout(nowMs)

    private fun DualSenseHidpState.isTerminal(): Boolean =
        this == DualSenseHidpState.CLOSED || this == DualSenseHidpState.FAILED

    companion object {
        private const val FULL_INPUT_ENABLE_FEATURE_REPORT = 0x05
        private const val DEFAULT_PROTOCOL_TIMEOUT_MS = 3000L
        private const val DEFAULT_SEQUENCE_RESYNC_TIMEOUT_MS = 500L
    }
}
