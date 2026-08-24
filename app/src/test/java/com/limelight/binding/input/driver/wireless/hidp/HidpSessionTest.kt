package com.limelight.binding.input.driver.wireless.hidp

import com.limelight.binding.input.driver.wireless.l2cap.L2capHidChannels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidpSessionTest {
    @Test
    fun negotiatesReportProtocolAndRoutesInputAndOutput() {
        val harness = Harness()
        harness.session.onChannelsOpen(CHANNELS)
        assertArrayEquals(byteArrayOf(0x71), harness.control.single())
        assertEquals(HidpState.WAITING_FOR_REPORT_PROTOCOL, harness.session.state)

        harness.session.onControlData(byteArrayOf(0x00))
        assertTrue(harness.ready)
        assertEquals(HidpState.REPORT_PROTOCOL, harness.session.state)

        assertTrue(harness.session.requestFeatureReport(0x05))
        assertArrayEquals(byteArrayOf(0x43, 0x05), harness.control.last())
        harness.session.onControlData(byteArrayOf(0xA3.toByte(), 0x05, 0x22))
        assertArrayEquals(byteArrayOf(0x05, 0x22), harness.feature)

        harness.session.onInterruptData(byteArrayOf(0xA1.toByte(), 0x31, 0x22))
        assertArrayEquals(byteArrayOf(0x31, 0x22), harness.input)

        assertTrue(harness.session.sendOutputReport(byteArrayOf(0x31, 0x44)))
        assertArrayEquals(
            byteArrayOf(0xA2.toByte(), 0x31, 0x44),
            harness.interrupt.single()
        )
    }

    @Test
    fun rejectsFailedHandshakeAndTimesOut() {
        val rejected = Harness()
        rejected.session.onChannelsOpen(CHANNELS)
        rejected.session.onControlData(byteArrayOf(0x03))
        assertEquals(HidpState.FAILED, rejected.session.state)
        assertEquals(HidpErrorCode.REPORT_PROTOCOL_REJECTED, rejected.failure!!.code)
        assertEquals(3, rejected.failure!!.handshakeResult)

        var nowMs = 0L
        val timeout = Harness(monotonicTimeMs = { nowMs }, protocolTimeoutMs = 100L)
        timeout.session.onChannelsOpen(CHANNELS)
        nowMs = 100L
        assertTrue(timeout.session.checkTimeout())
        assertEquals(HidpErrorCode.REPORT_PROTOCOL_TIMEOUT, timeout.failure!!.code)
    }

    @Test
    fun ignoresInvalidInterruptFramesAndHandlesVirtualCableUnplug() {
        val harness = Harness()
        harness.session.onChannelsOpen(CHANNELS)
        harness.session.onControlData(byteArrayOf(0x00))

        harness.session.onInterruptData(byteArrayOf(0xA2.toByte(), 0x31))
        assertEquals(0xA2, harness.invalidHeader)
        assertEquals(HidpState.REPORT_PROTOCOL, harness.session.state)
        assertFalse(harness.session.sendOutputReport(ByteArray(0)))

        harness.session.onControlData(byteArrayOf(0x15))
        assertTrue(harness.virtualCableUnplug)
        assertEquals(HidpState.CLOSING, harness.session.state)
    }

    private class Harness(
        monotonicTimeMs: () -> Long = { 0L },
        protocolTimeoutMs: Long = 3000L
    ) : HidpListener {
        val control = mutableListOf<ByteArray>()
        val interrupt = mutableListOf<ByteArray>()
        var ready = false
        var input: ByteArray? = null
        var feature: ByteArray? = null
        var invalidHeader: Int? = null
        var virtualCableUnplug = false
        var failure: HidpFailure? = null
        val session = HidpSession(
            sendControl = { control += it; true },
            sendInterrupt = { interrupt += it; true },
            listener = this,
            monotonicTimeMs = monotonicTimeMs,
            protocolTimeoutMs = protocolTimeoutMs
        )

        override fun onReportProtocolReady() {
            ready = true
        }

        override fun onInputReport(report: ByteArray) {
            input = report
        }

        override fun onFeatureReport(report: ByteArray) {
            feature = report
        }

        override fun onInvalidFrame(header: Int?) {
            invalidHeader = header
        }

        override fun onVirtualCableUnplug() {
            virtualCableUnplug = true
        }

        override fun onClosed() = Unit

        override fun onFailure(failure: HidpFailure) {
            this.failure = failure
        }
    }

    companion object {
        private val CHANNELS = L2capHidChannels(0x40, 0x70, 0x41, 0x71)
    }
}
