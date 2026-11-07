package com.limelight.binding.audio;

/** Complete, validated IEC carrier cycles only. The byte array is borrowed until return. */
public interface MatCarrierConsumer {
    boolean offerMatCycle(byte[] bytes, int length, long epoch, long domain, long index, long pts48k);
    /** Input EOS only. Implementations must drain before reporting playback complete. */
    boolean endMatStream(long cycles);
}
