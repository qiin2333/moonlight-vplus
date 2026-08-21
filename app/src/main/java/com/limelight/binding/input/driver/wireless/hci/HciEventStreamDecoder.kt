package com.limelight.binding.input.driver.wireless.hci

/** Reassembles HCI events for adapters which split interrupt transfers at endpoint boundaries. */
internal class HciEventStreamDecoder {
    private var pending = ByteArray(0)

    @Synchronized
    fun append(bytes: ByteArray): List<HciEventPacket> {
        if (bytes.isEmpty()) {
            return emptyList()
        }

        val combined = ByteArray(pending.size + bytes.size)
        pending.copyInto(combined)
        bytes.copyInto(combined, destinationOffset = pending.size)

        val events = ArrayList<HciEventPacket>()
        var cursor = 0
        while (combined.size - cursor >= EVENT_HEADER_LENGTH) {
            val parameterLength = combined[cursor + 1].toInt() and 0xff
            val eventLength = EVENT_HEADER_LENGTH + parameterLength
            if (combined.size - cursor < eventLength) {
                break
            }
            HciPacketCodec.decodeEvent(combined, cursor, eventLength)?.let(events::add)
            cursor += eventLength
        }

        pending = combined.copyOfRange(cursor, combined.size)
        return events
    }

    @Synchronized
    fun reset() {
        pending = ByteArray(0)
    }

    @Synchronized
    internal fun pendingByteCount(): Int = pending.size

    companion object {
        private const val EVENT_HEADER_LENGTH = 2
    }
}
