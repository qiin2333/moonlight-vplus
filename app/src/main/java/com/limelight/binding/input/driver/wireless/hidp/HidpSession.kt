package com.limelight.binding.input.driver.wireless.hidp

import com.limelight.binding.input.driver.wireless.l2cap.L2capHidChannels
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidFailure
import com.limelight.binding.input.driver.wireless.l2cap.L2capHidListener

internal enum class HidpState {
    IDLE,
    WAITING_FOR_REPORT_PROTOCOL,
    REPORT_PROTOCOL,
    CLOSING,
    CLOSED,
    FAILED
}

internal enum class HidpErrorCode {
    SEND_FAILED,
    MALFORMED_CONTROL_FRAME,
    REPORT_PROTOCOL_REJECTED,
    REPORT_PROTOCOL_TIMEOUT,
    L2CAP_FAILED
}

internal data class HidpFailure(
    val code: HidpErrorCode,
    val handshakeResult: Int? = null,
    val l2capFailure: L2capHidFailure? = null
)

internal interface HidpListener {
    fun onReportProtocolReady()
    fun onInputReport(report: ByteArray)
    fun onFeatureReport(report: ByteArray) = Unit
    fun onInvalidFrame(header: Int?) = Unit
    fun onVirtualCableUnplug() = Unit
    fun onClosed()
    fun onFailure(failure: HidpFailure)
}

/** Minimal HIDP host session over an already established Control/Interrupt channel pair. */
internal class HidpSession(
    private val sendControl: (ByteArray) -> Boolean,
    private val sendInterrupt: (ByteArray) -> Boolean,
    private val listener: HidpListener,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val protocolTimeoutMs: Long = DEFAULT_PROTOCOL_TIMEOUT_MS
) : L2capHidListener {
    @Volatile
    var state = HidpState.IDLE
        private set

    @Volatile
    var failure: HidpFailure? = null
        private set

    private var protocolDeadlineMs = 0L

    init {
        require(protocolTimeoutMs > 0)
    }

    @Synchronized
    override fun onChannelsOpen(channels: L2capHidChannels) {
        if (state != HidpState.IDLE) return
        state = HidpState.WAITING_FOR_REPORT_PROTOCOL
        protocolDeadlineMs = monotonicTimeMs() + protocolTimeoutMs
        if (!safeSendControl(byteArrayOf(SET_PROTOCOL_REPORT))) {
            fail(HidpFailure(HidpErrorCode.SEND_FAILED))
        }
    }

    @Synchronized
    override fun onControlData(payload: ByteArray) {
        if (state == HidpState.CLOSED || state == HidpState.FAILED) return
        if (payload.isEmpty()) {
            rejectFrame(null)
            return
        }

        val header = payload[0].toInt() and 0xFF
        val transaction = header and TRANSACTION_MASK
        val parameter = header and PARAMETER_MASK
        when (transaction) {
            TRANSACTION_HANDSHAKE -> handleHandshake(payload, parameter)
            TRANSACTION_HID_CONTROL -> handleHidControl(payload, parameter)
            TRANSACTION_DATA -> handleData(payload, parameter)
            else -> {
                rejectFrame(header)
                safeSendControl(byteArrayOf(HANDSHAKE_UNSUPPORTED_REQUEST))
            }
        }
    }

    @Synchronized
    override fun onInterruptData(payload: ByteArray) {
        if (state != HidpState.REPORT_PROTOCOL) return
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != DATA_INPUT) {
            rejectFrame(payload.firstOrNull()?.toInt()?.and(0xFF))
            return
        }
        runCatching { listener.onInputReport(payload.copyOfRange(1, payload.size)) }
    }

    @Synchronized
    override fun onChannelsClosed() {
        if (state == HidpState.CLOSED) return
        if (state != HidpState.FAILED) {
            state = HidpState.CLOSED
            runCatching { listener.onClosed() }
        }
    }

    @Synchronized
    override fun onL2capFailure(failure: L2capHidFailure) {
        fail(HidpFailure(HidpErrorCode.L2CAP_FAILED, l2capFailure = failure))
    }

    @Synchronized
    fun sendOutputReport(report: ByteArray): Boolean {
        if (state != HidpState.REPORT_PROTOCOL || report.isEmpty()) return false
        return runCatching { sendInterrupt(byteArrayOf(DATA_OUTPUT) + report) }
            .getOrDefault(false)
    }

    /**
     * Requests a numbered HID feature report on the Control channel.
     * The response is delivered as DATA|FEATURE to [HidpListener.onFeatureReport].
     */
    @Synchronized
    fun requestFeatureReport(reportId: Int): Boolean {
        if (state != HidpState.REPORT_PROTOCOL || reportId !in 0..0xFF) return false
        if (safeSendControl(byteArrayOf(GET_REPORT_FEATURE, reportId.toByte()))) return true
        fail(HidpFailure(HidpErrorCode.SEND_FAILED))
        return false
    }

    @Synchronized
    fun beginClose(): Boolean {
        return when (state) {
            HidpState.CLOSED -> true
            HidpState.FAILED -> false
            HidpState.CLOSING -> true
            else -> {
                state = HidpState.CLOSING
                true
            }
        }
    }

    @Synchronized
    fun checkTimeout(nowMs: Long = monotonicTimeMs()): Boolean {
        if (state != HidpState.WAITING_FOR_REPORT_PROTOCOL || nowMs < protocolDeadlineMs) {
            return false
        }
        fail(HidpFailure(HidpErrorCode.REPORT_PROTOCOL_TIMEOUT))
        return true
    }

    private fun handleHandshake(payload: ByteArray, result: Int) {
        if (payload.size != 1) {
            fail(HidpFailure(HidpErrorCode.MALFORMED_CONTROL_FRAME))
            return
        }
        if (state != HidpState.WAITING_FOR_REPORT_PROTOCOL) {
            rejectFrame(payload[0].toInt() and 0xFF)
            return
        }
        if (result != HANDSHAKE_SUCCESS) {
            fail(HidpFailure(HidpErrorCode.REPORT_PROTOCOL_REJECTED, result))
            return
        }
        state = HidpState.REPORT_PROTOCOL
        protocolDeadlineMs = 0L
        runCatching { listener.onReportProtocolReady() }
    }

    private fun handleHidControl(payload: ByteArray, operation: Int) {
        if (payload.size != 1) {
            fail(HidpFailure(HidpErrorCode.MALFORMED_CONTROL_FRAME))
            return
        }
        if (operation == CONTROL_VIRTUAL_CABLE_UNPLUG) {
            state = HidpState.CLOSING
            runCatching { listener.onVirtualCableUnplug() }
        } else {
            rejectFrame(payload[0].toInt() and 0xFF)
            safeSendControl(byteArrayOf(HANDSHAKE_UNSUPPORTED_REQUEST))
        }
    }

    private fun handleData(payload: ByteArray, reportType: Int) {
        if (state != HidpState.REPORT_PROTOCOL || payload.size < 2) {
            rejectFrame(payload.firstOrNull()?.toInt()?.and(0xFF))
            return
        }
        val report = payload.copyOfRange(1, payload.size)
        when (reportType) {
            REPORT_TYPE_INPUT -> runCatching { listener.onInputReport(report) }
            REPORT_TYPE_FEATURE -> runCatching { listener.onFeatureReport(report) }
            else -> rejectFrame(payload[0].toInt() and 0xFF)
        }
    }

    private fun safeSendControl(payload: ByteArray): Boolean =
        runCatching { sendControl(payload) }.getOrDefault(false)

    private fun rejectFrame(header: Int?) {
        runCatching { listener.onInvalidFrame(header) }
    }

    private fun fail(value: HidpFailure) {
        if (state == HidpState.CLOSED || state == HidpState.FAILED) return
        state = HidpState.FAILED
        failure = value
        protocolDeadlineMs = 0L
        runCatching { listener.onFailure(value) }
    }

    companion object {
        private const val TRANSACTION_MASK = 0xF0
        private const val PARAMETER_MASK = 0x0F
        private const val TRANSACTION_HANDSHAKE = 0x00
        private const val TRANSACTION_HID_CONTROL = 0x10
        private const val TRANSACTION_DATA = 0xA0
        private const val REPORT_TYPE_INPUT = 0x01
        private const val REPORT_TYPE_OUTPUT = 0x02
        private const val REPORT_TYPE_FEATURE = 0x03
        private const val HANDSHAKE_SUCCESS = 0x00
        private const val HANDSHAKE_UNSUPPORTED_REQUEST: Byte = 0x03
        private const val CONTROL_VIRTUAL_CABLE_UNPLUG = 0x05
        private const val SET_PROTOCOL_REPORT: Byte = 0x71
        private const val GET_REPORT_FEATURE: Byte = 0x43
        private const val DATA_INPUT = TRANSACTION_DATA or REPORT_TYPE_INPUT
        private const val DATA_OUTPUT: Byte = (TRANSACTION_DATA or REPORT_TYPE_OUTPUT).toByte()
        private const val DEFAULT_PROTOCOL_TIMEOUT_MS = 3000L
    }
}
