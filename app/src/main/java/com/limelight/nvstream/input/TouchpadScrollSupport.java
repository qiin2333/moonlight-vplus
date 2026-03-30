package com.limelight.nvstream.input;

import android.view.InputDevice;
import android.view.MotionEvent;

public final class TouchpadScrollSupport {
    public static final int HIGH_RES_SCROLL_UNITS = 120;
    public static final int LI_FF_TOUCHPAD_SCROLL_EVENTS = 0x04;
    private static final float TOUCHPAD_AXIS_SCROLL_SCALE = 16.0f;
    private static final float TOUCHPAD_AXIS_SCROLL_COMPRESSION = 6.0f;
    private static final short TOUCHPAD_AXIS_SCROLL_MAX_DELTA = 6;
    private static final float TOUCHPAD_GESTURE_SCROLL_SCALE = 0.30f;
    private static final float TOUCHPAD_GESTURE_SCROLL_COMPRESSION = 8.0f;
    private static final short TOUCHPAD_GESTURE_SCROLL_MAX_DELTA = 8;
    private static final float TOUCHPAD_LEGACY_SCROLL_SCALE = 0.60f;
    private static final float TOUCHPAD_LEGACY_SCROLL_COMPRESSION = 8.0f;
    private static final short TOUCHPAD_LEGACY_SCROLL_MAX_DELTA = 8;

    public static final byte LI_TOUCHPAD_SCROLL_PHASE_NONE = 0x00;
    public static final byte LI_TOUCHPAD_SCROLL_PHASE_BEGAN = 0x01;
    public static final byte LI_TOUCHPAD_SCROLL_PHASE_CHANGED = 0x02;
    public static final byte LI_TOUCHPAD_SCROLL_PHASE_ENDED = 0x04;
    public static final byte LI_TOUCHPAD_SCROLL_PHASE_CANCELLED = 0x08;
    public static final byte LI_TOUCHPAD_SCROLL_PHASE_MAY_BEGIN = (byte) 0x80;

    public static final byte LI_TOUCHPAD_SCROLL_MOMENTUM_PHASE_NONE = 0x00;
    public static final byte LI_TOUCHPAD_SCROLL_MOMENTUM_PHASE_BEGIN = 0x01;
    public static final byte LI_TOUCHPAD_SCROLL_MOMENTUM_PHASE_CONTINUE = 0x02;
    public static final byte LI_TOUCHPAD_SCROLL_MOMENTUM_PHASE_END = 0x03;

    public static final long GESTURE_END_DELAY_MS = 50L;

    private TouchpadScrollSupport() {
    }

    public static boolean supportsExplicitTouchpadScroll(int hostFeatureFlags) {
        return (hostFeatureFlags & LI_FF_TOUCHPAD_SCROLL_EVENTS) != 0;
    }

    public static boolean isTouchpadSource(int eventSource) {
        return eventSource == InputDevice.SOURCE_TOUCHPAD ||
                (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0;
    }

    public static boolean shouldUseClassificationTouchpadScroll(boolean nativeMousePointerEnabled,
                                                                int eventSource,
                                                                int toolType,
                                                                int classification) {
        return nativeMousePointerEnabled &&
                (toolType == MotionEvent.TOOL_TYPE_MOUSE ||
                        eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                        (eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                        isTouchpadSource(eventSource)) &&
                classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE;
    }

    public static boolean shouldUseLegacyMultiPointerScroll(boolean nativeMousePointerEnabled,
                                                            int eventSource,
                                                            int pointerCount) {
        return nativeMousePointerEnabled &&
                isTouchpadSource(eventSource) &&
                pointerCount >= 2;
    }

    public static short scaleAxisValue(float axisValue) {
        return (short) Math.round(axisValue * HIGH_RES_SCROLL_UNITS);
    }

    public static short scaleVerticalAxisValue(float axisValue) {
        return scaleAxisValue(-axisValue);
    }

    public static short scaleTouchpadAxisScroll(float axisValue) {
        return compressTouchpadScroll(axisValue,
                TOUCHPAD_AXIS_SCROLL_SCALE,
                TOUCHPAD_AXIS_SCROLL_COMPRESSION,
                TOUCHPAD_AXIS_SCROLL_MAX_DELTA);
    }

    public static short scaleVerticalTouchpadAxisScroll(float axisValue) {
        return scaleTouchpadAxisScroll(-axisValue);
    }

    public static short scaleGestureDistance(float distance) {
        long roundedDistance = Math.round(distance);
        if (roundedDistance > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (roundedDistance < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) roundedDistance;
    }

    public static short scaleVerticalGestureDistance(float distance) {
        return scaleGestureDistance(-distance);
    }

    public static short scaleTouchpadGestureScrollDistance(float distance) {
        return compressTouchpadScroll(distance,
                TOUCHPAD_GESTURE_SCROLL_SCALE,
                TOUCHPAD_GESTURE_SCROLL_COMPRESSION,
                TOUCHPAD_GESTURE_SCROLL_MAX_DELTA);
    }

    public static short scaleVerticalTouchpadGestureScrollDistance(float distance) {
        return scaleTouchpadGestureScrollDistance(-distance);
    }

    public static short scaleTouchpadLegacyScrollDelta(float delta) {
        return compressTouchpadScroll(delta,
                TOUCHPAD_LEGACY_SCROLL_SCALE,
                TOUCHPAD_LEGACY_SCROLL_COMPRESSION,
                TOUCHPAD_LEGACY_SCROLL_MAX_DELTA);
    }

    public static short scaleVerticalTouchpadLegacyScrollDelta(float delta) {
        return scaleTouchpadLegacyScrollDelta(-delta);
    }

    public static byte phaseForDelta(boolean gestureActive) {
        return gestureActive ? LI_TOUCHPAD_SCROLL_PHASE_CHANGED : LI_TOUCHPAD_SCROLL_PHASE_BEGAN;
    }

    private static short compressTouchpadScroll(float rawValue,
                                                float linearScale,
                                                float compressionThreshold,
                                                short maxDelta) {
        if (rawValue == 0f) {
            return 0;
        }

        float scaledValue = rawValue * linearScale;
        float compressedValue = scaledValue / (1.0f + (Math.abs(scaledValue) / compressionThreshold));
        long roundedValue = Math.round(compressedValue);
        long clampedValue = Math.max(-maxDelta, Math.min(maxDelta, roundedValue));
        return (short) clampedValue;
    }
}
