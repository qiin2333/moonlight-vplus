package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.binding.input.driver.DualSenseInputReportParser
import com.limelight.binding.input.driver.DualSenseInputState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal enum class DualSenseBluetoothInputDisposition {
    ACCEPTED,
    RESYNCHRONIZED,
    INVALID_LENGTH,
    INVALID_REPORT_ID,
    INVALID_CRC,
    INVALID_PAYLOAD,
    DUPLICATE_SEQUENCE,
    OUT_OF_ORDER_SEQUENCE
}

internal data class DualSenseBluetoothInputResult(
    val disposition: DualSenseBluetoothInputDisposition,
    val state: DualSenseInputState? = null,
    val sequence: Int? = null,
    val missingReports: Int = 0
) {
    val accepted: Boolean
        get() = state != null
}

/** Validates the Bluetooth envelope and feeds the shared DualSense payload parser. */
internal class DualSenseBluetoothInputCodec(
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sequenceResyncTimeoutMs: Long = DEFAULT_SEQUENCE_RESYNC_TIMEOUT_MS
) {
    private var lastSequence: Int? = null
    private var lastAcceptedAtMs = 0L

    init {
        require(sequenceResyncTimeoutMs > 0)
    }

    @Synchronized
    fun decode(report: ByteArray): DualSenseBluetoothInputResult {
        if (report.size != REPORT_SIZE) {
            return rejected(DualSenseBluetoothInputDisposition.INVALID_LENGTH)
        }
        if (report[0].toInt() and 0xFF != REPORT_ID) {
            return rejected(DualSenseBluetoothInputDisposition.INVALID_REPORT_ID)
        }
        if (!hasValidCrc(report)) {
            return rejected(DualSenseBluetoothInputDisposition.INVALID_CRC)
        }

        val sequence = report[COMMON_PAYLOAD_OFFSET + COMMON_SEQUENCE_OFFSET].toInt() and 0xFF
        val nowMs = monotonicTimeMs()
        val previous = lastSequence
        var disposition = DualSenseBluetoothInputDisposition.ACCEPTED
        var missingReports = 0

        if (previous != null) {
            val delta = (sequence - previous) and 0xFF
            val mayResynchronize = nowMs - lastAcceptedAtMs >= sequenceResyncTimeoutMs
            when {
                delta == 0 && !mayResynchronize -> {
                    return rejected(
                        DualSenseBluetoothInputDisposition.DUPLICATE_SEQUENCE,
                        sequence
                    )
                }
                delta > MAX_FORWARD_DELTA && !mayResynchronize -> {
                    return rejected(
                        DualSenseBluetoothInputDisposition.OUT_OF_ORDER_SEQUENCE,
                        sequence
                    )
                }
                mayResynchronize && delta != 1 -> {
                    disposition = DualSenseBluetoothInputDisposition.RESYNCHRONIZED
                }
                else -> missingReports = delta - 1
            }
        }

        val payload = ByteBuffer.wrap(
            report,
            COMMON_PAYLOAD_OFFSET,
            CRC_OFFSET - COMMON_PAYLOAD_OFFSET
        ).slice().order(ByteOrder.LITTLE_ENDIAN)
        val state = DualSenseInputReportParser.parseCommonPayload(payload, sequence)
            ?: return rejected(DualSenseBluetoothInputDisposition.INVALID_PAYLOAD, sequence)

        lastSequence = sequence
        lastAcceptedAtMs = nowMs
        return DualSenseBluetoothInputResult(disposition, state, sequence, missingReports)
    }

    @Synchronized
    fun reset() {
        lastSequence = null
        lastAcceptedAtMs = 0L
    }

    private fun hasValidCrc(report: ByteArray): Boolean {
        val crc = CRC32()
        crc.update(INPUT_CRC_PREFIX)
        crc.update(report, 0, CRC_OFFSET)
        val expected = (report[CRC_OFFSET].toLong() and 0xFF) or
            ((report[CRC_OFFSET + 1].toLong() and 0xFF) shl 8) or
            ((report[CRC_OFFSET + 2].toLong() and 0xFF) shl 16) or
            ((report[CRC_OFFSET + 3].toLong() and 0xFF) shl 24)
        return crc.value == expected
    }

    private fun rejected(
        disposition: DualSenseBluetoothInputDisposition,
        sequence: Int? = null
    ) = DualSenseBluetoothInputResult(disposition, sequence = sequence)

    companion object {
        const val REPORT_SIZE = 78
        const val REPORT_ID = 0x31

        private const val COMMON_PAYLOAD_OFFSET = 2
        private const val COMMON_SEQUENCE_OFFSET = 6
        private const val CRC_OFFSET = REPORT_SIZE - 4
        private const val INPUT_CRC_PREFIX = 0xA1
        private const val MAX_FORWARD_DELTA = 127
        private const val DEFAULT_SEQUENCE_RESYNC_TIMEOUT_MS = 500L
    }
}
