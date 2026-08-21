package com.limelight.binding.input.driver.wireless.hci

internal data class HciAclEnqueueResult(
    val accepted: Boolean,
    val transferFailed: Boolean = false
)

internal enum class HciAclCompletionEventResult {
    NOT_HANDLED,
    HANDLED,
    MALFORMED,
    TRANSFER_FAILED
}

/** Applies controller ACL MTU fragmentation and Number Of Completed Packets backpressure. */
internal class HciAclOutputScheduler(
    private val maxQueuedPackets: Int = DEFAULT_MAX_QUEUED_PACKETS
) {
    private val pendingPackets = ArrayDeque<HciAclPacket>()
    private var maxPayloadLength = 0
    private var totalCredits = 0
    private var availableCredits = 0

    init {
        require(maxQueuedPackets > 0)
    }

    @Synchronized
    fun configure(maxPayloadLength: Int, packetCredits: Int): Boolean {
        if (maxPayloadLength !in 1..HciPacketCodec.MAX_ACL_PAYLOAD ||
            packetCredits !in 1..MAX_PACKET_CREDITS || pendingPackets.isNotEmpty()
        ) {
            return false
        }
        this.maxPayloadLength = maxPayloadLength
        totalCredits = packetCredits
        availableCredits = packetCredits
        return true
    }

    @Synchronized
    fun enqueue(
        packet: HciAclPacket,
        sender: (HciAclPacket) -> Boolean
    ): HciAclEnqueueResult {
        if (maxPayloadLength == 0 ||
            packet.connectionHandle !in 0..0x0fff ||
            packet.packetBoundaryFlag !in 0..0x03 ||
            packet.broadcastFlag !in 0..0x03
        ) {
            return HciAclEnqueueResult(accepted = false)
        }

        val fragments = fragment(packet)
        if (fragments.size > maxQueuedPackets - pendingPackets.size) {
            return HciAclEnqueueResult(accepted = false)
        }
        pendingPackets.addAll(fragments)
        return if (drain(sender)) {
            HciAclEnqueueResult(accepted = true)
        } else {
            HciAclEnqueueResult(accepted = false, transferFailed = true)
        }
    }

    @Synchronized
    fun onEvent(
        event: HciEventPacket,
        sender: (HciAclPacket) -> Boolean
    ): HciAclCompletionEventResult {
        if (event.eventCode != NUMBER_OF_COMPLETED_PACKETS_EVENT_CODE) {
            return HciAclCompletionEventResult.NOT_HANDLED
        }
        if (event.parameters.isEmpty()) {
            return HciAclCompletionEventResult.MALFORMED
        }

        val handleCount = event.parameters[0].toInt() and 0xff
        if (event.parameters.size != 1 + handleCount * COMPLETED_PACKETS_ENTRY_LENGTH) {
            return HciAclCompletionEventResult.MALFORMED
        }

        var completedPackets = 0
        var offset = 1
        repeat(handleCount) {
            completedPackets += littleEndianUnsignedShort(event.parameters, offset + 2)
            offset += COMPLETED_PACKETS_ENTRY_LENGTH
        }
        availableCredits = (availableCredits + completedPackets).coerceAtMost(totalCredits)
        return if (drain(sender)) {
            HciAclCompletionEventResult.HANDLED
        } else {
            HciAclCompletionEventResult.TRANSFER_FAILED
        }
    }

    @Synchronized
    fun reset() {
        pendingPackets.clear()
        maxPayloadLength = 0
        totalCredits = 0
        availableCredits = 0
    }

    @Synchronized
    internal fun queuedPacketCount(): Int = pendingPackets.size

    @Synchronized
    internal fun availablePacketCredits(): Int = availableCredits

    private fun fragment(packet: HciAclPacket): List<HciAclPacket> {
        if (packet.payload.size <= maxPayloadLength) {
            return listOf(packet)
        }

        val fragments = ArrayList<HciAclPacket>(
            (packet.payload.size + maxPayloadLength - 1) / maxPayloadLength
        )
        var offset = 0
        while (offset < packet.payload.size) {
            val end = (offset + maxPayloadLength).coerceAtMost(packet.payload.size)
            fragments.add(
                HciAclPacket(
                    connectionHandle = packet.connectionHandle,
                    packetBoundaryFlag = if (offset == 0) {
                        packet.packetBoundaryFlag
                    } else {
                        CONTINUING_FRAGMENT_PACKET_BOUNDARY_FLAG
                    },
                    broadcastFlag = packet.broadcastFlag,
                    payload = packet.payload.copyOfRange(offset, end)
                )
            )
            offset = end
        }
        return fragments
    }

    private fun drain(sender: (HciAclPacket) -> Boolean): Boolean {
        while (availableCredits > 0 && pendingPackets.isNotEmpty()) {
            val packet = pendingPackets.first()
            if (!sender(packet)) {
                return false
            }
            pendingPackets.removeFirst()
            availableCredits--
        }
        return true
    }

    private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    companion object {
        private const val NUMBER_OF_COMPLETED_PACKETS_EVENT_CODE = 0x13
        private const val COMPLETED_PACKETS_ENTRY_LENGTH = 4
        private const val CONTINUING_FRAGMENT_PACKET_BOUNDARY_FLAG = 0x01
        private const val MAX_PACKET_CREDITS = 0xffff
        private const val DEFAULT_MAX_QUEUED_PACKETS = 256
    }
}
