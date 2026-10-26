package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DualSenseBluetoothHapticsSinkTest {
    private fun pulse(sequence: Int = 0, flags: Byte = 1) = Ds5HapticsPcmFrame(0, flags, sequence, 0, 48000, 240, 2, 16,
        ByteArray(960).apply { repeat(240) { i -> this[i * 4 + 1] = 32 } })

    @Test fun isolatedFiveMillisecondEffectPlaysAndStopIsATransportBarrier() {
        val played = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var writes = 0
        val sink = DualSenseBluetoothHapticsSink({ true }, {
            synchronized(played) { writes++ }
            if (it.any { sample -> sample != 0.toByte() }) played.countDown()
            true
        }, { true })
        assertTrue(sink.start())
        sink.submit(pulse())
        assertTrue("short pulse must not wait for four nonzero packets", played.await(1, TimeUnit.SECONDS))
        sink.stopAndThen { stopped.countDown() }
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        val count = synchronized(played) { writes }
        sink.submit(pulse(1))
        assertFalse(sink.isOperational)
        assertEquals(count, synchronized(played) { writes })
    }

    @Test fun transportFailureWithdrawsPcmAndNotifiesFallback() {
        val failed = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val sink = DualSenseBluetoothHapticsSink({ true }, { false }, { true }, { failed.countDown() })
        assertTrue(sink.start())
        sink.submit(pulse())
        assertTrue(failed.await(1, TimeUnit.SECONDS))
        assertFalse(sink.isOperational)
        sink.stopAndThen { stopped.countDown() }
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
    }

    @Test fun failureNotificationFollowsFinalTransportRelease() {
        val releasing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val sink = DualSenseBluetoothHapticsSink({ true }, { false }, { true },
            onFailure = { failed.countDown() }, release = {
                releasing.countDown()
                check(resume.await(2, TimeUnit.SECONDS))
                true
            })
        try {
            assertTrue(sink.start())
            sink.submit(pulse())
            assertTrue(releasing.await(1, TimeUnit.SECONDS))
            assertEquals("recovery must not start while the old sink can still clear transport state",
                1L, failed.count)
            resume.countDown()
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertFalse(sink.isOperational)
        } finally {
            resume.countDown()
            sink.stopAndThen { stopped.countDown() }
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun unavailableTransportNeverAnnouncesOperationalOutput() {
        val stopped = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val sink = DualSenseBluetoothHapticsSink({ false }, { error("must not write") }, { true },
            onFailure = { failed.countDown() })
        assertFalse(sink.start())
        sink.stopAndThen { stopped.countDown() }
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        assertTrue("failed startup must retire its route so a later ready event can retry",
            failed.await(1, TimeUnit.SECONDS))
    }

    @Test fun sequenceGapPreservesPartialPacketAndFilterHistory() {
        val frames = listOf(pulse(0), pulse(1, 0), pulse(2, 0), pulse(4, 2))
        val expected = mutableListOf<ByteArray>()
        DualSenseBluetoothPcmEncoder().apply {
            frames.forEach { encode(it, expected::add) }
            finish(expected::add)
        }
        val writing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val played = CountDownLatch(expected.size)
        val stopped = CountDownLatch(1)
        val actual = Collections.synchronizedList(mutableListOf<ByteArray>())
        val sink = DualSenseBluetoothHapticsSink({ true }, {
            writing.countDown()
            check(resume.await(2, TimeUnit.SECONDS))
            actual.add(it.copyOf())
            played.countDown()
            true
        }, { true })
        try {
            assertTrue(sink.start())
            frames.take(3).forEach(sink::submit)
            assertTrue(writing.await(1, TimeUnit.SECONDS))
            sink.submit(frames.last()) // Drop sequence 3 without declaring a discontinuity.
            resume.countDown()
            assertTrue(played.await(1, TimeUnit.SECONDS))
            expected.indices.forEach { assertArrayEquals(expected[it], actual[it]) }
        } finally {
            resume.countDown()
            sink.stopAndThen { stopped.countDown() }
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
        }
    }
}
