package com.limelight.binding.input.advance_setting;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

public class KeyboardGestureDetector {

    public interface GestureListener {
        void onKeyPress(int keyCode);
        void onKeyRelease(int keyCode);
        void onModifierHoldRelease(int keyCode);
        void onLongPress(int keyCode);
        void onDoubleTap(int keyCode);
    }

    private final GestureListener listener;
    private final int touchSlop;
    private long lastDownTime = 0;
    private long downTimeMs = 0;
    private static final int DOUBLE_TAP_TIMEOUT = 250;
    private static final int HOLD_THRESHOLD = 200;

    public KeyboardGestureDetector(View view, GestureListener listener) {
        this.listener = listener;
        this.touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
    }

    public boolean onTouchEvent(View v, MotionEvent event) {
        String tag = (String) v.getTag();
        if (tag == null || !tag.startsWith("k")) return false;
        int keyCode = Integer.parseInt(tag.substring(1));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                v.setPressed(true);
                v.refreshDrawableState();
                downTimeMs = System.currentTimeMillis();
                
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastDownTime < DOUBLE_TAP_TIMEOUT) {
                    listener.onDoubleTap(keyCode);
                    lastDownTime = 0; // Reset to prevent triple-taps triggering it again
                } else {
                    listener.onKeyPress(keyCode);
                    lastDownTime = currentTime;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                v.refreshDrawableState();
                long pressDuration = System.currentTimeMillis() - downTimeMs;
                if (pressDuration >= HOLD_THRESHOLD) {
                    listener.onModifierHoldRelease(keyCode);
                } else {
                    listener.onKeyRelease(keyCode);
                }
                return true;
        }
        return false;
    }
}
