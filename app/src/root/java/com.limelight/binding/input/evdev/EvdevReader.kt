package com.limelight.binding.input.evdev

import com.limelight.LimeLog
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EvdevReader {
    @Throws(IOException::class)
    private fun readAll(input: InputStream, bb: ByteBuffer) {
        val buf = bb.array()
        var offset = 0

        while (offset < buf.size) {
            val ret = input.read(buf, offset, buf.size - offset)
            if (ret <= 0) {
                throw IOException("Read failed: $ret")
            }
            offset += ret
        }
    }

    // Takes a byte buffer to use to read the output into.
    // This buffer MUST be in native byte order and at least
    // EVDEV_MAX_EVENT_SIZE bytes long.
    @JvmStatic
    @Throws(IOException::class)
    fun read(input: InputStream): EvdevEvent? {
        // Read the packet length
        var bb = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        readAll(input, bb)
        val packetLength = bb.getInt()

        if (packetLength < EvdevEvent.EVDEV_MIN_EVENT_SIZE) {
            LimeLog.warning("Short read: $packetLength")
            return null
        }

        // Read the rest of the packet
        bb = ByteBuffer.allocate(packetLength).order(ByteOrder.nativeOrder())
        readAll(input, bb)

        // Throw away the time stamp
        if (packetLength == EvdevEvent.EVDEV_MAX_EVENT_SIZE) {
            bb.getLong()
            bb.getLong()
        } else {
            bb.getInt()
            bb.getInt()
        }

        return EvdevEvent(bb.getShort(), bb.getShort(), bb.getInt())
    }
}
