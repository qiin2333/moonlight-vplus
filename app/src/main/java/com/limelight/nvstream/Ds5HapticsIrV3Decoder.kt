package com.limelight.nvstream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoder for debug fixture streams made of consecutive, length-prefixed IR v3 wire frames. */
object Ds5HapticsIrV3Decoder {
    fun decode(bytes: ByteArray): List<Ds5HapticsIrV3Frame> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = mutableListOf<Ds5HapticsIrV3Frame>()
        while (buffer.remaining() > 0) {
            val start = buffer.position()
            require(buffer.remaining() >= 64 && buffer.get(start).toInt() == 3)
            val size = buffer.getShort(start + 2).toInt() and 65535
            val count = buffer.get(start + 28).toInt() and 255
            require(count <= 8 && size == 64 + count * 32 && size <= buffer.remaining())
            require(buffer.get(start + 29).toInt() == 2 && buffer.getShort(start + 30).toInt() == 0)
            fun lane(at: Int) = Ds5HapticsIrV3Lane(buffer.getFloat(at), buffer.getFloat(at + 4),
                buffer.getFloat(at + 8), buffer.getFloat(at + 12))
            val events = (0 until count).map { index ->
                val at = start + 64 + index * 32
                require(buffer.getInt(at + 4) == 0)
                Ds5HapticsIrV3Event(buffer.getInt(at), buffer.getLong(at + 8), buffer.getFloat(at + 16),
                    buffer.getFloat(at + 20), buffer.getFloat(at + 24), buffer.getFloat(at + 28))
            }
            frames += Ds5HapticsIrV3Frame(buffer.getShort(start + 4), buffer.get(start + 1).toInt() and 255,
                buffer.getShort(start + 6).toInt() and 65535, buffer.getInt(start + 8), buffer.getInt(start + 12),
                buffer.getLong(start + 16), buffer.getInt(start + 24), lane(start + 32), lane(start + 48), events)
            buffer.position(start + size)
        }
        return frames
    }
}
