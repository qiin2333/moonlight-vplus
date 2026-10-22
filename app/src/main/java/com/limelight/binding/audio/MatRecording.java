package com.limelight.binding.audio;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, fully SHA-verified recording. No output is opened while reading untrusted input. */
public final class MatRecording {
    public static final int VERIFIED_CYCLES = 178;
    public static final String VERIFIED_SHA256 = "79cd69e33adcd1bcffe95673564b6fc727a7aaed7370745d43ec269925a492f6";
    private static final AtomicLong EPOCH = new AtomicLong(1);
    private final byte[] bytes;
    private MatRecording(byte[] bytes) { this.bytes = bytes; }
    public static MatRecording readVerified(InputStream input) throws IOException {
        byte[] bytes = new byte[VERIFIED_CYCLES * MatCarrierPlayer.CYCLE_BYTES];
        int offset = 0;
        while (offset < bytes.length) {
            int n = input.read(bytes, offset, bytes.length - offset);
            if (n <= 0) throw new IOException("Truncated MAT recording or input made no progress");
            offset += n;
        }
        if (input.read() != -1) throw new IOException("Oversized MAT recording");
        try {
            String hash = MatCarrierPlayer.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!VERIFIED_SHA256.equals(hash)) throw new IOException("MAT recording SHA256 mismatch: " + hash);
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        for (int i = 0; i < VERIFIED_CYCLES; i++)
            if (!MatCarrierPlayer.validCarrier(bytes, i * MatCarrierPlayer.CYCLE_BYTES, MatCarrierPlayer.CYCLE_BYTES))
                throw new IOException("Invalid MAT carrier cycle " + i);
        return new MatRecording(bytes);
    }
    /** Absolute 20ms cadence absorbs timer jitter; a full-cycle stall rebases instead of bursting. */
    public void replay(MatCarrierConsumer consumer) throws IOException {
        byte[] cycle = new byte[MatCarrierPlayer.CYCLE_BYTES];
        long epoch = EPOCH.getAndIncrement(), deadline = System.nanoTime();
        for (int i = 0; i < VERIFIED_CYCLES; i++) {
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("MAT replay cancelled");
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    try { Thread.sleep(remaining / 1_000_000, (int)(remaining % 1_000_000)); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new IOException("MAT replay cancelled", e);
                    }
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("MAT replay cancelled");
            long started = System.nanoTime();
            System.arraycopy(bytes, i * cycle.length, cycle, 0, cycle.length);
            if (!consumer.offerMatCycle(cycle, cycle.length, epoch, 1, i, i * 960L))
                throw new IOException("MAT consumer rejected cycle " + i);
            deadline = (started - deadline >= 20_000_000 ? started : deadline) + 20_000_000;
        }
        if (!consumer.endMatStream(VERIFIED_CYCLES)) throw new IOException("MAT consumer rejected EOS");
    }
}
