package com.limelight.binding.input.driver.wireless.hci

/**
 * Applies optional, post-HID radio tuning without making controller readiness depend on it.
 *
 * Some controllers enter BR/EDR low-power modes after setup. Tuning is deliberately armed only
 * after a valid HID input proves that pairing, encryption, L2CAP, and HIDP are already healthy.
 */
internal class HciLiveLinkTuner(
    private val submitCommand: (
        HciCommandPacket,
        (HciCommandResult) -> Unit
    ) -> Boolean,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @Volatile
    var state = HciLiveLinkTuningState.IDLE
        private set

    private var connectionHandle: Int? = null
    private var commandPending = false
    private var linkPolicyAttempted = false
    private var supervisionTimeoutAttempted = false
    private var exitSniffRequested = false
    private var nextCommandAtMs = 0L
    private var lastExitSniffAttemptMs: Long? = null
    private var generation = 0L

    @Synchronized
    fun onFirstValidInput(handle: Int) {
        if (state == HciLiveLinkTuningState.CLOSED || connectionHandle != null) return
        require(handle in 0..HCI_CONNECTION_HANDLE_MASK)
        connectionHandle = handle
        state = HciLiveLinkTuningState.TUNING
        pump()
    }

    /** Handles only a Mode Change event for the active, proven HID link. */
    @Synchronized
    fun onEvent(packet: HciEventPacket): Boolean {
        if (packet.eventCode != MODE_CHANGE_EVENT || packet.parameters.size < 4) return false
        val handle = ((packet.parameters[1].toInt() and 0xff) or
            ((packet.parameters[2].toInt() and 0xff) shl 8)) and HCI_CONNECTION_HANDLE_MASK
        if (handle != connectionHandle) return false
        if ((packet.parameters[0].toInt() and 0xff) != HCI_SUCCESS) return true

        val mode = packet.parameters[3].toInt() and 0xff
        exitSniffRequested = mode != ACTIVE_MODE
        pump()
        return true
    }

    @Synchronized
    fun checkProgress() {
        pump()
    }

    @Synchronized
    fun onLinkDisconnected(handle: Int) {
        if (handle == connectionHandle) reset()
    }

    @Synchronized
    fun close() {
        reset()
        state = HciLiveLinkTuningState.CLOSED
    }

    private fun pump() {
        val handle = connectionHandle ?: return
        if (state == HciLiveLinkTuningState.CLOSED || commandPending) return
        val now = monotonicTimeMs()
        if (now < nextCommandAtMs) return

        when {
            !linkPolicyAttempted -> submitOptional(
                HciOpcodes.WRITE_LINK_POLICY_SETTINGS,
                handleParameters(handle) + byteArrayOf(0x00, 0x00)
            ) {
                linkPolicyAttempted = true
                nextCommandAtMs = monotonicTimeMs() + POST_CONNECT_COMMAND_GAP_MS
            }

            !supervisionTimeoutAttempted -> submitOptional(
                HciOpcodes.WRITE_LINK_SUPERVISION_TIMEOUT,
                handleParameters(handle) + byteArrayOf(0x00, 0x50)
            ) {
                supervisionTimeoutAttempted = true
                state = HciLiveLinkTuningState.TUNED
            }

            exitSniffRequested && mayRetryExitSniff(now) -> submitOptional(
                HciOpcodes.EXIT_SNIFF_MODE,
                handleParameters(handle)
            ) {
                exitSniffRequested = false
                lastExitSniffAttemptMs = monotonicTimeMs()
                state = HciLiveLinkTuningState.TUNED
            }
        }
    }

    private fun submitOptional(opcode: Int, parameters: ByteArray, afterAttempt: () -> Unit) {
        val callbackGeneration = generation
        commandPending = true
        val accepted = submitCommand(HciCommandPacket(opcode, parameters)) {
            synchronized(this) {
                if (generation != callbackGeneration) return@synchronized
                commandPending = false
                afterAttempt()
                pump()
            }
        }
        if (!accepted) commandPending = false
    }

    private fun mayRetryExitSniff(now: Long): Boolean {
        val previous = lastExitSniffAttemptMs ?: return true
        return now - previous >= EXIT_SNIFF_RETRY_MS
    }

    private fun reset() {
        generation++
        connectionHandle = null
        commandPending = false
        linkPolicyAttempted = false
        supervisionTimeoutAttempted = false
        exitSniffRequested = false
        nextCommandAtMs = 0L
        lastExitSniffAttemptMs = null
        state = HciLiveLinkTuningState.IDLE
    }

    private fun handleParameters(handle: Int): ByteArray =
        byteArrayOf(handle.toByte(), (handle ushr 8).toByte())

    companion object {
        private const val MODE_CHANGE_EVENT = 0x14
        private const val HCI_SUCCESS = 0x00
        private const val ACTIVE_MODE = 0x00
        private const val HCI_CONNECTION_HANDLE_MASK = 0x0fff
        private const val POST_CONNECT_COMMAND_GAP_MS = 500L
        private const val EXIT_SNIFF_RETRY_MS = 2_000L
    }
}

internal enum class HciLiveLinkTuningState {
    IDLE,
    TUNING,
    TUNED,
    CLOSED
}
