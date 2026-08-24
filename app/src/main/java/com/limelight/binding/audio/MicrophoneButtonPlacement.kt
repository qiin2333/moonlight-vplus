package com.limelight.binding.audio

import com.limelight.ui.FloatingButtonCoordinates
import com.limelight.ui.FloatingButtonNormalizedPosition
import com.limelight.ui.FloatingButtonPlacement
import com.limelight.ui.FloatingButtonViewport

internal typealias MicrophoneButtonCoordinates = FloatingButtonCoordinates
internal typealias MicrophoneButtonNormalizedPosition = FloatingButtonNormalizedPosition
internal typealias MicrophoneButtonViewport = FloatingButtonViewport

internal object MicrophoneButtonPlacement {
    const val POSITION_TOP_LEFT = FloatingButtonPlacement.POSITION_TOP_LEFT
    const val POSITION_TOP_CENTER = FloatingButtonPlacement.POSITION_TOP_CENTER
    const val POSITION_TOP_RIGHT = FloatingButtonPlacement.POSITION_TOP_RIGHT
    const val POSITION_CENTER_LEFT = FloatingButtonPlacement.POSITION_CENTER_LEFT
    const val POSITION_CENTER_RIGHT = FloatingButtonPlacement.POSITION_CENTER_RIGHT
    const val POSITION_BOTTOM_LEFT = FloatingButtonPlacement.POSITION_BOTTOM_LEFT
    const val POSITION_BOTTOM_CENTER = FloatingButtonPlacement.POSITION_BOTTOM_CENTER
    const val POSITION_BOTTOM_RIGHT = FloatingButtonPlacement.POSITION_BOTTOM_RIGHT
    const val POSITION_CUSTOM = FloatingButtonPlacement.POSITION_CUSTOM
    const val DEFAULT_POSITION = FloatingButtonPlacement.DEFAULT_POSITION

    fun normalizePreset(position: String?): String =
        FloatingButtonPlacement.normalizePreset(position)

    fun resolve(
        position: String,
        customPosition: MicrophoneButtonNormalizedPosition,
        viewport: MicrophoneButtonViewport
    ): MicrophoneButtonCoordinates {
        return FloatingButtonPlacement.resolve(position, customPosition, viewport)
    }

    fun clampCustom(
        x: Float,
        y: Float,
        viewport: MicrophoneButtonViewport
    ): MicrophoneButtonCoordinates {
        return FloatingButtonPlacement.clampCustom(x, y, viewport)
    }

    fun normalize(
        coordinates: MicrophoneButtonCoordinates,
        viewport: MicrophoneButtonViewport
    ): MicrophoneButtonNormalizedPosition {
        return FloatingButtonPlacement.normalize(coordinates, viewport)
    }
}
