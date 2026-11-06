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
        assertEquals(
            TouchpadZoneGeometry.NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f),
            TouchpadZoneGeometry.parseZoneRect("0.1,0.2,0.9,0.8"),
        )
    }

    @Test
    fun invalidOrDegenerateZonesFallBackToDefault() {
        for (raw in listOf(null, "", "abc", "1,2", "0.1,0.2,0.9", "a,b,c,d",
            "0.5,0.5,0.5,0.9", "0.8,0.9,0.2,0.5")) {
            assertEquals(raw, defaultZone, TouchpadZoneGeometry.parseZoneRect(raw))
        }
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
    fun regularAndMinimumSizeZonesSurviveFormatAndParse() {
        val zones = listOf(
            TouchpadZoneGeometry.NormalizedRect(0.12f, 0.3456f, 0.8877f, 0.94f),
            TouchpadZoneGeometry.NormalizedRect(0f, 0f, 0.12f, 0.06f),
            // Parsed subtraction rounds width or height below its minimum.
            TouchpadZoneGeometry.NormalizedRect(0.2f, 0.6f, 0.32f, 0.66f),
            TouchpadZoneGeometry.NormalizedRect(0.1f, 0.2f, 0.22f, 0.26f),
            TouchpadZoneGeometry.NormalizedRect(0.88f, 0.94f, 1f, 1f),
        )
        for (rect in zones) {
            assertEquals(rect, TouchpadZoneGeometry.parseZoneRect(TouchpadZoneGeometry.formatZoneRect(rect)))
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
    fun clampEnforcesExactMinimumAndBoundsEvenWithinTheParseTolerance() {
        val tiny = TouchpadZoneGeometry.clampZoneRect(0f, 0f, 0.1199f, 0.0599f)
        assertEquals(TouchpadZoneGeometry.MIN_ZONE_WIDTH, tiny.width, 0f)
        assertEquals(TouchpadZoneGeometry.MIN_ZONE_HEIGHT, tiny.height, 0f)

        val pinned = TouchpadZoneGeometry.clampZoneRect(0.95f, 0.95f, 0.99f, 0.99f)
        assertEquals(0.99f, pinned.right, 1e-6f)
        assertEquals(0.99f - TouchpadZoneGeometry.MIN_ZONE_WIDTH, pinned.left, 1e-4f)
        assertEquals(0.99f, pinned.bottom, 1e-6f)
        assertEquals(0.99f - TouchpadZoneGeometry.MIN_ZONE_HEIGHT, pinned.top, 1e-4f)
    }
}
