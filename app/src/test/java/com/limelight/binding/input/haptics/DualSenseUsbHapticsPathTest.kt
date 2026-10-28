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
            usb.completions.add(-19) // ENODEV: withdraw direct output, then finish retirement.
            assertTrue(usb.closed.await(2, TimeUnit.SECONDS))
            val fallback = AuthoredPcmOutputRouter().submit(frame(1, 0, 240), sink, true)!!
            assertFalse(fallback.direct)
            assertTrue(fallback.failedSink)
            assertTrue(fallback.fallback.lowFrequency > 0)
        } finally { stop(sink) }
        assertEquals(listOf("cancel", "close", "alt0"), usb.events.takeLast(3))
        val count = usb.submitted
        sink.submit(frame(2, 0, 480))
        assertEquals(count, usb.submitted)
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
        var controlsWritten = 0
        val sink = DualSenseUsbHapticsSink(usb, onControl = { controlsWritten++ })
        sink.updateControl(Ds5HapticsState(ByteArray(8)))
        assertEquals("accepting controls must not perform HID I/O", 0, controlsWritten)
        assertFalse(sink.start())
        assertEquals("failed startup must discard queued host control", 0, controlsWritten)
        val routed = AuthoredPcmOutputRouter().submit(frame(0, 1, 240), sink, true)!!
        assertFalse(routed.direct)
        assertTrue(routed.failedSink)
        assertTrue(routed.fallback.lowFrequency > 0)
        assertEquals(listOf("cancel", "close", "alt0"), usb.events.takeLast(3))
        stop(sink)
        assertEquals(1, usb.events.count { it == "close" })
    }

    @Test fun uncertainNativeReleaseIsRetainedAndCannotAdvertiseRestoredInterface() {
        val usb = MockUsb(closeResult = -5)
        val sink = DualSenseUsbHapticsSink(usb)
        assertTrue(sink.start())
        stop(sink)
        assertNotNull(sink.releaseFailure)
        assertFalse(usb.events.contains("alt0"))
    }

    @Test fun blockedControlAndActivationAllowCancellationButDeferCompletion() {
        for (duringActivation in listOf(false, true)) {
            val usb = MockUsb()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val activations = Collections.synchronizedList(mutableListOf<Boolean>())
            val block = {
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
                usb.events.add("callback-done")
            }
            val sink = DualSenseUsbHapticsSink(usb, onControl = { block() }, onActiveChanged = { active ->
                activations.add(active)
                if (active && duringActivation) block()
            })
            val updating = Thread {
                assertTrue(sink.start())
                if (!duringActivation) {
                    sink.updateControl(Ds5HapticsState(ByteArray(8)))
                    usb.completions.add(0)
                }
            }.apply { start() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val stopping = Thread { sink.stopAndThen { usb.events.add("stopped"); stopped.countDown() } }
            try {
                stopping.start()
                assertTrue("blocked HID callback must not hold lifecycleLock",
                    usb.cancelSignal.await(200, TimeUnit.MILLISECONDS))
                assertEquals(1L, stopped.count)
            } finally {
                resume.countDown()
                updating.join(2000)
                stopping.join(2000)
                assertTrue(stopped.await(2, TimeUnit.SECONDS))
            }
            assertTrue(usb.events.indexOf("callback-done") < usb.events.indexOf("stopped"))
            if (duringActivation) assertEquals(listOf(true, false), activations)
        }
    }

    @Test fun blockedHidWriteDoesNotBlockAcceptanceAndLatestControlPrecedesNewPcm() {
        val usb = MockUsb()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val controls = Collections.synchronizedList(mutableListOf<Int>())
        val caller = Thread.currentThread()
        val sink = DualSenseUsbHapticsSink(usb, onControl = {
            assertNotSame("HID writes belong to the transport worker", caller, Thread.currentThread())
            controls.add(it.modeFlags0)
            usb.events.add("control-${it.modeFlags0}")
            if (it.modeFlags0 == 0) { entered.countDown(); check(resume.await(3, TimeUnit.SECONDS)) }
        })
        sink.updateControl(Ds5HapticsState(ByteArray(8)))
        val receiving = Thread {
            sink.updateControl(Ds5HapticsState(byteArrayOf(2, 4, 0, 0, 0, 0, 0, 0)))
            sink.submit(frame(0, 3, 240))
            accepted.countDown()
        }
        try {
            assertTrue(sink.start())
            usb.drainStartup()
            usb.completions.add(0)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            receiving.start()
            assertTrue("slow HID must not block control or PCM acceptance", accepted.await(200, TimeUnit.MILLISECONDS))
            resume.countDown()
            assertTrue(usb.packets.poll(2, TimeUnit.SECONDS)!!.all { it == 0.toByte() })
            assertPacket(usb.completeOne(), -12345, 23456)
            assertEquals("new control must be written before submitting the new PCM slot", listOf(0, 2), controls)
            assertTrue(usb.events.indexOf("control-2") < usb.events.indexOf("pcm"))
        } finally { resume.countDown(); receiving.join(2000); stop(sink) }
        sink.updateControl(Ds5HapticsState(ByteArray(8)))
        assertEquals(listOf(0, 2), controls)
    }

    @Test fun repeatedStopCannotCompleteBeforeTheFinalOutputCallback() {
        val usb = MockUsb()
        val releasing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val stopped = CountDownLatch(2)
        val sink = DualSenseUsbHapticsSink(usb, onActiveChanged = { active ->
            if (!active) { releasing.countDown(); check(resume.await(3, TimeUnit.SECONDS)) }
        })
        assertTrue(sink.start())
        val first = Thread { sink.stopAndThen { stopped.countDown() } }.apply { start() }
        assertTrue(releasing.await(1, TimeUnit.SECONDS))
        val second = Thread { sink.stopAndThen { stopped.countDown() } }.apply { start() }
        try {
            second.join(100)
            assertEquals("the final HID write still owns the connection", 2L, stopped.count)
        } finally {
            resume.countDown()
            first.join(2000)
            second.join(2000)
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
        }
        sink.updateControl(Ds5HapticsState(ByteArray(8)))
        assertFalse(sink.isOperational)
    }

    @Test fun transportCleanupExceptionsAreReportedWithoutStrandingStopCallbacks() {
        for (operation in listOf("cancel", "close", "alt0", "hid-clear")) {
            val usb = MockUsb(throwAt = operation)
            val stopped = CountDownLatch(1)
            val sink = DualSenseUsbHapticsSink(usb, onActiveChanged = { active ->
                if (!active && operation == "hid-clear") error("disconnected during final HID clear")
            })
            assertTrue(sink.start())
            sink.stopAndThen { stopped.countDown() }
            assertTrue("stop stranded after $operation", stopped.await(2, TimeUnit.SECONDS))
            if (operation == "hid-clear") assertEquals(listOf("cancel", "close", "alt0"), usb.events.takeLast(3))
            else assertNotNull("cleanup failure must block a clean handoff", sink.releaseFailure)
            assertFalse(sink.isOperational)
        }
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

    private class MockUsb(private val failSubmission: Int = -1, private val closeResult: Int = 0,
                          private val throwAt: String? = null) : DualSenseUsbHapticsTransport {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val completions = LinkedBlockingQueue<Int>()
        val packets = LinkedBlockingQueue<ByteArray>()
        val closed = CountDownLatch(1)
        val cancelSignal = CountDownLatch(1)
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
            if (bytes.any { it != 0.toByte() }) events.add("pcm")
            packets.add(bytes)
            return 0
        }

        override fun reap(timeoutMs: Int): Int = completions.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: -110
        override fun cancel() {
            cancelled = true; events.add("cancel"); cancelSignal.countDown(); completions.offer(-125)
            if (throwAt == "cancel") error("disconnected during cancel")
        }
        override fun close(): Int {
            events.add("close"); closed.countDown()
            if (throwAt == "close") error("disconnected during close")
            return closeResult
        }
        override fun restoreInterface(): Boolean {
            events.add("alt0")
            if (throwAt == "alt0") error("disconnected during alt0")
            return true
        }

        fun drainStartup() = repeat(4) {
            assertTrue(packets.poll(2, TimeUnit.SECONDS)!!.all { sample -> sample == 0.toByte() })
        }

        fun completeOne(): ByteArray {
            completions.add(0)
            return requireNotNull(packets.poll(2, TimeUnit.SECONDS)) { "ISO completion was not resubmitted" }
        }
    }
}
