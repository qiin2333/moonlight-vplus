package com.limelight.ui

internal object FloatBallPlacement {
    fun presetPosition(position: String): FloatBallStoredPosition {
        val normalizedPreset = FloatingButtonPlacement.normalizePreset(position)
        val normalized = when (normalizedPreset) {
            FloatingButtonPlacement.POSITION_TOP_LEFT -> normalized(0f, 0f)
            FloatingButtonPlacement.POSITION_TOP_CENTER -> normalized(0.5f, 0f)
            FloatingButtonPlacement.POSITION_TOP_RIGHT -> normalized(1f, 0f)
            FloatingButtonPlacement.POSITION_CENTER_LEFT -> normalized(0f, 0.5f)
            FloatingButtonPlacement.POSITION_BOTTOM_LEFT -> normalized(0f, 1f)
            FloatingButtonPlacement.POSITION_BOTTOM_CENTER -> normalized(0.5f, 1f)
            FloatingButtonPlacement.POSITION_BOTTOM_RIGHT -> normalized(1f, 1f)
            else -> normalized(1f, 0.5f)
        }
        val edge = when (normalizedPreset) {
            FloatingButtonPlacement.POSITION_TOP_CENTER -> FloatBallEdge.TOP
            FloatingButtonPlacement.POSITION_BOTTOM_CENTER -> FloatBallEdge.BOTTOM
            FloatingButtonPlacement.POSITION_TOP_LEFT,
            FloatingButtonPlacement.POSITION_CENTER_LEFT,
            FloatingButtonPlacement.POSITION_BOTTOM_LEFT -> FloatBallEdge.LEFT
            else -> FloatBallEdge.RIGHT
        }
        return FloatBallStoredPosition(normalized, edge)
    }

    fun migrateLegacy(
        legacy: FloatBallLegacyPosition,
        viewport: FloatingButtonViewport,
        enableEdgeSnap: Boolean
    ): FloatBallStoredPosition {
        val coordinates = FloatingButtonPlacement.clampCustom(
            legacy.x.toFloat(),
            legacy.y.toFloat(),
            viewport
        )
        val edge = if (enableEdgeSnap) {
            nearestEdge(coordinates, viewport)
        } else {
            FloatBallEdge.FREE
        }
        val anchored = applyEdge(coordinates, edge, viewport)
        return FloatBallStoredPosition(
            normalized = FloatingButtonPlacement.normalize(anchored, viewport),
            edge = edge
        )
    }

    fun snapToNearestEdge(
        coordinates: FloatingButtonCoordinates,
        viewport: FloatingButtonViewport
    ): Pair<FloatingButtonCoordinates, FloatBallEdge> {
        val edge = nearestEdge(coordinates, viewport)
        return applyEdge(coordinates, edge, viewport) to edge
    }

    fun applyEdge(
        coordinates: FloatingButtonCoordinates,
        edge: FloatBallEdge,
        viewport: FloatingButtonViewport
    ): FloatingButtonCoordinates {
        val bounds = bounds(viewport)
        return when (edge) {
            FloatBallEdge.LEFT -> coordinates.copy(x = bounds.left)
            FloatBallEdge.RIGHT -> coordinates.copy(x = bounds.right)
            FloatBallEdge.TOP -> coordinates.copy(y = bounds.top)
            FloatBallEdge.BOTTOM -> coordinates.copy(y = bounds.bottom)
            FloatBallEdge.FREE -> coordinates
        }
    }

    fun nearestEdge(
        coordinates: FloatingButtonCoordinates,
        viewport: FloatingButtonViewport
    ): FloatBallEdge {
        val bounds = bounds(viewport)
        val distances = linkedMapOf(
            FloatBallEdge.LEFT to coordinates.x - bounds.left,
            FloatBallEdge.RIGHT to bounds.right - coordinates.x,
            FloatBallEdge.TOP to coordinates.y - bounds.top,
            FloatBallEdge.BOTTOM to bounds.bottom - coordinates.y
        )
        return distances.minBy { it.value }.key
    }

    fun halfShownCoordinates(
        anchor: FloatingButtonCoordinates,
        edge: FloatBallEdge,
        ballSize: Int
    ): FloatingButtonCoordinates = when (edge) {
        FloatBallEdge.LEFT -> anchor.copy(x = anchor.x - ballSize / 2)
        FloatBallEdge.RIGHT -> anchor.copy(x = anchor.x + ballSize / 2)
        FloatBallEdge.TOP -> anchor.copy(y = anchor.y - ballSize / 2)
        FloatBallEdge.BOTTOM -> anchor.copy(y = anchor.y + ballSize / 2)
        FloatBallEdge.FREE -> anchor
    }

    private fun bounds(viewport: FloatingButtonViewport): Bounds {
        val origin = FloatingButtonPlacement.resolve(
            FloatingButtonPlacement.POSITION_TOP_LEFT,
            normalized(0f, 0f),
            viewport
        )
        val end = FloatingButtonPlacement.resolve(
            FloatingButtonPlacement.POSITION_BOTTOM_RIGHT,
            normalized(1f, 1f),
            viewport
        )
        return Bounds(origin.x, origin.y, end.x, end.y)
    }

    private fun normalized(x: Float, y: Float) = FloatingButtonNormalizedPosition(x, y)

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )
}
