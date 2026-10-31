package com.limelight.nvstream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Debug-only fixture reader; live network payloads use the production common-c decoder. */
object Ds5HapticsIrExtendedDecoder {
    fun decode(bytes: ByteArray): List<Ds5HapticsIrExtendedFrame> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = mutableListOf<Ds5HapticsIrExtendedFrame>()
        while (b.remaining() > 0) {
            val start = b.position()
            require(b.remaining() >= 104 && b.get(start).toInt() == 2)
            require(b.getShort(start + 2).toInt() == 72 && b.getShort(start + 6).toInt() == 0 && b.getInt(start + 68) == 0)
            val count = b.get(start + 73).toInt() and 255
            val size = 72 + (b.getShort(start + 74).toInt() and 65535)
            require(b.get(start + 72).toInt() == 1 && count <= 8 && size == 104 + count * 20 && size <= b.remaining())
            fun baseLane(at: Int) = Ds5HapticsIrLane(b.getFloat(at), b.getFloat(at + 4),
                b.getFloat(at + 8), b.getFloat(at + 12), b.getFloat(at + 16))
            val base = Ds5HapticsIrFrame(b.getShort(start + 4), b.get(start + 1).toInt() and 255,
                b.getInt(start + 8), b.getLong(start + 12), b.getInt(start + 20),
                baseLane(start + 24), baseLane(start + 44))
            fun continuous(at: Int) = Ds5HapticsIrExtendedLane(b.getFloat(at), b.getFloat(at + 4))
            val events = (0 until count).map { index ->
                val at = start + 104 + index * 20
                Ds5HapticsIrExtendedEvent(b.getInt(at), b.getLong(at + 4), b.getFloat(at + 12),
                    b.getFloat(at + 16))
            }
            frames += Ds5HapticsIrExtendedFrame(base, b.getInt(start + 76), b.getLong(start + 80),
                continuous(start + 88), continuous(start + 96), events)
            b.position(start + size)
        }
        return frames
    }
}
