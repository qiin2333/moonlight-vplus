package com.limelight.binding.audio;

import com.limelight.LimeLog;

public class OpusEncoder {
    private long nativePtr;
    private final int sampleRate;
    private final int channels;
    private final int bitrate;
    
    static {
        System.loadLibrary("moonlight-core");
    }
    
    public OpusEncoder(int sampleRate, int channels, int bitrate) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitrate = bitrate;
        
        nativePtr = nativeInit(sampleRate, channels, bitrate);
        if (nativePtr == 0) {
            throw new IllegalStateException("无法初始化Opus编码器");
        }
    }
    
    public byte[] encode(byte[] pcmData, int offset, int length) {
        if (nativePtr == 0) {
            return null;
        }
        
        return nativeEncode(nativePtr, pcmData, offset, length);
    }
    
    public synchronized void release() {
        if (nativePtr != 0) {
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }
    
    // 这些方法需要在原生代码中实现
    private static native long nativeInit(int sampleRate, int channels, int bitrate);
    private static native byte[] nativeEncode(long handle, byte[] pcmData, int offset, int length);
    private static native void nativeDestroy(long handle);
}
