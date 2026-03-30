package com.limelight.nvstream.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;
import android.view.MotionEvent;

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

    @Test
    public void onlyTreatsNativeTouchpadTwoFingerSwipeClassificationAsExplicitScroll() {
        assertTrue(TouchpadScrollSupport.shouldUseClassificationTouchpadScroll(
                true,
                InputDevice.SOURCE_TOUCHPAD,
                MotionEvent.TOOL_TYPE_MOUSE,
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE));
        assertTrue(TouchpadScrollSupport.shouldUseClassificationTouchpadScroll(
                true,
                InputDevice.SOURCE_CLASS_POSITION,
                MotionEvent.TOOL_TYPE_MOUSE,
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE));
        assertTrue(TouchpadScrollSupport.shouldUseClassificationTouchpadScroll(
                true,
                InputDevice.SOURCE_MOUSE,
                MotionEvent.TOOL_TYPE_MOUSE,
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE));
        assertTrue(TouchpadScrollSupport.shouldUseClassificationTouchpadScroll(
                true,
                InputDevice.SOURCE_MOUSE_RELATIVE,
                MotionEvent.TOOL_TYPE_MOUSE,
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE));
        assertFalse(TouchpadScrollSupport.shouldUseClassificationTouchpadScroll(
                false,
                InputDevice.SOURCE_TOUCHPAD,
                MotionEvent.TOOL_TYPE_MOUSE,
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE));
        assertFalse(TouchpadScrollSupport.shouldUseClassificationTouchpadScroll(
                true,
                InputDevice.SOURCE_TOUCHPAD,
                MotionEvent.TOOL_TYPE_MOUSE,
                MotionEvent.CLASSIFICATION_NONE));
        assertFalse(TouchpadScrollSupport.shouldUseClassificationTouchpadScroll(
                true,
                InputDevice.SOURCE_UNKNOWN,
                MotionEvent.TOOL_TYPE_FINGER,
                MotionEvent.CLASSIFICATION_NONE));
    }

    @Test
    public void onlyUsesLegacyFallbackForMultiPointerTouchpadMotion() {
        assertTrue(TouchpadScrollSupport.shouldUseLegacyMultiPointerScroll(
                true,
                InputDevice.SOURCE_TOUCHPAD,
                2));
        assertTrue(TouchpadScrollSupport.shouldUseLegacyMultiPointerScroll(
                true,
                InputDevice.SOURCE_CLASS_POSITION,
                3));
        assertFalse(TouchpadScrollSupport.shouldUseLegacyMultiPointerScroll(
                true,
                InputDevice.SOURCE_TOUCHPAD,
                1));
        assertFalse(TouchpadScrollSupport.shouldUseLegacyMultiPointerScroll(
                true,
                InputDevice.SOURCE_MOUSE,
                2));
    }

    @Test
    public void convertsGestureDistanceToPacketDeltaWithoutWheelTickScaling() {
        assertEquals((short) 24, TouchpadScrollSupport.scaleGestureDistance(24.4f));
        assertEquals((short) -13, TouchpadScrollSupport.scaleGestureDistance(-12.6f));
        assertEquals(Short.MAX_VALUE, TouchpadScrollSupport.scaleGestureDistance(Short.MAX_VALUE + 1000f));
        assertEquals(Short.MIN_VALUE, TouchpadScrollSupport.scaleGestureDistance(Short.MIN_VALUE - 1000f));
    }

    @Test
    public void invertsVerticalTouchpadScrollToMatchAndroidSwipeDirection() {
        assertEquals((short) -24, TouchpadScrollSupport.scaleVerticalGestureDistance(24.4f));
        assertEquals((short) 13, TouchpadScrollSupport.scaleVerticalGestureDistance(-12.6f));
        assertEquals(Short.MIN_VALUE, TouchpadScrollSupport.scaleVerticalGestureDistance(Short.MAX_VALUE + 1000f));
        assertEquals(Short.MAX_VALUE, TouchpadScrollSupport.scaleVerticalGestureDistance(Short.MIN_VALUE - 1000f));
    }
}
