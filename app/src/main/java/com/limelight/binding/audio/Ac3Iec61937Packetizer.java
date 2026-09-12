package com.limelight.binding.audio;

import java.util.Arrays;

/** Streaming, bounded AC3-to-IEC61937 packer. No decoding or gain changes.
 * Supports normal-rate 48 kHz AC3 only; E-AC3 has a different burst format.
 * The consumer must finish using the reusable word buffer before returning. */
public final class Ac3Iec61937Packetizer {
    public static final int SAMPLES_PER_FRAME = 1536;
    public static final int BURST_BYTES = 6144;
    public static final int BURST_WORDS = BURST_BYTES / 2;
    private static final int[] BITRATES = {32,40,48,56,64,80,96,112,128,160,192,224,256,320,384,448,512,576,640};
    private final byte[] frame = new byte[3840];
    private final short[] burst = new short[BURST_WORDS];
    private int used;
    private int expected;

    public interface Sink { void write(short[] words); }

    public void reset() { used = 0; expected = 0; }

    /** Handles partial frames and multiple frames in one native callback. */
    public void append(byte[] bytes, int length, Sink sink) {
        if (length < 0 || length > bytes.length) {
            reset();
            throw new IllegalArgumentException("Invalid AC3 callback length");
        }
        int offset = 0;
        try {
            while (offset < length) {
                int target = expected == 0 ? 6 : expected;
                int count = Math.min(target - used, length - offset);
                System.arraycopy(bytes, offset, frame, used, count);
                used += count;
                offset += count;
                if (expected == 0 && used == 6) {
                    if ((frame[0] & 255) != 0x0b || (frame[1] & 255) != 0x77)
                        throw new IllegalArgumentException("Missing AC3 sync word");
                    int fscod = (frame[4] & 255) >>> 6;
                    int frmsizecod = frame[4] & 63;
                    int bsid = (frame[5] & 255) >>> 3;
                    if (fscod != 0 || frmsizecod > 37 || bsid > 8)
                        throw new IllegalArgumentException("IEC61937 mode requires normal-rate 48 kHz AC3");
                    expected = BITRATES[frmsizecod / 2] * 4;
                }
                if (expected != 0 && used == expected) {
                    Arrays.fill(burst, (short) 0);
                    burst[0] = (short) 0xf872;
                    burst[1] = (short) 0x4e1f;
                    burst[2] = (short) (1 | ((frame[5] & 7) << 8)); // AC3 + bsmod
                    burst[3] = (short) (expected * 8); // payload size in bits
                    for (int i = 0; i < expected; i += 2)
                        burst[4 + i / 2] = (short) (((frame[i] & 255) << 8) | (frame[i + 1] & 255));
                    // AudioTrack's short[] API writes these numeric words in native order.
                    sink.write(burst);
                    reset();
                }
            }
        } catch (RuntimeException e) {
            reset();
            throw e;
        }
    }
}
