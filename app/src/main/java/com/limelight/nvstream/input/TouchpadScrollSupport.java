package com.limelight.nvstream.input;

public final class TouchpadScrollSupport {
    public static final int HIGH_RES_SCROLL_UNITS = 120;
    public static final int LI_FF_TOUCHPAD_SCROLL_EVENTS = 0x04;

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

    public static short scaleAxisValue(float axisValue) {
        return (short) Math.round(axisValue * HIGH_RES_SCROLL_UNITS);
    }

    public static byte phaseForDelta(boolean gestureActive) {
        return gestureActive ? LI_TOUCHPAD_SCROLL_PHASE_CHANGED : LI_TOUCHPAD_SCROLL_PHASE_BEGAN;
    }
}
