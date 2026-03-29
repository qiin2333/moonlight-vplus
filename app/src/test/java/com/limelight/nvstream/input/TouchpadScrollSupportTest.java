package com.limelight.nvstream.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TouchpadScrollSupportTest {
    @Test
    public void detectsExplicitTouchpadScrollFeatureSupport() {
        assertTrue(TouchpadScrollSupport.supportsExplicitTouchpadScroll(
                TouchpadScrollSupport.LI_FF_TOUCHPAD_SCROLL_EVENTS));
        assertFalse(TouchpadScrollSupport.supportsExplicitTouchpadScroll(0));
        assertTrue(TouchpadScrollSupport.supportsExplicitTouchpadScroll(
                TouchpadScrollSupport.LI_FF_TOUCHPAD_SCROLL_EVENTS | 0x40));
    }

    @Test
    public void scalesAndroidAxisValuesToHighResolutionWheelDeltas() {
        assertEquals((short) 120, TouchpadScrollSupport.scaleAxisValue(1.0f));
        assertEquals((short) -60, TouchpadScrollSupport.scaleAxisValue(-0.5f));
        assertEquals((short) 0, TouchpadScrollSupport.scaleAxisValue(0.0f));
    }

    @Test
    public void beginsThenChangesGesturePhases() {
        assertEquals(TouchpadScrollSupport.LI_TOUCHPAD_SCROLL_PHASE_BEGAN,
                TouchpadScrollSupport.phaseForDelta(false));
        assertEquals(TouchpadScrollSupport.LI_TOUCHPAD_SCROLL_PHASE_CHANGED,
                TouchpadScrollSupport.phaseForDelta(true));
    }
}
