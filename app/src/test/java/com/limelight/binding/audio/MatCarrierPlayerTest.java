package com.limelight.binding.audio;

import org.junit.Test;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Generated carrier-shaped data tests transport only; never Dolby decoding. */
public class MatCarrierPlayerTest {
    private static byte[] cycle() {
        byte[] b = new byte[61440];
        for (int i = 8; i < b.length; i++) b[i] = (byte) (i * 31);
        b[0] = 0x72; b[1] = (byte) 0xf8; b[2] = 0x1f; b[3] = 0x4e;
        b[4] = 0x16; b[6] = 2;
        return b;
    }
    private static class Sink implements MatCarrierPlayer.Sink {
        final MessageDigest hash;
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean zero, frozen, closed;
        long words;
        int calls;
        Sink() throws Exception { hash = MessageDigest.getInstance("SHA-256"); }
        public void start() {}
        public int write(short[] data, int offset, int count) {
            entered.countDown();
            if (zero || ++calls % 7 == 0) return 0;
            int n = Math.min(count, 512);
            for (int i = offset; i < offset + n; i++) {
                hash.update((byte) data[i]); hash.update((byte) (data[i] >>> 8));
            }
            words += n;
            return n;
        }
        public long playedFrames() { return frozen ? 0 : words / 8; }
        public void close() { closed = true; }
    }
    @Test public void shortWritesPreserveCopiedBytesAndDrain() throws Exception {
        Sink sink = new Sink();
        byte[] source = cycle();
        String expected = MatCarrierPlayer.hex(MessageDigest.getInstance("SHA-256").digest(source));
        try (MatCarrierPlayer p = new MatCarrierPlayer(sink)) {
            p.start(); assertTrue(p.awaitReady(1000));
            assertTrue(p.offerMatCycle(source, source.length, 1, 1, 0, 0));
            java.util.Arrays.fill(source, (byte) 0);
            assertTrue(p.endMatStream(1)); assertTrue(p.awaitCompletion(2000));
            assertEquals(expected, p.submittedSha256());
            assertEquals(expected, MatCarrierPlayer.hex(sink.hash.digest()));
            assertEquals(61440, p.submittedBytes()); assertEquals(1, p.writtenCycles());
        }
        assertTrue(sink.closed);
    }
    @Test public void unplayedTailCannotSucceed() throws Exception {
        Sink sink = new Sink(); sink.frozen = true;
        try (MatCarrierPlayer p = new MatCarrierPlayer(sink, 4, 1000, 30)) {
            p.start(); assertTrue(p.awaitReady(1000));
            assertTrue(p.offerMatCycle(cycle(), 61440, 1, 1, 0, 0));
            assertTrue(p.endMatStream(1)); assertFalse(p.awaitCompletion(2000));
            assertEquals(MatCarrierPlayer.State.FAILED, p.state());
            assertTrue(p.error().contains("drain timed out"));
        }
        assertTrue(sink.closed);
    }
    @Test public void overflowIsTerminalAndCancellationReleasesSink() throws Exception {
        Sink sink = new Sink(); sink.zero = true;
        try (MatCarrierPlayer p = new MatCarrierPlayer(sink, 1, 1000, 1000)) {
            p.start(); assertTrue(p.awaitReady(1000));
            assertTrue(p.offerMatCycle(cycle(), 61440, 1, 1, 0, 0));
            assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
            assertTrue(p.offerMatCycle(cycle(), 61440, 1, 1, 1, 960));
            assertFalse(p.offerMatCycle(cycle(), 61440, 1, 1, 2, 1920));
            assertFalse(p.awaitCompletion(2000));
            assertEquals(MatCarrierPlayer.State.FAILED, p.state());
        }
        assertTrue(sink.closed);
    }
    @Test public void newSessionStartsAtZeroAndRejectsDomainChange() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Sink sink = new Sink();
            try (MatPlaybackSession session = new MatPlaybackSession(sink)) {
                assertTrue(session.offerMatCycle(cycle(), 61440, attempt + 1, 1, 0, 0));
                assertFalse(session.offerMatCycle(cycle(), 61440, attempt + 1, 2, 1, 960));
                assertFalse(session.endMatStream(1));
            }
            assertTrue(sink.closed);
        }
    }
}
