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

        if (packetLength == EvdevEvent.EVDEV_WRAPPED_EVENT_SIZE) {
            val version = bb.getInt()
            if (version != EvdevEvent.EVDEV_PACKET_VERSION) {
                LimeLog.warning("Unknown EvdevReader packet version: $version")
                return null
            }

            val deviceId = bb.getInt()
            val deviceClass = bb.getInt()
            val eventSize = bb.getInt()
            val absXMin = bb.getInt()
            val absXMax = bb.getInt()
            val absYMin = bb.getInt()
            val absYMax = bb.getInt()
            val absXResolution = bb.getInt()
            val absYResolution = bb.getInt()

            if (eventSize != EvdevEvent.EVDEV_MIN_EVENT_SIZE && eventSize != EvdevEvent.EVDEV_MAX_EVENT_SIZE) {
                LimeLog.warning("Unexpected evdev input_event size: $eventSize")
                return null
            }

            // Throw away the time stamp from the embedded input_event
            if (eventSize == EvdevEvent.EVDEV_MAX_EVENT_SIZE) {
                bb.getLong()
                bb.getLong()
            } else {
                bb.getInt()
                bb.getInt()
            }

            return EvdevEvent(
                bb.getShort(), bb.getShort(), bb.getInt(),
                deviceId, deviceClass,
                absXMin, absXMax, absYMin, absYMax, absXResolution, absYResolution
            )
        } else {
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
}
