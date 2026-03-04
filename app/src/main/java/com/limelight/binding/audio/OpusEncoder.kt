package com.limelight.binding.audio

import com.limelight.LimeLog

class OpusEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrate: Int
) {
    private var nativePtr: Long

    companion object {
        init {
            System.loadLibrary("moonlight-core")
        }

        @JvmStatic
        private external fun nativeInit(sampleRate: Int, channels: Int, bitrate: Int): Long

        @JvmStatic
        private external fun nativeEncode(handle: Long, pcmData: ByteArray, offset: Int, length: Int): ByteArray?

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }

    init {
        nativePtr = nativeInit(sampleRate, channels, bitrate)
        if (nativePtr == 0L) {
            throw IllegalStateException("无法初始化Opus编码器")
        }
    }

    fun encode(pcmData: ByteArray, offset: Int, length: Int): ByteArray? {
        if (nativePtr == 0L) {
            return null
        }
        return nativeEncode(nativePtr, pcmData, offset, length)
    }

    @Synchronized
    fun release() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0
        }
    }

    @Suppress("removal")
    @Throws(Throwable::class)
    protected fun finalize() {
        release()
    }
}
