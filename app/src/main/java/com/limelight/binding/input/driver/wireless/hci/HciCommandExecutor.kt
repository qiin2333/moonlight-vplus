package com.limelight.binding.input.driver.wireless.hci

internal enum class HciCommandCompletionType {
    COMMAND_COMPLETE,
    COMMAND_STATUS
}

internal enum class HciCommandFailureCode {
    TIMEOUT
}

internal sealed class HciCommandResult {
    data class Completed(
        val opcode: Int,
        val type: HciCommandCompletionType,
        val controllerStatus: Int?,
        val returnParameters: ByteArray
    ) : HciCommandResult()

    data class Failed(
        val opcode: Int,
        val code: HciCommandFailureCode
    ) : HciCommandResult()
}

/**
 * Serializes HCI commands for a controller session and correlates Command Complete/Status events.
 *
 * Keeping this session-wide prevents bootstrap, discovery, and HID setup from racing commands on
 * controllers which expose only one command credit.
 */
internal class HciCommandExecutor(
    private val sendCommand: (HciCommandPacket) -> Boolean,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS
) {
    private data class PendingCommand(
        val opcode: Int,
        val deadlineMs: Long,
        val callback: (HciCommandResult) -> Unit
    )

    private var pending: PendingCommand? = null
    private var closed = false

    init {
        require(commandTimeoutMs > 0)
    }

    fun submit(packet: HciCommandPacket, callback: (HciCommandResult) -> Unit): Boolean {
        synchronized(this) {
            if (closed || pending != null) {
                return false
            }
            pending = PendingCommand(
                opcode = packet.opcode,
                deadlineMs = monotonicTimeMs() + commandTimeoutMs,
                callback = callback
            )
            if (runCatching { sendCommand(packet) }.getOrDefault(false)) {
                return true
            }
            pending = null
            return false
        }
    }

    fun onEvent(event: HciEventPacket): Boolean {
        val completed = parseCompletion(event) ?: return false
        val callback: (HciCommandResult) -> Unit
        synchronized(this) {
            val current = pending ?: return false
            if (current.opcode != completed.opcode) {
                return false
            }
            pending = null
            callback = current.callback
        }
        runCatching { callback(completed) }
        return true
    }

    fun checkTimeout(nowMs: Long = monotonicTimeMs()): Boolean {
        val timedOut: PendingCommand
        synchronized(this) {
            val current = pending ?: return false
            if (nowMs < current.deadlineMs) {
                return false
            }
            pending = null
            timedOut = current
        }
        runCatching {
            timedOut.callback(
                HciCommandResult.Failed(timedOut.opcode, HciCommandFailureCode.TIMEOUT)
            )
        }
        return true
    }

    @Synchronized
    fun hasPendingCommand(): Boolean = pending != null

    @Synchronized
    fun close() {
        closed = true
        pending = null
    }

    private fun parseCompletion(event: HciEventPacket): HciCommandResult.Completed? {
        return when (event.eventCode) {
            COMMAND_COMPLETE_EVENT_CODE -> {
                if (event.parameters.size < COMMAND_COMPLETE_HEADER_LENGTH) {
                    null
                } else {
                    val opcode = littleEndianUnsignedShort(event.parameters, 1)
                    val returnParameters = event.parameters.copyOfRange(
                        COMMAND_COMPLETE_HEADER_LENGTH,
                        event.parameters.size
                    )
                    HciCommandResult.Completed(
                        opcode = opcode,
                        type = HciCommandCompletionType.COMMAND_COMPLETE,
                        controllerStatus = returnParameters.firstOrNull()?.toInt()?.and(0xff),
                        returnParameters = returnParameters
                    )
                }
            }
            COMMAND_STATUS_EVENT_CODE -> {
                if (event.parameters.size != COMMAND_STATUS_PARAMETER_LENGTH) {
                    null
                } else {
                    HciCommandResult.Completed(
                        opcode = littleEndianUnsignedShort(event.parameters, 2),
                        type = HciCommandCompletionType.COMMAND_STATUS,
                        controllerStatus = event.parameters[0].toInt() and 0xff,
                        returnParameters = ByteArray(0)
                    )
                }
            }
            else -> null
        }
    }

    private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    companion object {
        private const val COMMAND_COMPLETE_EVENT_CODE = 0x0e
        private const val COMMAND_STATUS_EVENT_CODE = 0x0f
        private const val COMMAND_COMPLETE_HEADER_LENGTH = 3
        private const val COMMAND_STATUS_PARAMETER_LENGTH = 4
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 3000L
    }
}
