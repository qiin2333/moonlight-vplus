package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import com.limelight.nvstream.Ds5HapticsState
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs the production router and USB pump; only the authorized device/USBFS boundary is fake. */
class DualSenseUsbHapticsPathTest {
    private fun frame(sequence: Int, flags: Int, frames: Int, left: Int = -12345, right: Int = 23456) =
        Ds5HapticsPcmFrame(0, flags.toByte(), sequence, sequence * 5000L, 48000, frames, 2, 16,
            ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(frames) { putShort(left.toShort()); putShort(right.toShort()) }
            }.array())

    @Test fun hostPcmTraversesRouterAndUsbPumpWithoutGainOrChannelChanges() {
        val usb = MockUsb()
        val sink = DualSenseUsbHapticsSink(usb)
        try {
            assertTrue(sink.start())
            usb.drainStartup()
            val routed = AuthoredPcmOutputRouter().submit(frame(0, 1, 480), sink, true)!!
            assertTrue(routed.direct)
            assertEquals(ControllerRumbleState.ZERO, routed.fallback)
            repeat(10) { assertPacket(usb.completeOne(), -12345, 23456) }
            assertPacket(usb.completeOne(), 0, 0) // underrun is silence, never stale replay
        } finally { stop(sink) }
        assertEquals(listOf("cancel", "close", "alt0"), usb.events.takeLast(3))
    }

    @Test fun finalFiveMillisecondFrameIsNotLostBelowPrebufferThreshold() {
        val usb = MockUsb()
        val sink = DualSenseUsbHapticsSink(usb)
        try {
            assertTrue(sink.start())
            usb.drainStartup()
            // The wire parser permits END together with the last samples, not just an empty marker.
            val delivery = AuthoredPcmOutputRouter().submit(frame(0, 3, 240), sink, true)!!
            assertTrue(delivery.direct)
            repeat(5) { assertPacket(usb.completeOne(), -12345, 23456) }
            assertPacket(usb.completeOne(), 0, 0)
        } finally { stop(sink) }
    }

    @Test fun staleAndDuplicatePacketsCannotReplayAndDiscontinuityDropsBufferedOldEffect() {
        val usb = MockUsb()
        val sink = DualSenseUsbHapticsSink(usb)
        val router = AuthoredPcmOutputRouter()
        try {
            assertTrue(sink.start())
            usb.drainStartup()
            router.submit(frame(10, 1, 240, 111, 222), sink, true)
            assertNull(router.submit(frame(10, 0, 240, 999, 999), sink, true))
            assertNull(router.submit(frame(9, 0, 240, 999, 999), sink, true))
            router.submit(frame(11, 4, 240, 333, 444), sink, true)
            router.submit(frame(12, 2, 0), sink, true)
            repeat(5) { assertPacket(usb.completeOne(), 333, 444) }
            assertPacket(usb.completeOne(), 0, 0)
        } finally { stop(sink) }
    }

    @Test fun submissionFailureRestoresInterfaceAndSelectsFallbackInsteadOfClaimingDirectOutput() {
        val usb = MockUsb(failSubmission = 1)
        var controlAttached = false
        val sink = DualSenseUsbHapticsSink(usb,
            onActiveChanged = { if (!it) controlAttached = false },
            onControl = { controlAttached = true })
        sink.updateControl(Ds5HapticsState(ByteArray(8)))
        assertTrue(controlAttached)
        assertFalse(sink.start())
        assertFalse("startup failure must withdraw previously applied host control", controlAttached)
        val routed = AuthoredPcmOutputRouter().submit(frame(0, 1, 240), sink, true)!!
        assertFalse(routed.direct)
        assertTrue(routed.failedSink)
        assertTrue(routed.fallback.lowFrequency > 0)
        assertEquals(listOf("cancel", "close", "alt0"), usb.events.takeLast(3))
        stop(sink)
        assertEquals(1, usb.events.count { it == "close" })
    }

    @Test fun disconnectWithdrawsRouteAndStopCallbackFollowsFinalTransportAccess() {
        val usb = MockUsb()
        val sink = DualSenseUsbHapticsSink(usb)
        assertTrue(sink.start())
        usb.drainStartup()
        usb.completions.add(-19) // ENODEV
        assertTrue(usb.closed.await(2, TimeUnit.SECONDS))
        assertFalse(sink.isOperational)
        val fallback = AuthoredPcmOutputRouter().submit(frame(0, 1, 240), sink, true)!!
        assertFalse(fallback.direct)
        assertTrue(fallback.failedSink)
        val stopped = CountDownLatch(1)
        sink.stopAndThen { usb.events.add("stopped"); stopped.countDown() }
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        val count = usb.submitted
        sink.submit(frame(1, 1, 480))
        assertEquals(count, usb.submitted)
        assertEquals(listOf("cancel", "close", "alt0", "stopped"), usb.events.takeLast(4))
    }

    @Test fun uncertainNativeReleaseIsRetainedAndCannotAdvertiseRestoredInterface() {
        val usb = MockUsb(closeResult = -5)
        val sink = DualSenseUsbHapticsSink(usb)
        assertTrue(sink.start())
        stop(sink)
        assertNotNull(sink.releaseFailure)
        assertFalse(usb.events.contains("alt0"))
    }

    private fun assertPacket(bytes: ByteArray, left: Int, right: Int) {
        assertEquals(384, bytes.size)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48) {
            assertEquals("speaker-left stays silent", 0, data.short.toInt())
            assertEquals("speaker-right stays silent", 0, data.short.toInt())
            assertEquals(left, data.short.toInt())
            assertEquals(right, data.short.toInt())
        }
    }

    private fun stop(sink: WaveformHapticsSink) {
        val stopped = CountDownLatch(1)
        sink.stopAndThen { stopped.countDown() }
        assertTrue("transport retirement must complete", stopped.await(2, TimeUnit.SECONDS))
    }

    private class MockUsb(private val failSubmission: Int = -1, private val closeResult: Int = 0) : DualSenseUsbHapticsTransport {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val completions = LinkedBlockingQueue<Int>()
        val packets = LinkedBlockingQueue<ByteArray>()
        val closed = CountDownLatch(1)
        @Volatile var submitted = 0
        @Volatile private var cancelled = false

        override fun open(slots: Int, packetSize: Int): Boolean {
            assertEquals(4, slots)
            assertEquals(384, packetSize)
            events.add("open")
            return true
        }

        override fun submit(slot: Int, buffer: ByteBuffer): Int {
            check(!cancelled) { "submission after cancellation" }
            if (submitted++ == failSubmission) return -5
            val bytes = ByteArray(384)
            buffer.duplicate().apply { rewind(); get(bytes) }
            packets.add(bytes)
            return 0
        }

        override fun reap(timeoutMs: Int): Int = completions.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: -110
        override fun cancel() { cancelled = true; events.add("cancel"); completions.offer(-125) }
        override fun close(): Int { events.add("close"); closed.countDown(); return closeResult }
        override fun restoreInterface(): Boolean { events.add("alt0"); return true }

        fun drainStartup() = repeat(4) {
            assertTrue(packets.poll(2, TimeUnit.SECONDS)!!.all { sample -> sample == 0.toByte() })
        }

        fun completeOne(): ByteArray {
            completions.add(0)
            return requireNotNull(packets.poll(2, TimeUnit.SECONDS)) { "ISO completion was not resubmitted" }
        }
    }
}
