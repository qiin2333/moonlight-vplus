package com.limelight.ui

import kotlin.math.roundToInt

internal data class FloatingButtonCoordinates(
    val x: Int,
    val y: Int
)

internal data class FloatingButtonNormalizedPosition(
    val x: Float,
    val y: Float
)

internal data class FloatingButtonViewport(
    val containerWidth: Int,
    val containerHeight: Int,
    val buttonWidth: Int,
    val buttonHeight: Int,
    val edgeInset: Int,
    val leftInset: Int = 0,
    val topInset: Int = 0,
    val rightInset: Int = 0,
    val bottomInset: Int = 0
)

internal object FloatingButtonPlacement {
    const val POSITION_TOP_LEFT = "top_left"
    const val POSITION_TOP_CENTER = "top_center"
    const val POSITION_TOP_RIGHT = "top_right"
    const val POSITION_CENTER_LEFT = "center_left"
    const val POSITION_CENTER_RIGHT = "center_right"
    const val POSITION_BOTTOM_LEFT = "bottom_left"
    const val POSITION_BOTTOM_CENTER = "bottom_center"
    const val POSITION_BOTTOM_RIGHT = "bottom_right"
    const val POSITION_CUSTOM = "custom"
    const val DEFAULT_POSITION = POSITION_CENTER_RIGHT

    private val presetPositions = setOf(
        POSITION_TOP_LEFT,
        POSITION_TOP_CENTER,
        POSITION_TOP_RIGHT,
        POSITION_CENTER_LEFT,
        POSITION_CENTER_RIGHT,
        POSITION_BOTTOM_LEFT,
        POSITION_BOTTOM_CENTER,
        POSITION_BOTTOM_RIGHT
    )

    fun normalizePreset(position: String?): String =
        position?.takeIf { it in presetPositions } ?: DEFAULT_POSITION

    fun resolve(
        position: String,
        customPosition: FloatingButtonNormalizedPosition,
        viewport: FloatingButtonViewport
    ): FloatingButtonCoordinates {
        val horizontal = axisBounds(
            viewport.containerWidth,
            viewport.buttonWidth,
            viewport.edgeInset,
            viewport.leftInset,
            viewport.rightInset
        )
        val vertical = axisBounds(
            viewport.containerHeight,
            viewport.buttonHeight,
            viewport.edgeInset,
            viewport.topInset,
            viewport.bottomInset
        )
        val centerX = (horizontal.first + horizontal.last) / 2
        val centerY = (vertical.first + vertical.last) / 2

        return when (position) {
            POSITION_TOP_LEFT -> FloatingButtonCoordinates(horizontal.first, vertical.first)
            POSITION_TOP_CENTER -> FloatingButtonCoordinates(centerX, vertical.first)
            POSITION_TOP_RIGHT -> FloatingButtonCoordinates(horizontal.last, vertical.first)
            POSITION_CENTER_LEFT -> FloatingButtonCoordinates(horizontal.first, centerY)
            POSITION_CENTER_RIGHT -> FloatingButtonCoordinates(horizontal.last, centerY)
            POSITION_BOTTOM_LEFT -> FloatingButtonCoordinates(horizontal.first, vertical.last)
            POSITION_BOTTOM_CENTER -> FloatingButtonCoordinates(centerX, vertical.last)
            POSITION_BOTTOM_RIGHT -> FloatingButtonCoordinates(horizontal.last, vertical.last)
            POSITION_CUSTOM -> FloatingButtonCoordinates(
                interpolate(horizontal, customPosition.x),
                interpolate(vertical, customPosition.y)
            )
            else -> FloatingButtonCoordinates(horizontal.last, centerY)
        }
    }

    fun clampCustom(
        x: Float,
        y: Float,
        viewport: FloatingButtonViewport
    ): FloatingButtonCoordinates {
        val horizontal = axisBounds(
            viewport.containerWidth,
            viewport.buttonWidth,
            viewport.edgeInset,
            viewport.leftInset,
            viewport.rightInset
        )
        val vertical = axisBounds(
            viewport.containerHeight,
            viewport.buttonHeight,
            viewport.edgeInset,
            viewport.topInset,
            viewport.bottomInset
        )
        return FloatingButtonCoordinates(
            x.roundToInt().coerceIn(horizontal.first, horizontal.last),
            y.roundToInt().coerceIn(vertical.first, vertical.last)
        )
    }

    fun normalize(
        coordinates: FloatingButtonCoordinates,
        viewport: FloatingButtonViewport
    ): FloatingButtonNormalizedPosition {
        val horizontal = axisBounds(
            viewport.containerWidth,
            viewport.buttonWidth,
            viewport.edgeInset,
            viewport.leftInset,
            viewport.rightInset
        )
        val vertical = axisBounds(
            viewport.containerHeight,
            viewport.buttonHeight,
            viewport.edgeInset,
            viewport.topInset,
            viewport.bottomInset
        )
        return FloatingButtonNormalizedPosition(
            normalizeAxis(coordinates.x, horizontal),
            normalizeAxis(coordinates.y, vertical)
        )
    }

    private fun axisBounds(
        containerSize: Int,
        buttonSize: Int,
        edgeInset: Int,
        leadingInset: Int,
        trailingInset: Int
    ): IntRange {
        val available = (containerSize - buttonSize).coerceAtLeast(0)
        val insetStart = leadingInset.coerceAtLeast(0).coerceAtMost(available)
        val insetEnd = (available - trailingInset.coerceAtLeast(0)).coerceAtLeast(0)
        if (insetStart <= insetEnd) {
            val safeEdgeInset = edgeInset.coerceAtLeast(0)
                .coerceAtMost((insetEnd - insetStart) / 2)
            return (insetStart + safeEdgeInset)..(insetEnd - safeEdgeInset)
        }
        val center = available / 2
        return center..center
    }

    private fun interpolate(bounds: IntRange, normalized: Float): Int {
        val fraction = normalized.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        return (bounds.first + (bounds.last - bounds.first) * fraction).roundToInt()
    }

    private fun normalizeAxis(value: Int, bounds: IntRange): Float {
        val span = bounds.last - bounds.first
        if (span <= 0) return 0.5f
        return ((value.coerceIn(bounds.first, bounds.last) - bounds.first).toFloat() / span)
            .coerceIn(0f, 1f)
    }
}
