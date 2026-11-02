package com.limelight.binding.input.touchpad

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of a reusable screen touchpad surface. All rects are normalized to the
 * stream content, matching the contact coordinate space of LI touch events, so the
 * same values drive hit-testing in [com.limelight.TouchInputHandler], the overlay
 * drawing in [com.limelight.ui.TouchpadZoneOverlayView] and any future consumer
 * (e.g. a virtual-controller pad). Pure floats throughout so it stays unit-testable
 * without Robolectric.
 */
object TouchpadZoneGeometry {

    data class NormalizedRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    /** Zone borders a resizing finger is currently driving. */
    data class EdgeSet(
        val left: Boolean,
        val top: Boolean,
        val right: Boolean,
        val bottom: Boolean,
    ) {
        val any: Boolean get() = left || top || right || bottom
    }

    fun defaultZoneRect(): NormalizedRect = parseZoneRect(DEFAULT_ZONE_RECT)

    /**
     * Parse a persisted "left,top,right,bottom" string. Values outside 0..1, a
     * degenerate rect, or unparsable input fall back to the default zone.
     */
    fun parseZoneRect(raw: String?): NormalizedRect {
        if (raw.isNullOrBlank()) return parseZoneRect(DEFAULT_ZONE_RECT)
        val parts = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (parts.size != 4) return parseZoneRect(DEFAULT_ZONE_RECT)
        val left = parts[0].coerceIn(0f, 1f)
        val top = parts[1].coerceIn(0f, 1f)
        val right = parts[2].coerceIn(0f, 1f)
        val bottom = parts[3].coerceIn(0f, 1f)
        if (right - left < MIN_ZONE_WIDTH || bottom - top < MIN_ZONE_HEIGHT) {
            return parseZoneRect(DEFAULT_ZONE_RECT)
        }
        return NormalizedRect(left, top, right, bottom)
    }

    fun formatZoneRect(rect: NormalizedRect): String =
        listOf(rect.left, rect.top, rect.right, rect.bottom)
            // Locale-invariant: the value round-trips through parseZoneRect, which
            // splits on the ASCII comma — a comma-decimal locale would corrupt it.
            .joinToString(",") { String.format(Locale.US, "%.4f", it) }

    /** The pad surface a touch belongs to. */
    fun hitTest(zone: NormalizedRect, x: Float, y: Float): Boolean =
        zone.contains(x, y)

    /**
     * Which zone borders sit within a finger's reach. [bandX]/[bandY] are the band
     * half-widths normalized to the content; corners report both adjoining edges.
     */
    fun edgeHitTest(zone: NormalizedRect, x: Float, y: Float, bandX: Float, bandY: Float): EdgeSet? {
        val nearLeft = abs(x - zone.left) <= bandX
        val nearRight = abs(x - zone.right) <= bandX
        val nearTop = abs(y - zone.top) <= bandY
        val nearBottom = abs(y - zone.bottom) <= bandY
        val withinX = x >= zone.left - bandX && x <= zone.right + bandX
        val withinY = y >= zone.top - bandY && y <= zone.bottom + bandY
        val edges = EdgeSet(
            left = nearLeft && withinY,
            top = nearTop && withinX,
            right = nearRight && withinY,
            bottom = nearBottom && withinX,
        )
        return if (edges.any) edges else null
    }

    /** Drive the flagged borders to the finger position, keeping the zone valid. */
    fun resizeEdges(zone: NormalizedRect, edges: EdgeSet, x: Float, y: Float): NormalizedRect {
        val left = if (edges.left) x else zone.left
        val top = if (edges.top) y else zone.top
        val right = if (edges.right) x else zone.right
        val bottom = if (edges.bottom) y else zone.bottom
        return clampZoneRect(left, top, right, bottom)
    }

    /** Clamp a dragged/edited zone so it stays inside the content and above the minimum size. */
    fun clampZoneRect(left: Float, top: Float, right: Float, bottom: Float): NormalizedRect {
        var l = left.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (r - l < MIN_ZONE_WIDTH) {
            if (l + MIN_ZONE_WIDTH <= 1f) r = l + MIN_ZONE_WIDTH else l = r - MIN_ZONE_WIDTH
        }
        if (b - t < MIN_ZONE_HEIGHT) {
            if (t + MIN_ZONE_HEIGHT <= 1f) b = t + MIN_ZONE_HEIGHT else t = b - MIN_ZONE_HEIGHT
        }
        return NormalizedRect(l, t, r, b)
    }

    const val DEFAULT_ZONE_RECT = "0.18,0.74,0.82,0.94"
    const val MIN_ZONE_WIDTH = 0.12f
    const val MIN_ZONE_HEIGHT = 0.06f
}
