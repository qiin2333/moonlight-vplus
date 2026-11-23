package com.limelight.binding.video

internal object PyrowaveFrameAccounting {
    // Frame-number gaps include both network losses and complete frames we
    // deliberately removed from the decode queue. Only the remainder is loss.
    fun missingFrames(previous: Int, current: Int, discarded: Int): Int {
        if (previous == 0 || current <= previous) return 0
        val gap = current.toLong() - previous.toLong() - 1L
        return (gap - discarded.coerceAtLeast(0)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
}
