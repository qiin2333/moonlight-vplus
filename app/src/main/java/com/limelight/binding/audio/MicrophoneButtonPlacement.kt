package com.limelight.binding.audio

import kotlin.math.roundToInt

internal data class MicrophoneButtonCoordinates(
    val x: Int,
    val y: Int
)

internal data class MicrophoneButtonNormalizedPosition(
    val x: Float,
    val y: Float
)

internal data class MicrophoneButtonViewport(
    val containerWidth: Int,
    val containerHeight: Int,
    val buttonWidth: Int,
    val buttonHeight: Int,
    val edgeInset: Int
)

internal object MicrophoneButtonPlacement {
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
        customPosition: MicrophoneButtonNormalizedPosition,
        viewport: MicrophoneButtonViewport
    ): MicrophoneButtonCoordinates {
        val horizontal = axisBounds(viewport.containerWidth, viewport.buttonWidth, viewport.edgeInset)
        val vertical = axisBounds(viewport.containerHeight, viewport.buttonHeight, viewport.edgeInset)
        val centerX = (horizontal.first + horizontal.last) / 2
        val centerY = (vertical.first + vertical.last) / 2

        return when (position) {
            POSITION_TOP_LEFT -> MicrophoneButtonCoordinates(horizontal.first, vertical.first)
            POSITION_TOP_CENTER -> MicrophoneButtonCoordinates(centerX, vertical.first)
            POSITION_TOP_RIGHT -> MicrophoneButtonCoordinates(horizontal.last, vertical.first)
            POSITION_CENTER_LEFT -> MicrophoneButtonCoordinates(horizontal.first, centerY)
            POSITION_CENTER_RIGHT -> MicrophoneButtonCoordinates(horizontal.last, centerY)
            POSITION_BOTTOM_LEFT -> MicrophoneButtonCoordinates(horizontal.first, vertical.last)
            POSITION_BOTTOM_CENTER -> MicrophoneButtonCoordinates(centerX, vertical.last)
            POSITION_BOTTOM_RIGHT -> MicrophoneButtonCoordinates(horizontal.last, vertical.last)
            POSITION_CUSTOM -> MicrophoneButtonCoordinates(
                interpolate(horizontal, customPosition.x),
                interpolate(vertical, customPosition.y)
            )
            else -> MicrophoneButtonCoordinates(horizontal.last, centerY)
        }
    }

    fun clampCustom(
        x: Float,
        y: Float,
        viewport: MicrophoneButtonViewport
    ): MicrophoneButtonCoordinates {
        val horizontal = axisBounds(viewport.containerWidth, viewport.buttonWidth, viewport.edgeInset)
        val vertical = axisBounds(viewport.containerHeight, viewport.buttonHeight, viewport.edgeInset)
        return MicrophoneButtonCoordinates(
            x.roundToInt().coerceIn(horizontal.first, horizontal.last),
            y.roundToInt().coerceIn(vertical.first, vertical.last)
        )
    }

    fun normalize(
        coordinates: MicrophoneButtonCoordinates,
        viewport: MicrophoneButtonViewport
    ): MicrophoneButtonNormalizedPosition {
        val horizontal = axisBounds(viewport.containerWidth, viewport.buttonWidth, viewport.edgeInset)
        val vertical = axisBounds(viewport.containerHeight, viewport.buttonHeight, viewport.edgeInset)
        return MicrophoneButtonNormalizedPosition(
            normalizeAxis(coordinates.x, horizontal),
            normalizeAxis(coordinates.y, vertical)
        )
    }

    private fun axisBounds(containerSize: Int, buttonSize: Int, edgeInset: Int): IntRange {
        val available = (containerSize - buttonSize).coerceAtLeast(0)
        val safeInset = edgeInset.coerceAtLeast(0).coerceAtMost(available / 2)
        return safeInset..(available - safeInset)
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
