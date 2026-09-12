package com.limelight.binding.input

/** Keep both endpoints reachable after moving the neutral position. */
internal fun recenterStickAxis(value: Float, center: Float): Float {
    if (!value.isFinite()) return 0f
    val safeCenter = if (center.isFinite() && center in -0.9f..0.9f) center else 0f
    val delta = value - safeCenter
    return (delta / if (delta >= 0f) 1f - safeCenter else 1f + safeCenter)
        .coerceIn(-1f, 1f)
}
