package com.limelight.binding.input.haptics

import kotlin.math.roundToInt

/** XL Sensa wire format: MSB-first, 7-bit quarter-millisecond duration,
 * then each actuator's bands (four 6-bit amplitude/7-bit frequency points).
 * Empty-band and empty-transient flags terminate each list. No vendor code or runtime.
 */
internal object KishiSensaPacket {
    data class Point(val amplitude: Double, val frequency: Double)

    fun frame(actuators: List<List<List<Point>>>): ByteArray {
        // Order is physical left, then right (metadata body IDs 216 and 116).
        // Each nested list contains bands, each with exactly four envelope points.
        require(actuators.size == 2)
        val payload = ByteArray(42) // 7 + 2 * (3 * (1 + 4 * 13) + 1) = 327 bits maximum
        var bit = 0
        fun put(value: Int, width: Int) {
            require(value in 0 until (1 shl width))
            repeat(width) { i ->
                if (value and (1 shl (width - i - 1)) != 0) {
                    payload[bit / 8] = (payload[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
                }
                bit++
            }
        }
        put(40, 7) // 10 ms
        for (bands in actuators) {
            require(bands.size <= 3)
            for (points in bands) {
                require(points.size == 4)
                put(1, 1)
                for (point in points) {
                    require(point.amplitude.isFinite() && point.frequency.isFinite())
                    put((point.amplitude.coerceIn(0.0, 1.0) * 63).roundToInt(), 6)
                    put(((point.frequency.coerceIn(30.0, 400.0) - 30) * 127 / 370).toInt(), 7)
                }
            }
            // Three bands implicitly end the list; adding a terminator in that case
            // would shift the following actuator and corrupt an otherwise valid frame.
            if (bands.size < 3) put(0, 1)
            put(0, 1) // no transient events
        }
        return payload.copyOf((bit + 7) / 8)
    }

    fun report(command: Int, payload: ByteArray): ByteArray {
        // Fixed HID report: ID 2, length excluding report ID, reserved 0, protocol 1,
        // command, payload, zero padding. Replies use ID 1 with the same command.
        require(command in 0..255 && payload.size in 1..58)
        return ByteArray(64).also {
            it[0] = 2
            it[1] = (payload.size + 4).toByte()
            it[3] = 1
            it[4] = command.toByte()
            payload.copyInto(it, 5)
        }
    }

    fun silence(): ByteArray = report(0x0e, frame(List(2) {
        listOf(List(4) { Point(0.0, 30.0) })
    }))
}
