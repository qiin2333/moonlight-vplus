package com.limelight.binding.input.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadZoneGeometryTest {
    private val defaultZone = TouchpadZoneGeometry.NormalizedRect(0.18f, 0.74f, 0.82f, 0.94f)

    @Test
    fun parseZoneRectReadsFourCommaSeparatedValues() {
        val rect = TouchpadZoneGeometry.parseZoneRect("0.1,0.2,0.9,0.8")
        assertEquals(0.1f, rect.left, 1e-6f)
        assertEquals(0.2f, rect.top, 1e-6f)
        assertEquals(0.9f, rect.right, 1e-6f)
        assertEquals(0.8f, rect.bottom, 1e-6f)
    }

    @Test
    fun parseZoneRectFallsBackToDefaultOnGarbage() {
        for (raw in listOf(null, "", "abc", "1,2", "0.1,0.2,0.9", "a,b,c,d")) {
            assertEquals(
                defaultZone,
                TouchpadZoneGeometry.parseZoneRect(raw)
            )
        }
    }

    @Test
    fun parseZoneRectFallsBackToDefaultOnDegenerateRect() {
        // Zero-width and inverted rects are invalid, not clampable.
        assertEquals(
            defaultZone,
            TouchpadZoneGeometry.parseZoneRect("0.5,0.5,0.5,0.9")
        )
        assertEquals(
            defaultZone,
            TouchpadZoneGeometry.parseZoneRect("0.8,0.9,0.2,0.5")
        )
    }

    @Test
    fun parseZoneRectClampsOutOfBoundsValues() {
        val rect = TouchpadZoneGeometry.parseZoneRect("-0.5,-0.5,1.5,1.5")
        assertEquals(0f, rect.left, 1e-6f)
        assertEquals(0f, rect.top, 1e-6f)
        assertEquals(1f, rect.right, 1e-6f)
        assertEquals(1f, rect.bottom, 1e-6f)
    }

    @Test
    fun formatAndParseRoundTrip() {
        val rect = TouchpadZoneGeometry.NormalizedRect(0.12f, 0.3456f, 0.8877f, 0.94f)
        assertEquals(
            rect,
            TouchpadZoneGeometry.parseZoneRect(TouchpadZoneGeometry.formatZoneRect(rect))
        )
    }

    @Test
    fun minimumSizeSurvivesFormatAndParseAcrossTheSurface() {
        for (index in 0..1000) {
            val left = index / 1000f * (1f - TouchpadZoneGeometry.MIN_ZONE_WIDTH)
            val top = index / 1000f * (1f - TouchpadZoneGeometry.MIN_ZONE_HEIGHT)
            val rect = TouchpadZoneGeometry.clampZoneRect(left, top, left, top)
            val serialized = TouchpadZoneGeometry.formatZoneRect(rect)
            val expected = serialized.split(',').map(String::toFloat).let {
                TouchpadZoneGeometry.NormalizedRect(it[0], it[1], it[2], it[3])
            }
            assertEquals(serialized, expected, TouchpadZoneGeometry.parseZoneRect(serialized))
        }
    }

    @Test
    fun parseAllowsOneRoundingUnitButRejectsSmallerZones() {
        val rounded = TouchpadZoneGeometry.NormalizedRect(0.3f, 0.6f, 0.4199f, 0.6599f)
        assertEquals(rounded, TouchpadZoneGeometry.parseZoneRect("0.3000,0.6000,0.4199,0.6599"))
        for (raw in listOf("0.3,0.6,0.4198,0.8", "0.3,0.6,0.8,0.6598")) {
            assertEquals(defaultZone, TouchpadZoneGeometry.parseZoneRect(raw))
        }
    }

    @Test
    fun clampStillEnforcesTheMinimumWithinTheParseTolerance() {
        val rect = TouchpadZoneGeometry.clampZoneRect(0f, 0f, 0.1199f, 0.0599f)
        assertEquals(TouchpadZoneGeometry.MIN_ZONE_WIDTH, rect.width, 0f)
        assertEquals(TouchpadZoneGeometry.MIN_ZONE_HEIGHT, rect.height, 0f)
    }

    @Test
    fun hitTestOnlyAcceptsPointsInsideTheZone() {
        val zone = defaultZone
        assertTrue(TouchpadZoneGeometry.hitTest(zone, 0.5f, 0.8f))
        assertFalse(TouchpadZoneGeometry.hitTest(zone, 0.5f, 0.1f))
        assertFalse(TouchpadZoneGeometry.hitTest(zone, 0.05f, 0.8f))
    }

    @Test
    fun edgeHitTestFlagsBordersWithinTheBand() {
        val zone = TouchpadZoneGeometry.NormalizedRect(0.2f, 0.6f, 0.8f, 0.9f)
        // band 0.03: inside near the left border only
        assertEquals(
            TouchpadZoneGeometry.EdgeSet(left = true, top = false, right = false, bottom = false),
            TouchpadZoneGeometry.edgeHitTest(zone, 0.215f, 0.75f, 0.03f, 0.03f)
        )
        // a corner reports both adjoining edges
        assertEquals(
            TouchpadZoneGeometry.EdgeSet(left = true, top = true, right = false, bottom = false),
            TouchpadZoneGeometry.edgeHitTest(zone, 0.205f, 0.61f, 0.03f, 0.03f)
        )
        // deep inside hits nothing
        assertNull(TouchpadZoneGeometry.edgeHitTest(zone, 0.5f, 0.75f, 0.03f, 0.03f))
        // outside the zone's extent hits nothing even if level with a border
        assertNull(TouchpadZoneGeometry.edgeHitTest(zone, 0.1f, 0.75f, 0.03f, 0.03f))
    }

    @Test
    fun resizeEdgesDriveOnlyTheFlaggedBorders() {
        val zone = TouchpadZoneGeometry.NormalizedRect(0.2f, 0.6f, 0.8f, 0.9f)
        val resized = TouchpadZoneGeometry.resizeEdges(
            zone,
            TouchpadZoneGeometry.EdgeSet(left = false, top = true, right = true, bottom = false),
            x = 0.9f,
            y = 0.5f,
        )
        assertEquals(0.2f, resized.left, 1e-6f)
        assertEquals(0.5f, resized.top, 1e-6f)
        assertEquals(0.9f, resized.right, 1e-6f)
        assertEquals(0.9f, resized.bottom, 1e-6f)
    }

    @Test
    fun resizeEdgesCrossingTheOppositeBorderClampsToMinimumSize() {
        val zone = TouchpadZoneGeometry.NormalizedRect(0.2f, 0.6f, 0.8f, 0.9f)
        val resized = TouchpadZoneGeometry.resizeEdges(
            zone,
            TouchpadZoneGeometry.EdgeSet(left = false, top = false, right = true, bottom = false),
            x = 0.1f,
            y = 0.75f,
        )
        assertEquals(0.2f, resized.left, 1e-6f)
        assertEquals(
            0.2f + TouchpadZoneGeometry.MIN_ZONE_WIDTH,
            resized.right,
            1e-4f,
        )
    }

    @Test
    fun clampZoneRectEnforcesMinimumSizeAndBounds() {
        val tiny = TouchpadZoneGeometry.clampZoneRect(0.5f, 0.5f, 0.55f, 0.52f)
        assertEquals(TouchpadZoneGeometry.MIN_ZONE_WIDTH, tiny.width, 1e-6f)
        assertEquals(TouchpadZoneGeometry.MIN_ZONE_HEIGHT, tiny.height, 1e-6f)

        val pinned = TouchpadZoneGeometry.clampZoneRect(0.95f, 0.95f, 0.99f, 0.99f)
        assertEquals(0.99f, pinned.right, 1e-6f)
        assertEquals(0.99f - TouchpadZoneGeometry.MIN_ZONE_WIDTH, pinned.left, 1e-4f)
        assertEquals(0.99f, pinned.bottom, 1e-6f)
        assertEquals(0.99f - TouchpadZoneGeometry.MIN_ZONE_HEIGHT, pinned.top, 1e-4f)
    }
}
