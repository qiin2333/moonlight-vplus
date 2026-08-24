package com.limelight.binding.input.driver.wireless.hci

/**
 * Standard Bluetooth-over-USB transport with one serialized completion reader and serialized writes.
 * Adapter quirks belong in a profile layer above this class, not in packet framing.
 */
internal class GenericHciUsbTransport(
    private val io: HciUsbIo,
    override val profile: HciUsbAdapterProfile,
    private val behavior: HciUsbAdapterBehavior = HciUsbAdapterBehaviorResolver.resolve(
        profile,
        compositeDevice = false
    )
) : HciTransport {
    private val lifecycleLock = Any()
    private val outputLock = Any()
    private val callbackLock = Any()
    private val aclDecoder = HciAclStreamDecoder()
    private val eventDecoder = HciEventStreamDecoder()
    private val aclOutputScheduler = HciAclOutputScheduler()

    @Volatile
    override var state: HciTransportState = HciTransportState.CLOSED
        private set

    @Volatile
    private var listener: HciPacketListener? = null

    @Volatile
    private var stopRequested = false

    private var readerThread: Thread? = null

    override fun setListener(listener: HciPacketListener?) {
        this.listener = listener
    }

    override fun open(): Boolean {
        synchronized(lifecycleLock) {
            if (state != HciTransportState.CLOSED) {
                return state == HciTransportState.OPEN
            }
            state = HciTransportState.OPENING
            stopRequested = false
        }

        if (!runCatching { io.open() }.getOrDefault(false)) {
            reportFailure(
                HciTransportFailure(
                    HciTransportErrorCode.INTERFACE_CLAIM_FAILED,
                    "Unable to open the USB HCI interface"
                )
            )
            runCatching { io.finishClose() }
            return false
        }

        synchronized(lifecycleLock) {
            if (stopRequested) {
                io.close()
                io.finishClose()
                state = HciTransportState.CLOSED
                return false
            }
            state = HciTransportState.OPEN
            readerThread = Thread(::readLoop, "hci-usb-reader").also(Thread::start)
        }
        return true
    }

    override fun sendCommand(packet: HciCommandPacket): Boolean {
        val encoded = runCatching { HciPacketCodec.encodeCommand(packet) }.getOrElse { return false }
        return send(
            failureCode = HciTransportErrorCode.CONTROL_TRANSFER_FAILED,
            failureMessage = "USB HCI command transfer failed"
        ) { io.sendCommand(encoded) }
    }

    override fun configureAclOutput(maxPayloadLength: Int, packetCredits: Int): Boolean {
        return synchronized(outputLock) {
            state == HciTransportState.OPEN && !stopRequested &&
                aclOutputScheduler.configure(maxPayloadLength, packetCredits)
        }
    }

    override fun sendAcl(packet: HciAclPacket): Boolean {
        val result = synchronized(outputLock) {
            if (state != HciTransportState.OPEN || stopRequested) {
                return false
            }
            runCatching {
                aclOutputScheduler.enqueue(packet, ::sendAclPacket)
            }.getOrElse {
                HciAclEnqueueResult(accepted = false, transferFailed = true)
            }
        }
        if (result.transferFailed) {
            reportFailure(
                HciTransportFailure(
                    HciTransportErrorCode.ACL_TRANSFER_FAILED,
                    "USB HCI ACL transfer failed"
                )
            )
        }
        return result.accepted
    }

    override fun flushAcl(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        do {
            val empty = synchronized(outputLock) {
                aclOutputScheduler.queuedPacketCount() == 0
            }
            if (empty) return true
            if (System.nanoTime() >= deadlineNs) return false
            try {
                Thread.sleep(ACL_FLUSH_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        } while (true)
    }

    override fun close() {
        val thread: Thread?
        synchronized(lifecycleLock) {
            if (state == HciTransportState.CLOSED || state == HciTransportState.CLOSING) {
                return
            }
            stopRequested = true
            state = HciTransportState.CLOSING
            thread = readerThread
        }

        synchronized(outputLock) {
            runCatching { io.close() }
        }

        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(READER_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (thread == null || !thread.isAlive) {
            runCatching { io.finishClose() }
        }

        synchronized(lifecycleLock) {
            readerThread = null
            aclDecoder.reset()
            eventDecoder.reset()
            aclOutputScheduler.reset()
            state = HciTransportState.CLOSED
        }
    }

    private fun send(
        failureCode: HciTransportErrorCode,
        failureMessage: String,
        operation: () -> Boolean
    ): Boolean {
        synchronized(outputLock) {
            if (state != HciTransportState.OPEN || stopRequested) {
                return false
            }
            if (runCatching(operation).getOrDefault(false)) {
                return true
            }
        }

        reportFailure(HciTransportFailure(failureCode, failureMessage))
        return false
    }

    private fun readLoop() {
        try {
            while (!stopRequested) {
                when (val result = runCatching { io.read() }.getOrElse {
                    HciUsbReadResult.Failure(
                        HciTransportFailure(
                            HciTransportErrorCode.EVENT_TRANSFER_FAILED,
                            "USB HCI read failed",
                            it
                        )
                    )
                }) {
                    HciUsbReadResult.Timeout -> continue
                    HciUsbReadResult.Closed -> return
                    is HciUsbReadResult.Failure -> {
                        reportFailure(result.failure)
                        return
                    }
                    is HciUsbReadResult.Packet -> {
                        if (!dispatch(result)) {
                            return
                        }
                    }
                }
            }
        } finally {
            runCatching { io.finishClose() }
        }
    }

    private fun dispatch(result: HciUsbReadResult.Packet): Boolean {
        return when (result.channel) {
            HciUsbInputChannel.EVENT -> {
                val events = if (behavior.reassembleEventTransfers) {
                    eventDecoder.append(result.bytes)
                } else {
                    val event = HciPacketCodec.decodeEvent(result.bytes)
                    if (event == null) emptyList() else listOf(event)
                }
                if (events.isEmpty() && !behavior.reassembleEventTransfers) {
                    reportMalformedEvent()
                    false
                } else {
                    for (event in events) {
                        if (!dispatchEvent(event)) {
                            return false
                        }
                    }
                    true
                }
            }
            HciUsbInputChannel.ACL -> {
                val decoded = aclDecoder.append(result.bytes)
                if (decoded.protocolError) {
                    reportFailure(
                        HciTransportFailure(
                            HciTransportErrorCode.MALFORMED_ACL,
                            "Malformed HCI ACL stream"
                        )
                    )
                    false
                } else {
                    for (packet in decoded.packets) {
                        val delivered = synchronized(callbackLock) {
                            if (state != HciTransportState.OPEN || stopRequested) {
                                return@synchronized false
                            }
                            runCatching { listener?.onAcl(packet) }
                            true
                        }
                        if (!delivered) {
                            return false
                        }
                    }
                    true
                }
            }
        }
    }

    private fun dispatchEvent(event: HciEventPacket): Boolean {
        val completionResult = synchronized(outputLock) {
            if (state != HciTransportState.OPEN || stopRequested) {
                return@synchronized HciAclCompletionEventResult.NOT_HANDLED
            }
            aclOutputScheduler.onEvent(event, ::sendAclPacket)
        }
        when (completionResult) {
            HciAclCompletionEventResult.MALFORMED -> {
                reportMalformedEvent()
                return false
            }
            HciAclCompletionEventResult.TRANSFER_FAILED -> {
                reportFailure(
                    HciTransportFailure(
                        HciTransportErrorCode.ACL_TRANSFER_FAILED,
                        "USB HCI ACL transfer failed while releasing queued packets"
                    )
                )
                return false
            }
            HciAclCompletionEventResult.HANDLED -> return true
            HciAclCompletionEventResult.NOT_HANDLED -> Unit
        }

        return synchronized(callbackLock) {
            if (state != HciTransportState.OPEN || stopRequested) {
                return@synchronized false
            }
            runCatching { listener?.onEvent(event) }
            true
        }
    }

    private fun sendAclPacket(packet: HciAclPacket): Boolean {
        val encoded = HciPacketCodec.encodeAcl(packet)
        return io.sendAcl(encoded)
    }

    private fun reportMalformedEvent() {
        reportFailure(
            HciTransportFailure(
                HciTransportErrorCode.MALFORMED_EVENT,
                "Malformed HCI event packet"
            )
        )
    }

    private fun reportFailure(failure: HciTransportFailure) {
        var notify = false
        synchronized(lifecycleLock) {
            if (state != HciTransportState.FAILED &&
                state != HciTransportState.CLOSING &&
                state != HciTransportState.CLOSED
            ) {
                state = HciTransportState.FAILED
                stopRequested = true
                notify = true
            }
        }
        if (!notify) {
            return
        }

        synchronized(outputLock) {
            runCatching { io.close() }
        }
        if (readerThread == null) {
            runCatching { io.finishClose() }
        }
        synchronized(callbackLock) {
            runCatching { listener?.onTransportFailure(failure) }
        }
    }

    companion object {
        private const val READER_JOIN_TIMEOUT_MS = 1000L
        private const val ACL_FLUSH_POLL_MS = 5L
    }
}
