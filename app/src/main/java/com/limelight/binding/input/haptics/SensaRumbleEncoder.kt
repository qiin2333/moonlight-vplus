package com.limelight.binding.input.haptics

/** Converts ordinary low/high motor amplitudes into left/right Sensa tones.
 * Four amplitude points ramp changes over 10 ms; a zero command is immediate.
 * Frequency and amplitude are independent, with no audio/DS5 dependency.
 */
internal class SensaRumbleEncoder {
    private val previous = DoubleArray(2)
    fun reset() { previous.fill(0.0) }
    fun encode(low: Float, high: Float, strength: Double, frequency: Double): ByteArray {
        require(low.isFinite() && high.isFinite() && strength.isFinite() && frequency.isFinite())
        val target = doubleArrayOf(low.toDouble(), high.toDouble())
        return KishiSensaPacket.report(0x0e, KishiSensaPacket.frame(List(2) { channel ->
            val amplitude = target[channel].coerceIn(0.0, 1.0) * strength.coerceIn(0.0, 1.0)
            val start = if (amplitude == 0.0) 0.0 else previous[channel]
            previous[channel] = amplitude
            listOf(List(4) { point ->
                KishiSensaPacket.Point(start + (amplitude - start) * (point + 1) / 4,
                    frequency.coerceIn(30.0, 400.0))
            })
        }))
    }
}
