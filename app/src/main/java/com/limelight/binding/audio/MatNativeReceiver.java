package com.limelight.binding.audio;

/** Serial JNI owner. No codec negotiation, PCM path, decoder or MAT_DUMP changes. */
public final class MatNativeReceiver implements AutoCloseable, MatCarrierConsumer {
    static { System.loadLibrary("moonlight-core"); }
    private long handle;
    private boolean pushing;
    public MatNativeReceiver(MatCarrierConsumer consumer) {
        if (consumer == null) throw new IllegalArgumentException("consumer");
        handle = nativeOpen(consumer);
        if (handle == 0) throw new IllegalStateException("MAT JNI receiver allocation failed");
    }
    public synchronized boolean pushPacket(byte[] packet, int length) {
        if (handle == 0 || pushing) return false;
        pushing = true;
        try { return nativePushPacket(handle, packet, length); } finally { pushing = false; }
    }
    @Override public synchronized boolean offerMatCycle(byte[] bytes, int length, long epoch,
                                                        long domain, long index, long pts48k) {
        if (handle == 0 || pushing) return false;
        pushing = true;
        try { return nativePushCycle(handle, bytes, length, epoch, domain, index, pts48k); }
        finally { pushing = false; }
    }
    @Override public synchronized boolean endMatStream(long cycles) {
        if (handle == 0 || pushing) return false;
        pushing = true;
        try { return nativeEnd(handle, cycles); } finally { pushing = false; }
    }
    @Override public synchronized void close() {
        if (pushing) throw new IllegalStateException("Cannot close inside a MAT callback");
        if (handle != 0) { nativeClose(handle); handle = 0; }
    }
    private static native long nativeOpen(MatCarrierConsumer consumer);
    private static native boolean nativePushPacket(long handle, byte[] bytes, int length);
    private static native boolean nativePushCycle(long handle, byte[] bytes, int length,
                                                  long epoch, long domain, long index, long pts48k);
    private static native boolean nativeEnd(long handle, long cycles);
    private static native void nativeClose(long handle);
}
