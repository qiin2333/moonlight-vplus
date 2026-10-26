package com.limelight.binding.input.haptics

import com.limelight.binding.input.driver.wireless.dualsense.DualSenseBluetoothOutputWriter
import com.limelight.nvstream.Ds5HapticsPcmFrame
import com.limelight.nvstream.Ds5HapticsState
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

/** Production router -> resampler/pump -> shared HID writer -> fake physical Bluetooth device. */
class DualSenseBluetoothHapticsPathTest {
    private fun pulse(sequence: Int = 0, flags: Byte = 3) = Ds5HapticsPcmFrame(0, flags, sequence,
        sequence * 10000L, 48000, 480, 2, 16,
        ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(480) { putShort((sin(2 * PI * 120 * it / 48000) * 16384).toInt().toShort()); putShort(0) }
        }.array())

    @Test fun signedStereoPcmAndHostModeReachSameSerializedPhysicalWriter() {
        val device = MockBluetooth()
        val writer = DualSenseBluetoothOutputWriter(device::send)
        val sink = sink(writer)
        try {
            assertTrue(sink.start())
            sink.updateControl(Ds5HapticsState(byteArrayOf(0, 0, 0x62, 0, 0, 0, 0, 1)))
            val delivery = AuthoredPcmOutputRouter().submit(pulse(), sink, true)!!
            assertTrue(delivery.direct)
            assertEquals(ControllerRumbleState.ZERO, delivery.fallback)
            val pcm = List(2) { device.await { it[0] == 0x32.toByte() } }
            val left = pcm.flatMap { report -> (10 until 74 step 2).map { report[it].toInt() } }
            assertTrue("native waveform retains positive phase", left.max() > 20)
            assertTrue("native waveform retains negative phase", left.min() < -20)
            assertTrue("silent right channel must not become a second rumble motor",
                pcm.all { report -> (11 until 74 step 2).all { report[it] == 0.toByte() } })

            // A zero-strength V2 compatibility mode is distinguishable from native PCM mode.
            sink.updateControl(Ds5HapticsState(byteArrayOf(2, 4, 0x62, 0, 0, 0x10, 0x23, 1)))
            val mode = device.await { it[0] == 0x31.toByte() && it[41].toInt() and 4 != 0 }
            assertEquals(2, mode[3].toInt() and 3)
            writer.updateRumble(0x5500, 0x3300)
            writer.updateLightbar(1, 2, 3)
            val led = device.await { it[0] == 0x31.toByte() && it[47] == 1.toByte() }
            assertEquals(2, led[3].toInt() and 3)
            assertEquals(4, led[41].toInt() and 4)
            assertEquals(0, led[5].toInt())
            assertEquals(0, led[6].toInt())
            assertEquals(0x10, led[12].toInt())
            assertEquals(0x23, led[39].toInt())
        } finally {
            stop(sink)
            writer.close()
        }
        val captured = device.snapshot()
        captured.zipWithNext().forEach { (first, second) ->
            assertEquals(((first[1].toInt() and 255) / 16 + 1) % 16, (second[1].toInt() and 255) / 16)
        }
        assertEquals(0x31, captured.last()[0].toInt())
        assertEquals(0, captured.last()[5].toInt())
        assertEquals(0, captured.last()[6].toInt())
        val count = captured.size
        sink.submit(pulse(1))
        assertEquals(count, device.snapshot().size)
    }

    @Test fun rejectedPhysicalPcmWithdrawsDirectRouteAndRestoresFallback() {
        val device = MockBluetooth(rejectPcm = true)
        val failed = CountDownLatch(1)
        val writer = DualSenseBluetoothOutputWriter(device::send)
        val sink = sink(writer) { failed.countDown() }
        val router = AuthoredPcmOutputRouter()
        try {
            assertTrue(sink.start())
            router.submit(pulse(flags = 1), sink, true)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            val fallback = router.submit(pulse(1, 0), sink, true)!!
            assertFalse(fallback.direct)
            assertTrue(fallback.failedSink)
            assertTrue(fallback.fallback.lowFrequency > 0)
        } finally { stop(sink); writer.close() }
    }

    @Test fun disabledControllerOutputUsesFallbackWithoutSendingPhysicalPcm() {
        val device = MockBluetooth()
        val writer = DualSenseBluetoothOutputWriter(device::send)
        val sink = sink(writer)
        try {
            assertTrue(sink.start())
            val delivery = AuthoredPcmOutputRouter().submit(pulse(flags = 1), sink, false)!!
            assertFalse(delivery.direct)
            assertFalse(delivery.failedSink)
            assertTrue(delivery.fallback.lowFrequency > 0)
        } finally { stop(sink); writer.close() }
        assertTrue(device.snapshot().none { it[0] == 0x32.toByte() })
    }

    @Test fun controlUpdatePrecedesNextPcmWhenTheAsyncWriterIsBacklogged() {
        val device = MockBluetooth()
        val executor = Executors.newSingleThreadExecutor()
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        executor.execute { blocked.countDown(); check(resume.await(3, TimeUnit.SECONDS)) }
        assertTrue(blocked.await(1, TimeUnit.SECONDS))
        val writer = DualSenseBluetoothOutputWriter(device::send, executor = executor)
        try {
            writer.updateControl(Ds5HapticsState(byteArrayOf(0, 0, 0x62, 0, 0, 0, 0, 1)))
            assertTrue(writer.writeHaptics(ByteArray(64)))
            writer.updateControl(Ds5HapticsState(byteArrayOf(2, 4, 0x62, 0, 0, 0, 0, 1)))
            assertTrue(writer.writeHaptics(ByteArray(64)))
            val reports = device.snapshot()
            val mode = reports[reports.lastIndex - 1]
            assertEquals("the accepted mode update must precede subsequent PCM", 0x31, mode[0].toInt())
            assertEquals(2, mode[3].toInt() and 3)
            assertEquals(4, mode[41].toInt() and 4)
            assertEquals(0x32, reports.last()[0].toInt())
        } finally {
            resume.countDown()
            writer.close()
        }
    }

    private fun sink(writer: DualSenseBluetoothOutputWriter, failure: () -> Unit = {}) =
        DualSenseBluetoothHapticsSink({ true }, writer::writeHaptics, writer::pauseHaptics,
            onFailure = failure, control = { writer.updateControl(it) }, release = writer::releaseHaptics)

    private fun stop(sink: WaveformHapticsSink) {
        val stopped = CountDownLatch(1)
        sink.stopAndThen { stopped.countDown() }
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
    }

    private class MockBluetooth(private val rejectPcm: Boolean = false) {
        private val reports = Collections.synchronizedList(mutableListOf<ByteArray>())
        private val pending = LinkedBlockingQueue<ByteArray>()

        fun send(bytes: ByteArray): Boolean {
            assertTrue(bytes.size == 78 && bytes[0] == 0x31.toByte() || bytes.size == 142 && bytes[0] == 0x32.toByte())
            val crcOffset = bytes.size - 4
            val crc = CRC32().apply { update(0xA2); update(bytes, 0, crcOffset) }.value
            val stored = (0..3).fold(0L) { result, i -> result or ((bytes[crcOffset + i].toLong() and 255) shl (8 * i)) }
            assertEquals("every physical report has a valid CRC", crc, stored)
            if (rejectPcm && bytes[0] == 0x32.toByte()) return false
            reports.add(bytes.copyOf())
            pending.add(bytes.copyOf())
            return true
        }

        fun await(predicate: (ByteArray) -> Boolean): ByteArray {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "expected Bluetooth report was not sent" }
                val report = requireNotNull(pending.poll(remaining, TimeUnit.NANOSECONDS)) { "Bluetooth path timed out" }
                if (predicate(report)) return report
            }
        }

        fun snapshot() = synchronized(reports) { reports.toList() }
    }
}
