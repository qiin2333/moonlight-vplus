package com.limelight.nvstream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Debug fixture reader; live network payloads use the production common-c decoder. */
object Ds5HapticsIrExtendedDecoder {
    fun decode(bytes: ByteArray): List<Ds5HapticsIrExtendedFrame> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = mutableListOf<Ds5HapticsIrExtendedFrame>()
        while (b.remaining() > 0) {
            val start = b.position()
            require(b.remaining() >= 112 && b.get(start).toInt() == 2)
            require(b.getShort(start + 2).toInt() == 72 && b.getShort(start + 6).toInt() == 0 && b.getInt(start + 68) == 0)
            val count = b.get(start + 73).toInt() and 255
            val size = 72 + (b.getShort(start + 74).toInt() and 65535)
            require(b.get(start + 72).toInt() == 1 && count <= 8 && size == 112 + count * 32 && size <= b.remaining())
            require(b.getShort(start + 78).toInt() == 0 && b.getInt(start + 108) == 0)
            fun baseLane(at: Int) = Ds5HapticsIrLane(b.getFloat(at), b.getFloat(at + 4),
                b.getFloat(at + 8), b.getFloat(at + 12), b.getFloat(at + 16))
            val base = Ds5HapticsIrFrame(b.getShort(start + 4), b.get(start + 1).toInt() and 255,
                b.getInt(start + 8), b.getLong(start + 12), b.getInt(start + 20),
                baseLane(start + 24), baseLane(start + 44))
            fun continuous(at: Int) = Ds5HapticsIrExtendedLane(b.getFloat(at), b.getFloat(at + 4))
            val events = (0 until count).map { index ->
                val at = start + 112 + index * 32
                require(b.getInt(at + 4) == 0)
                Ds5HapticsIrExtendedEvent(b.getInt(at), b.getLong(at + 8), b.getFloat(at + 16),
                    b.getFloat(at + 20), b.getFloat(at + 24), b.getFloat(at + 28))
            }
            frames += Ds5HapticsIrExtendedFrame(base, b.getShort(start + 76).toInt() and 65535,
                b.getInt(start + 80), b.getLong(start + 84), continuous(start + 92), continuous(start + 100), events)
            b.position(start + size)
        }
        return frames
    }
}
