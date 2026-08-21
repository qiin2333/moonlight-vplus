package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.binding.input.driver.DualSenseAdaptiveTriggerEffect
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

internal data class DualSenseBluetoothOutputSnapshot(
    val validFlag0: Int,
    val validFlag1: Int,
    val validFlag2: Int,
    val motorRight: Int,
    val motorLeft: Int,
    val rightTriggerType: Byte,
    val rightTrigger: ByteArray,
    val leftTriggerType: Byte,
    val leftTrigger: ByteArray,
    val muteLed: Int,
    val playerLeds: Int,
    val lightbarRed: Int,
    val lightbarGreen: Int,
    val lightbarBlue: Int
)

internal object DualSenseBluetoothOutputEncoder {
    const val REPORT_SIZE = 78
    const val REPORT_ID = 0x31

    fun encode(snapshot: DualSenseBluetoothOutputSnapshot, sequence: Int): ByteArray {
        require(sequence in 0..15)
        require(snapshot.rightTrigger.size == DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE)
        require(snapshot.leftTrigger.size == DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE)

        return ByteArray(REPORT_SIZE).apply {
            this[0] = REPORT_ID.toByte()
            this[1] = (sequence shl 4).toByte()
            this[2] = OUTPUT_TAG
            this[3] = snapshot.validFlag0.toByte()
            this[4] = snapshot.validFlag1.toByte()
            this[5] = snapshot.motorRight.toByte()
            this[6] = snapshot.motorLeft.toByte()
            this[11] = snapshot.muteLed.toByte()
            this[13] = snapshot.rightTriggerType
            snapshot.rightTrigger.copyInto(this, 14)
            this[24] = snapshot.leftTriggerType
            snapshot.leftTrigger.copyInto(this, 25)
            this[41] = snapshot.validFlag2.toByte()
            this[46] = snapshot.playerLeds.toByte()
            this[47] = snapshot.lightbarRed.toByte()
            this[48] = snapshot.lightbarGreen.toByte()
            this[49] = snapshot.lightbarBlue.toByte()
            writeCrc(this)
        }
    }

    fun neutral(sequence: Int): ByteArray = encode(
        DualSenseBluetoothOutputSnapshot(
            validFlag0 = VALID_COMPATIBLE_RUMBLE or DualSenseAdaptiveTriggerEffect.BOTH_FLAGS,
            validFlag1 = 0,
            validFlag2 = 0,
            motorRight = 0,
            motorLeft = 0,
            rightTriggerType = DualSenseAdaptiveTriggerEffect.TYPE_OFF,
            rightTrigger = ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE),
            leftTriggerType = DualSenseAdaptiveTriggerEffect.TYPE_OFF,
            leftTrigger = ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE),
            muteLed = 0,
            playerLeds = 0,
            lightbarRed = 0,
            lightbarGreen = 0,
            lightbarBlue = 0
        ),
        sequence
    )

    /**
     * Releases the Bluetooth startup animation before regular RGB updates.
     * Sony's Linux driver sends this as a distinct one-shot report so the
     * controller will accept later lightbar programming.
     */
    fun lightbarSetup(sequence: Int): ByteArray = ByteArray(REPORT_SIZE).apply {
        this[0] = REPORT_ID.toByte()
        this[1] = (sequence shl 4).toByte()
        this[2] = OUTPUT_TAG
        this[41] = VALID_LIGHTBAR_SETUP.toByte()
        this[44] = LIGHTBAR_SETUP_LIGHT_OUT.toByte()
        writeCrc(this)
    }

    private fun writeCrc(report: ByteArray) {
        val crc = CRC32()
        crc.update(OUTPUT_CRC_PREFIX)
        crc.update(report, 0, CRC_OFFSET)
        val value = crc.value
        for (index in 0 until 4) {
            report[CRC_OFFSET + index] = (value ushr (index * 8)).toByte()
        }
    }

    const val VALID_COMPATIBLE_RUMBLE = 0x03
    const val VALID_LIGHTBAR = 0x04
    const val VALID_MUTE_LED = 0x01
    const val VALID_PLAYER_LEDS = 0x10

    private const val VALID_LIGHTBAR_SETUP = 0x02
    private const val LIGHTBAR_SETUP_LIGHT_OUT = 0x02
    private const val OUTPUT_TAG: Byte = 0x10
    private const val OUTPUT_CRC_PREFIX = 0xA2
    private const val CRC_OFFSET = REPORT_SIZE - 4
}

internal enum class DualSenseBluetoothOutputEvent {
    SUBMITTED,
    SENT,
    COALESCED,
    FAILED
}

/** Coalesces output state and serializes all Bluetooth report transmission. */
internal class DualSenseBluetoothOutputWriter(
    private val sendReport: (ByteArray) -> Boolean,
    private val onSendFailure: () -> Unit = {},
    private val onOutputEvent: (DualSenseBluetoothOutputEvent) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DualSenseBtOutput").apply { isDaemon = true }
    }
) : Closeable {
    private class MutableState {
        var validFlag0 = 0
        var validFlag1 = 0
        var validFlag2 = 0
        var motorRight = 0
        var motorLeft = 0
        var rightTriggerType = DualSenseAdaptiveTriggerEffect.TYPE_OFF
        var rightTrigger = ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE)
        var leftTriggerType = DualSenseAdaptiveTriggerEffect.TYPE_OFF
        var leftTrigger = ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE)
        var muteLed = 0
        var playerLeds = 0
        var lightbarRed = 0
        var lightbarGreen = 0
        var lightbarBlue = 0

        fun snapshot(): DualSenseBluetoothOutputSnapshot = DualSenseBluetoothOutputSnapshot(
            validFlag0,
            validFlag1,
            validFlag2,
            motorRight,
            motorLeft,
            rightTriggerType,
            rightTrigger.copyOf(),
            leftTriggerType,
            leftTrigger.copyOf(),
            muteLed,
            playerLeds,
            lightbarRed,
            lightbarGreen,
            lightbarBlue
        )

        fun clearValidFlags() {
            validFlag0 = 0
            validFlag1 = 0
            validFlag2 = 0
        }

        fun restoreValidFlags(snapshot: DualSenseBluetoothOutputSnapshot) {
            validFlag0 = validFlag0 or snapshot.validFlag0
            validFlag1 = validFlag1 or snapshot.validFlag1
            validFlag2 = validFlag2 or snapshot.validFlag2
        }

        fun hasPendingOutput(): Boolean = validFlag0 != 0 || validFlag1 != 0 || validFlag2 != 0
    }

    private val stateLock = Any()
    private val sendLock = Any()
    private val state = MutableState()
    private var scheduled = false
    private var closed = false
    private var lightbarSetupQueued = false
    private var sequence = 0

    /** Queues the one-shot Bluetooth lightbar setup ahead of normal output. */
    fun initializeLightbar(): Boolean {
        synchronized(stateLock) {
            if (closed) return false
            if (lightbarSetupQueued) return true
            lightbarSetupQueued = true
            notifyOutputEvent(DualSenseBluetoothOutputEvent.SUBMITTED)
            return try {
                executor.execute {
                    val sent = synchronized(sendLock) {
                        runCatching {
                            sendReport(DualSenseBluetoothOutputEncoder.lightbarSetup(nextSequence()))
                        }.getOrDefault(false)
                    }
                    if (sent) {
                        notifyOutputEvent(DualSenseBluetoothOutputEvent.SENT)
                    } else {
                        notifySendFailure()
                    }
                }
                true
            } catch (_: RuntimeException) {
                lightbarSetupQueued = false
                notifySendFailure()
                false
            }
        }
    }

    fun updateRumble(lowFrequency: Short, highFrequency: Short): Boolean = update {
        motorRight = (highFrequency.toInt() ushr 8) and 0xFF
        motorLeft = (lowFrequency.toInt() ushr 8) and 0xFF
        validFlag0 = validFlag0 or DualSenseBluetoothOutputEncoder.VALID_COMPATIBLE_RUMBLE
    }

    fun updateAdaptiveTriggers(
        eventFlags: Byte,
        typeLeft: Byte,
        typeRight: Byte,
        left: ByteArray,
        right: ByteArray
    ): Boolean {
        if (left.size != DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE ||
            right.size != DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE
        ) {
            return false
        }
        return update {
            val requested = eventFlags.toInt() and DualSenseAdaptiveTriggerEffect.BOTH_FLAGS
            if (requested and DualSenseAdaptiveTriggerEffect.RIGHT_FLAG != 0) {
                rightTriggerType = typeRight
                rightTrigger = right.copyOf()
            }
            if (requested and DualSenseAdaptiveTriggerEffect.LEFT_FLAG != 0) {
                leftTriggerType = typeLeft
                leftTrigger = left.copyOf()
            }
            validFlag0 = validFlag0 or requested
        }
    }

    fun updateLightbar(red: Byte, green: Byte, blue: Byte): Boolean = update {
        lightbarRed = red.toInt() and 0xFF
        lightbarGreen = green.toInt() and 0xFF
        lightbarBlue = blue.toInt() and 0xFF
        validFlag1 = validFlag1 or DualSenseBluetoothOutputEncoder.VALID_LIGHTBAR
    }

    fun updatePlayerLeds(mask: Int): Boolean = update {
        playerLeds = mask and 0x1F
        validFlag1 = validFlag1 or DualSenseBluetoothOutputEncoder.VALID_PLAYER_LEDS
    }

    fun updateMuteLed(enabled: Boolean): Boolean = update {
        muteLed = if (enabled) 1 else 0
        validFlag1 = validFlag1 or DualSenseBluetoothOutputEncoder.VALID_MUTE_LED
    }

    override fun close() {
        close(sendNeutral = true)
    }

    fun close(sendNeutral: Boolean) {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            state.clearValidFlags()
        }
        executor.shutdown()
        runCatching { executor.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }
        if (sendNeutral) {
            notifyOutputEvent(DualSenseBluetoothOutputEvent.SUBMITTED)
            synchronized(sendLock) {
                val sent = runCatching {
                    sendReport(DualSenseBluetoothOutputEncoder.neutral(nextSequence()))
                }.getOrDefault(false)
                if (sent) {
                    notifyOutputEvent(DualSenseBluetoothOutputEvent.SENT)
                } else {
                    notifySendFailure()
                }
            }
        }
    }

    private fun update(block: MutableState.() -> Unit): Boolean {
        synchronized(stateLock) {
            if (closed) return false
            state.block()
            notifyOutputEvent(DualSenseBluetoothOutputEvent.SUBMITTED)
            if (scheduled) {
                notifyOutputEvent(DualSenseBluetoothOutputEvent.COALESCED)
                return true
            }
            scheduled = true
            return try {
                executor.execute(::drain)
                true
            } catch (_: RuntimeException) {
                scheduled = false
                notifySendFailure()
                false
            }
        }
    }

    private fun drain() {
        while (true) {
            val snapshot = synchronized(stateLock) {
                if (closed || !state.hasPendingOutput()) {
                    scheduled = false
                    return
                }
                state.snapshot().also { state.clearValidFlags() }
            }
            val sent = synchronized(sendLock) {
                runCatching {
                    sendReport(DualSenseBluetoothOutputEncoder.encode(snapshot, nextSequence()))
                }.getOrDefault(false)
            }
            if (!sent) {
                synchronized(stateLock) {
                    if (!closed) state.restoreValidFlags(snapshot)
                    scheduled = false
                }
                notifySendFailure()
                return
            }
            notifyOutputEvent(DualSenseBluetoothOutputEvent.SENT)
        }
    }

    private fun notifySendFailure() {
        notifyOutputEvent(DualSenseBluetoothOutputEvent.FAILED)
        runCatching(onSendFailure)
    }

    private fun notifyOutputEvent(event: DualSenseBluetoothOutputEvent) {
        runCatching { onOutputEvent(event) }
    }

    private fun nextSequence(): Int {
        val current = sequence
        sequence = (sequence + 1) and 0x0F
        return current
    }

    companion object {
        private const val CLOSE_WAIT_MS = 1000L
    }
}
