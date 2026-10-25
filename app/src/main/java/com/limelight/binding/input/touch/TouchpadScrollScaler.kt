package com.limelight.binding.input.touch

/** Keeps sub-unit motion until the next event and splits output without Short wrapping. */
internal class TouchpadScrollScaler {
    private var remainder = 0.0

    fun send(delta: Int, percent: Int, emit: (Short) -> Unit) {
        val scaled = delta.toDouble() * 5 * percent.coerceIn(1, 500) / 100 + remainder
        val whole = scaled.toLong()
        remainder = scaled - whole
        // Bound work per MotionEvent even if a malformed source supplies an extreme delta.
        var units = whole.coerceIn(-MAX_UNITS_PER_EVENT, MAX_UNITS_PER_EVENT)
        while (units != 0L) {
            val chunk = units.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
            emit(chunk.toShort())
            units -= chunk
        }
    }

    fun reset() {
        remainder = 0.0
    }

    private companion object {
        const val MAX_UNITS_PER_EVENT = Short.MAX_VALUE.toLong() * 32
    }
}
