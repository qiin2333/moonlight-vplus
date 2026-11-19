package com.limelight.binding.input.advance_setting.element

object WheelPadGeometry {
    private const val FULL_CIRCLE_DEGREES = 360.0f

    @JvmStatic
    fun sweepAngleDegrees(segmentCount: Int): Float {
        if (segmentCount <= 0) {
            throw IllegalArgumentException("segmentCount must be positive")
        }
        return FULL_CIRCLE_DEGREES / segmentCount
    }

    @JvmStatic
    fun rotationOffsetDegrees(segmentCount: Int, alignSegmentEdge: Boolean): Float {
        return if (alignSegmentEdge) sweepAngleDegrees(segmentCount) / 2.0f else 0.0f
    }

    @JvmStatic
    fun segmentStartAngleDegrees(index: Int, segmentCount: Int, alignSegmentEdge: Boolean): Float {
        val sweepAngle = sweepAngleDegrees(segmentCount)
        return (index * sweepAngle) - (sweepAngle / 2.0f) - 90.0f
                + rotationOffsetDegrees(segmentCount, alignSegmentEdge)
    }

    @JvmStatic
    fun segmentIndexForVector(dx: Float, dy: Float, segmentCount: Int, alignSegmentEdge: Boolean): Int {
        val angleFromTop = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())) + 90.0
        return segmentIndexForAngleFromTop(angleFromTop, segmentCount, alignSegmentEdge)
    }

    @JvmStatic
    fun segmentIndexForAngleFromTop(angleFromTop: Double, segmentCount: Int, alignSegmentEdge: Boolean): Int {
        val sweepAngle = sweepAngleDegrees(segmentCount)
        val adjustedAngle = normalizeDegrees(
            angleFromTop - rotationOffsetDegrees(segmentCount, alignSegmentEdge) + sweepAngle / 2.0f
        )
        return Math.floor(adjustedAngle / sweepAngle).toInt() % segmentCount
    }

    private fun normalizeDegrees(degrees: Double): Double {
        val normalized = degrees % FULL_CIRCLE_DEGREES
        return if (normalized < 0.0) normalized + FULL_CIRCLE_DEGREES else normalized
    }
}
