package com.limelight.binding.audio;

import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Android-independent bit-exact output state machine. Never used by MAT_DUMP. */
public final class MatCarrierPlayer implements MatCarrierConsumer, AutoCloseable {
    public static final int CYCLE_BYTES = 61440, RATE = 192000, CHANNELS = 8;
    public static final int FRAMES_PER_CYCLE = 3840;
    public interface Sink extends AutoCloseable {
        void start() throws Exception;
        /** Non-blocking; count and return value are 16-bit words, not bytes. */
        int write(short[] words, int offset, int count) throws Exception;
        /** Carrier frames since this track was opened; implementation handles 32-bit wrap. */
        long playedFrames() throws Exception;
        void close() throws Exception;
    }
    public enum State { NEW, RUNNING, DRAINING, COMPLETED, FAILED, STOPPED }
    private static final class Slot { final short[] words = new short[CYCLE_BYTES / 2]; }
    private final Sink sink;
    private final ArrayBlockingQueue<Slot> pending, free;
    private final long stallNanos, drainNanos;
    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread writer;
    private volatile State state = State.NEW;
    private volatile String error = "", submittedSha256 = "";
    private volatile long receivedCycles, writtenCycles, submittedBytes;
    private volatile int peakQueue;
    private long epoch, domain;

    public MatCarrierPlayer(Sink sink) { this(sink, 4, 1000, 3000); }
    public MatCarrierPlayer(Sink sink, int capacity, long stallMs, long drainMs) {
        if (sink == null || capacity < 1 || capacity > 16 || stallMs < 1 || drainMs < 1)
            throw new IllegalArgumentException("Invalid MAT output bounds");
        this.sink = sink;
        pending = new ArrayBlockingQueue<>(capacity);
        free = new ArrayBlockingQueue<>(capacity + 2);
        for (int i = 0; i < capacity + 2; i++) free.add(new Slot());
        stallNanos = TimeUnit.MILLISECONDS.toNanos(stallMs);
        drainNanos = TimeUnit.MILLISECONDS.toNanos(drainMs);
        writer = new Thread(this::run, "mat-iec-writer");
    }
    public synchronized void start() {
        if (state != State.NEW) throw new IllegalStateException("MAT player already started");
        state = State.RUNNING; writer.start();
    }
    public static boolean validCarrier(byte[] b, int offset, int length) {
        if (b == null || offset < 0 || length != CYCLE_BYTES || offset > b.length - length) return false;
        int pd = (b[offset + 6] & 255) | ((b[offset + 7] & 255) << 8);
        return b[offset] == 0x72 && b[offset + 1] == (byte) 0xf8 && b[offset + 2] == 0x1f &&
                b[offset + 3] == 0x4e && (b[offset + 4] & 31) == 0x16 &&
                (b[offset + 4] & 0x80) == 0 && pd >= 2 && pd <= CYCLE_BYTES - 8 && (pd & 1) == 0;
    }
    @Override public synchronized boolean offerMatCycle(byte[] b, int length, long e, long d,
                                                         long index, long pts48k) {
        if (state != State.RUNNING) return false;
        if (!validCarrier(b, 0, length) || e <= 0 || d <= 0 || index != receivedCycles ||
                index >= Long.MAX_VALUE / 960 || pts48k != index * 960 ||
                (receivedCycles != 0 && (e != epoch || d != domain))) return fail("Invalid MAT cycle metadata");
        Slot slot = free.poll();
        if (slot == null) return fail("MAT buffer pool exhausted");
        // Lossless little-endian reinterpretation, NOT another IEC byte swap.
        for (int i = 0; i < slot.words.length; i++)
            slot.words[i] = (short) ((b[2 * i] & 255) | ((b[2 * i + 1] & 255) << 8));
        if (!pending.offer(slot)) { free.add(slot); return fail("MAT output queue overflow"); }
        epoch = e; domain = d; receivedCycles++;
        peakQueue = Math.max(peakQueue, pending.size());
        return true;
    }
    @Override public synchronized boolean endMatStream(long cycles) {
        if (state != State.RUNNING || cycles == 0 || cycles != receivedCycles) return fail("Invalid MAT EOS");
        state = State.DRAINING; return true;
    }
    private synchronized boolean fail(String message) {
        if (state != State.FAILED && state != State.STOPPED) { error = message; state = State.FAILED; }
        if (Thread.currentThread() != writer) writer.interrupt();
        return false;
    }
    private boolean active() { return state == State.RUNNING || state == State.DRAINING; }
    private void run() {
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            byte[] scratch = new byte[CYCLE_BYTES];
            sink.start();
            ready.countDown();
            long lastHead = 0, lastHeadChange = System.nanoTime(), nextHeadCheck = 0;
            while (active()) {
                Slot slot = pending.poll(10, TimeUnit.MILLISECONDS);
                if (slot == null) {
                    // EOS is published under the same monitor as offer; never skip a final enqueue.
                    synchronized (this) {
                        if (state == State.DRAINING && pending.isEmpty()) break;
                    }
                    continue;
                }
                try {
                    int offset = 0;
                    long lastWrite = System.nanoTime();
                    while (offset < slot.words.length && active()) {
                        int n = sink.write(slot.words, offset, slot.words.length - offset);
                        if (n < 0 || n > slot.words.length - offset || n % CHANNELS != 0)
                            throw new IllegalStateException("Invalid IEC write result: " + n);
                        long now = System.nanoTime();
                        if (n == 0) {
                            if (now - lastWrite > stallNanos) throw new IllegalStateException("IEC write made no progress");
                            Thread.sleep(1);
                        } else {
                            if (submittedBytes <= lastHead * 16) lastHeadChange = now;
                            for (int i = 0; i < n; i++) {
                                short word = slot.words[offset + i];
                                scratch[2 * i] = (byte) word; scratch[2 * i + 1] = (byte) (word >>> 8);
                            }
                            digest.update(scratch, 0, n * 2);
                            offset += n; submittedBytes += n * 2L; lastWrite = now;
                        }
                        if (now >= nextHeadCheck) {
                            long head = sink.playedFrames();
                            if (head < lastHead) throw new IllegalStateException("IEC playhead moved backwards");
                            if (head > lastHead) { lastHead = head; lastHeadChange = now; }
                            else if (submittedBytes > head * 16 && now - lastHeadChange > stallNanos)
                                throw new IllegalStateException("IEC playhead stalled");
                            nextHeadCheck = now + TimeUnit.MILLISECONDS.toNanos(50);
                        }
                    }
                    if (offset == slot.words.length) writtenCycles++;
                } finally { free.add(slot); }
            }
            if (state == State.DRAINING) {
                long deadline = System.nanoTime() + drainNanos;
                while (state == State.DRAINING && sink.playedFrames() < submittedBytes / 16) {
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("IEC EOS drain timed out");
                    Thread.sleep(2);
                }
                synchronized (this) {
                    if (state == State.DRAINING) {
                        if (writtenCycles != receivedCycles) throw new IllegalStateException("MAT EOS lost cycles");
                        state = State.COMPLETED;
                    }
                }
            }
        } catch (InterruptedException e) {
            if (active()) fail("MAT writer interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) { fail(e.toString()); }
        finally {
            if (digest != null) submittedSha256 = hex(digest.digest());
            try { sink.close(); } catch (Exception e) { fail("IEC close failed: " + e); }
            pending.clear(); ready.countDown(); done.countDown();
        }
    }
    public static String hex(byte[] b) {
        StringBuilder out = new StringBuilder(b.length * 2);
        for (byte value : b) out.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return out.toString();
    }
    public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
        return done.await(timeoutMs, TimeUnit.MILLISECONDS) && state == State.COMPLETED;
    }
    public boolean awaitReady(long timeoutMs) throws InterruptedException {
        return ready.await(timeoutMs, TimeUnit.MILLISECONDS) && active();
    }
    public State state() { return state; }
    public String error() { return error; }
    public String submittedSha256() { return submittedSha256; }
    public long submittedBytes() { return submittedBytes; }
    public long receivedCycles() { return receivedCycles; }
    public long writtenCycles() { return writtenCycles; }
    public int peakQueue() { return peakQueue; }
    @Override public void close() {
        synchronized (this) {
            if (state == State.NEW) { state = State.STOPPED; ready.countDown(); done.countDown(); return; }
            if (active()) state = State.STOPPED;
        }
        writer.interrupt();
        // Replay cancellation interrupts its owner. Still join/release the sink,
        // then restore that interrupt instead of letting join abort immediately.
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            while (writer.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { writer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (writer.isAlive()) throw new IllegalStateException("MAT writer did not stop");
    }
}
