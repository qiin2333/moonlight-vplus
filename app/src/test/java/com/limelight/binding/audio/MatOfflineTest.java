package com.limelight.binding.audio;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Plain JVM executable: real production receiver JNI + production player, fake hardware only. */
public final class MatOfflineTest {
    private static int cases;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void pass(String name) { cases++; System.out.println("PASS " + name); }
    private static class FakeSink implements MatCarrierPlayer.Sink {
        final MessageDigest digest;
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean zero, negative, invalidCount, headBlocked, closed, opened;
        volatile long bytes;
        int calls;
        FakeSink() throws Exception { digest = MessageDigest.getInstance("SHA-256"); }
        @Override public void start() { opened = true; }
        @Override public int write(short[] words, int offset, int count) {
            entered.countDown(); calls++;
            if (negative) return -6;
            if (invalidCount) return 1;
            if (zero || calls % 11 == 0) return 0;
            int n = Math.min(count, new int[]{8, 24, 512, 4096, 8192}[calls % 5]);
            byte[] accepted = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                accepted[2 * i] = (byte) words[offset + i];
                accepted[2 * i + 1] = (byte) (words[offset + i] >>> 8);
            }
            digest.update(accepted); bytes += accepted.length;
            return n;
        }
        @Override public long playedFrames() { return headBlocked ? 0 : bytes / 16; }
        @Override public void close() { closed = true; }
        String hash() { return MatCarrierPlayer.hex(digest.digest()); }
    }
    private static byte[] first;
    private static boolean offer(MatCarrierConsumer consumer, long index) {
        return consumer.offerMatCycle(first, first.length, 1, 1, index, index * 960);
    }
    private static void inputTests(byte[] bytes) throws Exception {
        MatRecording.readVerified(new ByteArrayInputStream(bytes));
        for (int kind = 0; kind < 3; kind++) {
            byte[] bad = Arrays.copyOf(bytes, bytes.length + (kind == 0 ? -1 : kind == 1 ? 1 : 0));
            if (kind == 2) bad[100] ^= 1;
            boolean rejected = false;
            try { MatRecording.readVerified(new ByteArrayInputStream(bad)); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "Recording accepted truncation, trailing data or SHA mutation");
        }
        pass("bounded recording verification: 178 cycles, SHA, truncation/trailing/mutation rejection");
    }
    private static void realTimeRecording(byte[] bytes) throws Exception {
        MatRecording recording = MatRecording.readVerified(new ByteArrayInputStream(bytes));
        FakeSink sink = new FakeSink();
        long start = System.nanoTime();
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink);
             MatNativeReceiver receiver = new MatNativeReceiver(player)) {
            player.start(); check(player.awaitReady(1000), "sink ready"); recording.replay(receiver);
            check(player.awaitCompletion(5000), "Native recording playback: " + player.error());
            check(player.writtenCycles() == 178 && player.submittedBytes() == bytes.length, "lost cycle/byte");
            check(MatRecording.VERIFIED_SHA256.equals(player.submittedSha256()), "player submitted hash");
            check(MatRecording.VERIFIED_SHA256.equals(sink.hash()), "independent fake AudioTrack hash");
            check(player.peakQueue() <= 4 && sink.closed, "bounds and release");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(elapsedMs >= 3500 && elapsedMs < 4500, "source cadence " + elapsedMs);
            System.out.println("recording: cycles=178 bytes=" + player.submittedBytes() +
                    " peakQueue=" + player.peakQueue() + " elapsedMs=" + elapsedMs +
                    " sha256=" + player.submittedSha256());
        }
        pass("3.56s recording -> real JNI -> bounded player -> partial/zero writes -> byte-exact SHA");
    }
    private static long fnv(byte[] bytes, int from, int count, long initial, boolean packet) {
        long hash = initial;
        for (int i = 0; i < count; i++) {
            hash ^= packet && i >= 72 && i < 76 ? 0 : bytes[from + i] & 255;
            hash = (hash * 16777619L) & 0xffffffffL;
        }
        return hash;
    }
    private static void put(byte[] b, int offset, long value, int n) {
        for (int i = 0; i < n; i++) b[offset + i] = (byte) (value >>> (i * 8));
    }
    private static byte[] packet(int kind, int frame, int fragment, long hash, byte[] bytes) {
        byte[] p = new byte[1104];
        p[0] = 'S'; p[1] = 'M'; p[2] = 'A'; p[3] = 'T'; p[4] = 1; p[5] = (byte)kind; p[6] = 80;
        put(p, 8, 1, 4); put(p, 12, 1, 4); put(p, 16, 1, 8); put(p, 24, 1, 8);
        put(p, 32, frame, 8); put(p, 40, frame * 960L, 8); put(p, 48, 48000, 4);
        if (kind == 1) {
            put(p, 52, 960, 4); put(p, 56, 61440, 4); p[60] = (byte)fragment; p[62] = 60;
            put(p, 64, 1024, 4);
            System.arraycopy(bytes, frame * 61440 + fragment * 1024, p, 80, 1024);
        }
        put(p, 68, hash, 4); put(p, 72, fnv(p, 0, p.length, 2166136261L, true), 4);
        return p;
    }
    private static void packetReplay(byte[] bytes) throws Exception {
        FakeSink sink = new FakeSink();
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink);
             MatNativeReceiver receiver = new MatNativeReceiver(player)) {
            player.start(); long streamHash = 2166136261L;
            check(receiver.pushPacket(packet(2, 0, 0, 0, bytes), 1104), "SYNC");
            for (int frame = 0; frame < 178; frame++) {
                long hash = fnv(bytes, frame * 61440, 61440, 2166136261L, false);
                // Reverse order and an identical duplicate within each cycle.
                for (int fragment = 59; fragment >= 0; fragment--) {
                    byte[] p = packet(1, frame, fragment, hash, bytes);
                    check(receiver.pushPacket(p, p.length), "packet " + frame + "/" + fragment + ": " + player.error());
                    if (fragment == 30) check(receiver.pushPacket(p, p.length), "duplicate fragment");
                }
                streamHash = fnv(bytes, frame * 61440, 61440, streamHash, false);
                Thread.sleep(20);
            }
            byte[] end = packet(3, 178, 0, streamHash, bytes);
            check(receiver.pushPacket(end, end.length) && receiver.pushPacket(end, end.length), "redundant EOS");
            check(player.awaitCompletion(5000), "packet replay: " + player.error());
            check(player.writtenCycles() == 178 && player.submittedBytes() == bytes.length, "packet count");
            check(MatRecording.VERIFIED_SHA256.equals(player.submittedSha256()), "packet output SHA");
            check(MatRecording.VERIFIED_SHA256.equals(sink.hash()), "packet fake sink SHA");
        }
        pass("SMAT 178 cycles -> shared native receiver -> JNI -> output; reordered/duplicate fragments and EOS");
    }
    private static void stateTests() throws Exception {
        FakeSink sink = new FakeSink(); sink.zero = true;
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink, 1, 1000, 1000)) {
            player.start(); check(offer(player, 0), "initial offer");
            check(sink.entered.await(1, TimeUnit.SECONDS), "writer entry");
            check(offer(player, 1) && !offer(player, 2), "queue must reject overflow");
            check(!player.awaitCompletion(1000) && player.state() == MatCarrierPlayer.State.FAILED, "overflow failure");
        }
        check(sink.closed, "overflow release"); pass("bounded queue overflow is terminal; no silent drop");
        for (int kind = 0; kind < 3; kind++) {
            FakeSink failing = new FakeSink();
            failing.zero = kind == 0; failing.negative = kind == 1; failing.invalidCount = kind == 2;
            try (MatCarrierPlayer player = new MatCarrierPlayer(failing, 4, 30, 30)) {
                player.start(); check(offer(player, 0), "offer failing sink");
                check(!player.awaitCompletion(2000) && player.state() == MatCarrierPlayer.State.FAILED,
                        "zero/error/misaligned write not rejected");
            }
            check(failing.closed, "failed sink released");
        }
        pass("zero-progress timeout, DEAD_OBJECT-style error and invalid write count");
        FakeSink delayed = new FakeSink(); delayed.headBlocked = true;
        try (MatCarrierPlayer player = new MatCarrierPlayer(delayed, 4, 1000, 1000)) {
            player.start(); check(offer(player, 0) && player.endMatStream(1), "delayed EOS");
            check(!player.awaitCompletion(50) && !delayed.closed, "EOS must not flush unplayed tail");
            delayed.headBlocked = false;
            check(player.awaitCompletion(1000), "EOS drain completion");
        }
        pass("EOS drains hardware playhead before release");
        FakeSink frozen = new FakeSink(); frozen.headBlocked = true;
        try (MatCarrierPlayer player = new MatCarrierPlayer(frozen, 4, 1000, 30)) {
            player.start(); check(offer(player, 0) && player.endMatStream(1), "frozen EOS");
            check(!player.awaitCompletion(1000) && player.state() == MatCarrierPlayer.State.FAILED, "drain timeout");
        }
        pass("EOS timeout cannot report completion");
        FakeSink cancel = new FakeSink(); cancel.zero = true;
        MatCarrierPlayer player = new MatCarrierPlayer(cancel);
        player.start(); check(offer(player, 0), "cancel offer");
        check(cancel.entered.await(1, TimeUnit.SECONDS), "cancel entry");
        long start = System.nanoTime();
        Thread.currentThread().interrupt(); player.close();
        check(Thread.interrupted(), "close must preserve owner cancellation interrupt");
        check(cancel.closed && player.state() == MatCarrierPlayer.State.STOPPED &&
                System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "bounded stop");
        pass("cancellation joins output thread and releases sink");
        FakeSink copy = new FakeSink(); copy.zero = true;
        byte[] borrowed = first.clone();
        try (MatCarrierPlayer p = new MatCarrierPlayer(copy)) {
            p.start();
            check(p.offerMatCycle(borrowed, borrowed.length, 1, 1, 0, 0), "borrowed copy");
            Arrays.fill(borrowed, (byte)0); copy.zero = false;
            check(p.endMatStream(1) && p.awaitCompletion(2000), "copy complete");
            check(p.submittedSha256().equals(MatCarrierPlayer.hex(MessageDigest.getInstance("SHA-256").digest(first))),
                    "consumer retained borrowed/reused JNI array");
        }
        pass("borrowed array is copied before callback returns; no byte swap");
    }
    private static final class Consumer implements MatCarrierConsumer {
        int offers, ends; boolean reject;
        @Override public boolean offerMatCycle(byte[] b, int n, long e, long d, long i, long pts) {
            check(n == 61440 && e == 1 && d == 1 && pts == i * 960, "JNI metadata");
            offers++; return !reject;
        }
        @Override public boolean endMatStream(long n) { ends++; return true; }
    }
    private static void nativeFailures(byte[] bytes) throws Exception {
        for (int kind = 0; kind < 12; kind++) {
            Consumer consumer = new Consumer();
            try (MatNativeReceiver receiver = new MatNativeReceiver(consumer)) {
                if (kind == 0) {
                    byte[] bad = first.clone(); bad[0] = 0;
                    check(!receiver.offerMatCycle(bad, bad.length, 1, 1, 0, 0), "bad carrier");
                } else if (kind == 1) check(!receiver.offerMatCycle(first, 16000, 1, 1, 0, 0), "short cycle");
                else if (kind == 2) check(!receiver.offerMatCycle(first, first.length, 1, 1, 0, 960), "wrong PTS");
                else if (kind == 3) check(!receiver.offerMatCycle(first, first.length, -1, 1, 0, 0), "negative epoch");
                else if (kind == 4) {
                    check(offer(receiver, 0), "first native cycle");
                    check(!receiver.offerMatCycle(first, first.length, 1, 2, 1, 960), "domain jump");
                } else if (kind == 5) { consumer.reject = true; check(!offer(receiver, 0), "consumer rejection"); }
                else if (kind == 6) {
                    check(offer(receiver, 0) && !receiver.endMatStream(2), "wrong EOS count");
                } else if (kind == 7 || kind == 8) {
                    byte[] p = packet(1, 0, 0, fnv(first, 0, first.length, 2166136261L, false), bytes);
                    check(receiver.pushPacket(p, p.length), "first fragment"); p[100] ^= 1;
                    if (kind == 8) put(p, 72, fnv(p, 0, p.length, 2166136261L, true), 4);
                    check(!receiver.pushPacket(p, p.length), "corrupt packet");
                } else if (kind == 9) {
                    long hash = fnv(first, 0, first.length, 2166136261L, false);
                    for (int fragment = 0; fragment < 59; fragment++) {
                        byte[] p = packet(1, 0, fragment, hash, bytes);
                        check(receiver.pushPacket(p, p.length), "incomplete cycle fragment");
                    }
                    byte[] end = packet(3, 1, 0, hash, bytes);
                    check(!receiver.pushPacket(end, end.length), "missing fragment before EOS");
                } else if (kind == 10) {
                    byte[] p = packet(1, 1, 0, 0, bytes);
                    check(!receiver.pushPacket(p, p.length), "cross-cycle gap");
                } else {
                    check(offer(receiver, 0), "raw mode input");
                    byte[] p = packet(2, 0, 0, 0, bytes);
                    check(!receiver.pushPacket(p, p.length), "mixed recording/SMAT input mode");
                }
                check(!offer(receiver, 0), "native failure must be sticky");
                check(consumer.ends == 0, "failed receiver emitted EOS");
            }
        }
        pass("JNI rejects bad size/header/PTS/epoch/domain/consumer/EOS/corruption, failure sticky");
        Consumer missing = new Consumer();
        try (MatNativeReceiver receiver = new MatNativeReceiver(missing)) { check(offer(receiver, 0), "missing EOS source"); }
        check(missing.ends == 0, "close synthesized EOS");
        pass("closing incomplete JNI receiver never synthesizes EOS");
        Consumer closed = new Consumer(); MatNativeReceiver receiver = new MatNativeReceiver(closed);
        receiver.close(); receiver.close();
        check(!offer(receiver, 0), "closed handle reused");
        pass("JNI handle double close and push-after-close are safe");
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 1, "verified source path required");
        byte[] bytes = Files.readAllBytes(Paths.get(args[0])); first = Arrays.copyOf(bytes, 61440);
        inputTests(bytes); realTimeRecording(bytes); packetReplay(bytes); stateTests(); nativeFailures(bytes);
        System.out.println("PASS ALL " + cases + " groups; fake hardware only, NOT Android playback or Atmos verification");
    }
}
