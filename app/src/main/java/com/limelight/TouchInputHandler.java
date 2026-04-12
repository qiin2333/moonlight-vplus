package com.limelight;

import android.graphics.Point;
import android.hardware.input.InputManager;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.ui.StreamView;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.NativeTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.StreamSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * 处理所有触控/鼠标/触控笔/高达 750 行的 MotionEvent 逻辑。
 * 从 Game.java 提取，保持行为完全一致。
 */
public class TouchInputHandler {

    private final Game game;

    // ---- 触控上下文 (Game 初始化后赋值) ----
    static final int TOUCH_CONTEXT_LENGTH = 2;
    TouchContext[] touchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];
    final TouchContext[] absoluteTouchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];
    final TouchContext[] relativeTouchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];

    // ---- 触控私有状态 ----
    private int lastButtonState = 0;
    private long multiFingerDownTime = 0;

    // 双指右键检测
    private long twoFingerDownTime = 0;
    private long firstFingerUpTime = 0;
    private boolean twoFingerTapPending = false;
    private boolean twoFingerMoved = false;
    private float twoFingerStartX = 0, twoFingerStartY = 0;

    private long lastAbsTouchUpTime = 0;
    private long lastAbsTouchDownTime = 0;
    private float lastAbsTouchUpX, lastAbsTouchUpY;
    private float lastAbsTouchDownX, lastAbsTouchDownY;

    final Map<Integer, NativeTouchContext.Pointer> nativeTouchPointerMap = new HashMap<>();

    // 华为鼠标滚轮/中键模拟
    private float fakeScrollInitialY = -1;
    private float scrollTotal = 0;
    long lastMouseHoverTime = 0;
    private boolean waitRelease = false;
    private boolean detectScrolling = false;
    boolean detectMouseMiddle = false;       // 键盘处理也会读写
    boolean detectMouseMiddleDown = false;   // 键盘处理也会读写

    // ---- 常量 ----
    private static final int TWO_FINGER_TAP_THRESHOLD = 100;
    private static final float TWO_FINGER_MOVE_THRESHOLD = 40f;
    private static final int STYLUS_DOWN_DEAD_ZONE_DELAY = 100;
    private static final int STYLUS_DOWN_DEAD_ZONE_RADIUS = 20;
    private static final int STYLUS_UP_DEAD_ZONE_DELAY = 150;
    private static final int STYLUS_UP_DEAD_ZONE_RADIUS = 50;
    private static final int MULTI_FINGER_TAP_THRESHOLD = 300;

    public TouchInputHandler(Game game) {
        this.game = game;
    }

    // ---- 公共入口 ----

    boolean handleMotionEvent(View view, MotionEvent event) {
        if (!game.grabbedInput) {
            return false;
        }

        int eventSource = event.getSource();

        // 支持华为平板识别原生鼠标下的滚动逻辑
        if (game.prefConfig.fixMouseWheel && game.cursorVisible &&
                eventSource == InputDevice.SOURCE_MOUSE &&
                (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE ||
                        event.getActionMasked() == MotionEvent.ACTION_MOVE ||
                        event.getActionMasked() == MotionEvent.ACTION_DOWN ||
                        event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS)){
            lastMouseHoverTime = android.os.SystemClock.uptimeMillis();
            detectScrolling = true;
        }
        else if (detectScrolling){
            if (eventSource == InputDevice.SOURCE_TOUCHSCREEN && event.getPointerCount() == 1) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_CANCEL) {
                    waitRelease = true;
                }
                else if (action == MotionEvent.ACTION_DOWN) {
                    long currentTime = android.os.SystemClock.uptimeMillis();
                    long timeDiff = currentTime - lastMouseHoverTime;
                    if (timeDiff <= 40 || waitRelease){
                        fakeScrollInitialY = event.getY();
                        game.conn.sendMousePosition(
                                (short)event.getX(),
                                (short)event.getY(),
                                (short)game.streamView.getWidth(),
                                (short)game.streamView.getHeight()
                        );
                        return true;
                    }
                    else {
                        detectScrolling = false;
                        waitRelease = false;
                        scrollTotal = 0;
                    }
                }
                else if (action == MotionEvent.ACTION_MOVE) {
                    float deltaY = event.getY() - fakeScrollInitialY;
                    fakeScrollInitialY = event.getY();
                    scrollTotal = scrollTotal + deltaY;
                    if (scrollTotal > 127.99){
                        scrollTotal = scrollTotal - 128;
                        game.conn.sendMouseHighResScroll((short) 120);
                    }
                    else if (scrollTotal < -127.99){
                        scrollTotal = scrollTotal + 128;
                        game.conn.sendMouseHighResScroll((short) -120);
                    }
                    return true;
                }
                else if (action == MotionEvent.ACTION_UP) {
                    while(scrollTotal > 127.99 || scrollTotal < -127.99) {
                        if (scrollTotal > 127.99){
                            scrollTotal = scrollTotal - 128;
                            game.conn.sendMouseHighResScroll((short) 120);
                        }
                        else {
                            scrollTotal = scrollTotal + 128;
                            game.conn.sendMouseHighResScroll((short) -120);
                        }
                    }
                    if (!waitRelease) {
                        detectScrolling = false;
                    }
                    fakeScrollInitialY = -1;
                    scrollTotal = 0;
                    return true;
                }
                else {
                    detectScrolling = false;
                    waitRelease = false;
                    scrollTotal = 0;
                }
            }
            else if (waitRelease && eventSource == InputDevice.SOURCE_MOUSE && event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                waitRelease = false;
            }
            else if (!waitRelease){
                detectScrolling = false;
                scrollTotal = 0;
            }
        }

        // 支持华为鼠标中键
        if (game.prefConfig.fixMouseMiddle) {
            if (game.cursorVisible) {
                if (eventSource == InputDevice.SOURCE_MOUSE &&
                        event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
                    lastMouseHoverTime = android.os.SystemClock.uptimeMillis();
                    detectMouseMiddle = true;
                }
            }
            else if (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE &&
                    event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                lastMouseHoverTime = android.os.SystemClock.uptimeMillis();
                detectMouseMiddle = true;
            }
        }

        int deviceSources = event.getDevice() != null ? event.getDevice().getSources() : 0;

        // 本地鼠标指针模式的特殊处理
        if (game.prefConfig.enableNativeMousePointer && (eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            boolean isActualMouse = (eventSource == InputDevice.SOURCE_MOUSE) ||
                    (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    (event.getPointerCount() >= 1 &&
                            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) ||
                    (eventSource == 12290);

            if (isActualMouse) {
                LimeLog.info("Native mouse event (processing): " + event.getActionMasked() +
                        ", source: " + eventSource +
                        ", x: " + event.getX() +
                        ", y: " + event.getY() +
                        ", buttons: " + event.getButtonState());

                updateMousePosition(view, event);

                int buttonState = event.getButtonState();
                int changedButtons = buttonState ^ lastButtonState;

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                        game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    } else {
                        game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }
                if ((changedButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
                        game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    } else {
                        game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }
                if ((changedButtons & MotionEvent.BUTTON_TERTIARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0) {
                        game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    } else {
                        game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    game.conn.sendMouseHighResScroll((short) (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120));
                    game.conn.sendMouseHighResHScroll((short) (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120));
                }

                lastButtonState = buttonState;
                return true;
            }
        }

        if ((eventSource & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            return game.controllerHandler.handleMotionEvent(event);
        } else if ((deviceSources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && game.controllerHandler.tryHandleTouchpadEvent(event)) {
            return true;
        } else if ((eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) {

            if (eventSource == InputDevice.SOURCE_MOUSE ||
                    (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                    (event.getPointerCount() >= 1 &&
                            (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)) ||
                    eventSource == 12290)
            {
                int buttonState = event.getButtonState();
                int changedButtons = buttonState ^ lastButtonState;

                if (eventSource == 12290) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        buttonState |= MotionEvent.BUTTON_PRIMARY;
                    } else if (event.getAction() == MotionEvent.ACTION_UP) {
                        buttonState &= ~MotionEvent.BUTTON_PRIMARY;
                    } else {
                        buttonState |= (lastButtonState & MotionEvent.BUTTON_PRIMARY);
                    }
                    changedButtons = buttonState ^ lastButtonState;
                }

                if (!game.inputCaptureProvider.isCapturingActive()) {
                    return true;
                }

                if (game.inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    short deltaX = (short) game.inputCaptureProvider.getRelativeAxisX(event);
                    short deltaY = (short) game.inputCaptureProvider.getRelativeAxisY(event);

                    if (deltaX != 0 || deltaY != 0) {
                        if (game.prefConfig.absoluteMouseMode) {
                            StreamView activeStreamView = game.getActiveStreamView();
                            game.conn.sendMouseMoveAsMousePosition(deltaX, deltaY, (short) activeStreamView.getWidth(), (short) activeStreamView.getHeight());
                        } else {
                            game.conn.sendMouseMove(deltaX, deltaY);
                        }
                    }
                } else if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    InputDevice device = event.getDevice();
                    if (device != null) {
                        InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource);
                        InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource);

                        if (xRange != null && yRange != null && xRange.getMin() == 0 && yRange.getMin() == 0) {
                            int xMax = (int) xRange.getMax();
                            int yMax = (int) yRange.getMax();

                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                game.conn.sendMousePosition((short) event.getX(), (short) event.getY(),
                                        (short) xMax, (short) yMax);
                            }
                        }
                    }
                } else if (view != null && trySendPenEvent(view, event)) {
                    return true;
                } else if (view != null) {
                    updateMousePosition(view, event);
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    game.conn.sendMouseHighResScroll((short) (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120));
                    game.conn.sendMouseHighResHScroll((short) (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120));
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                        game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    } else {
                        game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                if ((changedButtons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    } else {
                        game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                if ((changedButtons & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    } else {
                        game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                if (game.prefConfig.mouseNavButtons) {
                    if ((changedButtons & MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                            game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1);
                        } else {
                            game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                        }
                    }

                    if ((changedButtons & MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                            game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2);
                        } else {
                            game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                        }
                    }
                }

                // Handle stylus presses
                if (event.getPointerCount() == 1 && event.getActionIndex() == 0) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);
                            game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);
                            game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);
                            game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);
                            game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                }

                lastButtonState = buttonState;
            }
            // This case is for fingers
            else
            {
                if (game.getisTouchOverrideEnabled()) {
                    game.panZoomHandler.handleTouchEvent(event);
                    return true;
                }

                if (!game.prefConfig.touchscreenTrackpad && game.prefConfig.enableEnhancedTouch && trySendTouchEvent(view, event)) {
                    return true;
                }

                if (game.virtualController != null &&
                        (game.virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons ||
                                game.virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons)) {
                    return true;
                }

                int actionIndex = event.getActionIndex();

                // Special handling for 3 finger gesture
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN &&
                        event.getPointerCount() == 3) {
                    multiFingerDownTime = event.getEventTime();

                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                    }

                    return true;
                }

                TouchContext context = getTouchContext(actionIndex);
                if (context == null) {
                    return false;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_DOWN: {
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            multiFingerDownTime = 0;
                        }
                        float[] normalizedCoords = getNormalizedCoordinates(game.streamView, event.getX(actionIndex), event.getY(actionIndex));
                        for (TouchContext touchContext : touchContextMap) {
                            touchContext.setPointerCount(event.getPointerCount());
                        }

                        if (event.getPointerCount() == 2 && game.prefConfig.touchscreenTrackpad) {
                            twoFingerDownTime = event.getEventTime();
                            twoFingerStartX = event.getX(0);
                            twoFingerStartY = event.getY(0);
                            twoFingerMoved = false;
                            twoFingerTapPending = false;
                        }

                        context.touchDownEvent((int) normalizedCoords[0], (int) normalizedCoords[1], event.getEventTime(), true);
                        break;
                    }
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_UP: {
                        float[] normalizedCoords = getNormalizedCoordinates(game.streamView, event.getX(actionIndex), event.getY(actionIndex));

                        if (multiFingerDownTime == 0 && event.getPointerCount() == 2 && !twoFingerMoved && game.prefConfig.touchscreenTrackpad) {
                            if (event.getEventTime() - twoFingerDownTime < TWO_FINGER_TAP_THRESHOLD) {
                                game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                                game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                                twoFingerTapPending = false;
                                twoFingerMoved = true;
                                if (context != null) {
                                    context.cancelTouch();
                                }
                                for (TouchContext touchContext : touchContextMap) {
                                    touchContext.setPointerCount(event.getPointerCount() - 1);
                                }
                                return true;
                            } else {
                                firstFingerUpTime = event.getEventTime();
                                twoFingerTapPending = true;
                            }
                        }

                        if (event.getPointerCount() == 1 &&
                                (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU || (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)) {
                            if (twoFingerTapPending && !twoFingerMoved && game.prefConfig.touchscreenTrackpad) {
                                if (event.getEventTime() - firstFingerUpTime < TWO_FINGER_TAP_THRESHOLD) {
                                    game.conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                                    game.conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                                    twoFingerTapPending = false;
                                    for (TouchContext touchContext : touchContextMap) {
                                        touchContext.cancelTouch();
                                        touchContext.setPointerCount(0);
                                    }
                                    return true;
                                }
                            }
                            twoFingerTapPending = false;

                            if (event.getEventTime() - multiFingerDownTime < MULTI_FINGER_TAP_THRESHOLD) {
                                game.toggleKeyboard();
                                return true;
                            }
                        }

                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                            context.cancelTouch();
                        } else {
                            context.touchUpEvent((int) normalizedCoords[0], (int) normalizedCoords[1], event.getEventTime());
                        }

                        for (TouchContext touchContext : touchContextMap) {
                            touchContext.setPointerCount(event.getPointerCount() - 1);
                        }
                        if (actionIndex == 0 && event.getPointerCount() > 1 && !context.isCancelled()) {
                            float[] normalizedSecondaryCoords = getNormalizedCoordinates(game.streamView, event.getX(1), event.getY(1));
                            context.touchDownEvent(
                                    (int) normalizedSecondaryCoords[0],
                                    (int) normalizedSecondaryCoords[1],
                                    event.getEventTime(), false);
                        }
                        break;
                    }
                    case MotionEvent.ACTION_MOVE:
                        if (event.getPointerCount() == 2 && !twoFingerMoved && game.prefConfig.touchscreenTrackpad) {
                            float dx = event.getX(0) - twoFingerStartX;
                            float dy = event.getY(0) - twoFingerStartY;
                            if (Math.sqrt(dx * dx + dy * dy) > TWO_FINGER_MOVE_THRESHOLD) {
                                twoFingerMoved = true;
                            }
                        }

                        for (int i = 0; i < event.getHistorySize(); i++) {
                            for (TouchContext aTouchContextMap : touchContextMap) {
                                if (aTouchContextMap.getActionIndex() < event.getPointerCount()) {
                                    float[] histCoords = getNormalizedCoordinates(game.streamView, event.getHistoricalX(aTouchContextMap.getActionIndex(), i), event.getHistoricalY(aTouchContextMap.getActionIndex(), i));
                                    aTouchContextMap.touchMoveEvent((int) histCoords[0], (int) histCoords[1], event.getHistoricalEventTime(i));
                                }
                            }
                        }

                        for (TouchContext aTouchContextMap : touchContextMap) {
                            if (aTouchContextMap.getActionIndex() < event.getPointerCount()) {
                                float[] currentCoords = getNormalizedCoordinates(game.streamView, event.getX(aTouchContextMap.getActionIndex()), event.getY(aTouchContextMap.getActionIndex()));
                                aTouchContextMap.touchMoveEvent((int) currentCoords[0], (int) currentCoords[1], event.getEventTime());
                            }
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        for (TouchContext aTouchContext : touchContextMap) {
                            aTouchContext.cancelTouch();
                            aTouchContext.setPointerCount(0);
                        }
                        break;
                    default:
                        return false;
                }
            }

            return true;
        }

        return false;
    }

    // ---- updateMousePosition ----

    private void updateMousePosition(View touchedView, MotionEvent event) {
        StreamView activeStreamView = game.getActiveStreamView();

        float eventX, eventY;

        if (touchedView == activeStreamView) {
            eventX = event.getX(0);
            eventY = event.getY(0);
        } else if (game.externalDisplayManager != null && game.externalDisplayManager.isUsingExternalDisplay()) {
            eventX = event.getX(0);
            eventY = event.getY(0);
        } else {
            eventX = event.getX(0) - activeStreamView.getX();
            eventY = event.getY(0) - activeStreamView.getY();
        }

        if (event.getPointerCount() == 1 && event.getActionIndex() == 0 &&
                (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (event.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchUpX, 2) + Math.pow(eventY - lastAbsTouchUpY, 2)) <= STYLUS_UP_DEAD_ZONE_RADIUS) {
                        return;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchDownX, 2) + Math.pow(eventY - lastAbsTouchDownY, 2)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS) {
                        return;
                    }
                    break;
            }
        }

        if (game.externalDisplayManager != null && game.externalDisplayManager.isUsingExternalDisplay()) {
            int streamViewWidth = activeStreamView.getWidth();
            int streamViewHeight = activeStreamView.getHeight();

            Point size = new Point();
            Display display = game.getWindowManager().getDefaultDisplay();
            display.getRealSize(size);
            int deviceWidth = size.x;
            int deviceHeight = size.y;

            float scaleX = (float) streamViewWidth / deviceWidth;
            float scaleY = (float) streamViewHeight / deviceHeight;

            float scaledX = eventX * scaleX;
            float scaledY = eventY * scaleY;

            eventX = Math.max(0, Math.min(scaledX, streamViewWidth));
            eventY = Math.max(0, Math.min(scaledY, streamViewHeight));
        } else {
            eventX = Math.min(Math.max(eventX, 0), activeStreamView.getWidth());
            eventY = Math.min(Math.max(eventY, 0), activeStreamView.getHeight());
        }

        game.conn.sendMousePosition((short) eventX, (short) eventY, (short) activeStreamView.getWidth(), (short) activeStreamView.getHeight());
    }

    // ---- Touch/Pen 事件发送相关 ----

    private byte getLiTouchTypeFromEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return MoonBridge.LI_TOUCH_EVENT_DOWN;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    return MoonBridge.LI_TOUCH_EVENT_CANCEL;
                } else {
                    return MoonBridge.LI_TOUCH_EVENT_UP;
                }
            case MotionEvent.ACTION_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_MOVE;
            case MotionEvent.ACTION_CANCEL:
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_HOVER;
            case MotionEvent.ACTION_HOVER_EXIT:
                return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE;
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY;
            default:
                return -1;
        }
    }

    private float[] getStreamViewRelativeNormalizedXY(View view, MotionEvent event, int pointerIndex) {
        StreamView activeStreamView = game.getActiveStreamView();
        if (activeStreamView == null) {
            return new float[]{0.0f, 0.0f};
        }

        float rawX = event.getX(pointerIndex);
        float rawY = event.getY(pointerIndex);

        if (game.externalDisplayManager != null && game.externalDisplayManager.isUsingExternalDisplay()) {
            float touchWidth, touchHeight;
            if (view != null && view.getWidth() > 0 && view.getHeight() > 0) {
                touchWidth = view.getWidth();
                touchHeight = view.getHeight();
            } else {
                Point size = new Point();
                game.getWindowManager().getDefaultDisplay().getRealSize(size);
                touchWidth = size.x;
                touchHeight = size.y;
            }
            float normalizedX = Math.max(0.0f, Math.min(1.0f, rawX / touchWidth));
            float normalizedY = Math.max(0.0f, Math.min(1.0f, rawY / touchHeight));
            return new float[]{normalizedX, normalizedY};
        }

        float scaleX = activeStreamView.getScaleX();
        float scaleY = activeStreamView.getScaleY();

        if (scaleX == 0 || scaleY == 0) {
            return new float[]{0.0f, 0.0f};
        }

        float absoluteX = (rawX - activeStreamView.getX()) / scaleX;
        float absoluteY = (rawY - activeStreamView.getY()) / scaleY;

        int streamWidth = activeStreamView.getWidth();
        int streamHeight = activeStreamView.getHeight();

        if (streamWidth == 0 || streamHeight == 0) {
            return new float[]{0.0f, 0.0f};
        }

        float normalizedX = absoluteX / streamWidth;
        float normalizedY = absoluteY / streamHeight;

        normalizedX = Math.max(0.0f, Math.min(1.0f, normalizedX));
        normalizedY = Math.max(0.0f, Math.min(1.0f, normalizedY));

        return new float[]{normalizedX, normalizedY};
    }

    private static float normalizeValueInRange(float value, InputDevice.MotionRange range) {
        return (value - range.getMin()) / range.getRange();
    }

    private static float getPressureOrDistance(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                if (dev != null) {
                    InputDevice.MotionRange distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.getSource());
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange);
                    }
                }
                return 0.0f;
            default:
                return event.getPressure(pointerIndex);
        }
    }

    private static short getRotationDegrees(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null) {
                short rotationDegrees = (short) Math.toDegrees(event.getOrientation(pointerIndex));
                if (rotationDegrees < 0) {
                    rotationDegrees += 360;
                }
                return rotationDegrees;
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN;
    }

    private static float[] polarToCartesian(float r, float theta) {
        return new float[]{(float) (r * Math.cos(theta)), (float) (r * Math.sin(theta))};
    }

    private static float cartesianToR(float[] point) {
        return (float) Math.sqrt(Math.pow(point[0], 2) + Math.pow(point[1], 2));
    }

    private float[] getStreamViewNormalizedContactArea(MotionEvent event, int pointerIndex) {
        float orientation;

        if (event.getDevice() == null || event.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) == null) {
            orientation = (float) (Math.PI / 4);
        } else {
            orientation = event.getOrientation(pointerIndex);
        }

        float contactAreaMajor, contactAreaMinor;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                contactAreaMajor = event.getToolMajor(pointerIndex);
                contactAreaMinor = event.getToolMinor(pointerIndex);
                break;
            default:
                contactAreaMajor = event.getTouchMajor(pointerIndex);
                contactAreaMinor = event.getTouchMinor(pointerIndex);
                break;
        }

        float[] contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation);
        float[] contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (float) (orientation + (Math.PI / 2)));

        StreamView refView = game.getActiveStreamView();
        int refWidth = (refView != null && refView.getWidth() > 0) ? refView.getWidth() : game.streamView.getWidth();
        int refHeight = (refView != null && refView.getHeight() > 0) ? refView.getHeight() : game.streamView.getHeight();
        if (refWidth == 0) refWidth = 1;
        if (refHeight == 0) refHeight = 1;
        contactAreaMajorCartesian[0] = Math.min(Math.abs(contactAreaMajorCartesian[0]), refWidth) / refWidth;
        contactAreaMinorCartesian[0] = Math.min(Math.abs(contactAreaMinorCartesian[0]), refWidth) / refWidth;
        contactAreaMajorCartesian[1] = Math.min(Math.abs(contactAreaMajorCartesian[1]), refHeight) / refHeight;
        contactAreaMinorCartesian[1] = Math.min(Math.abs(contactAreaMinorCartesian[1]), refHeight) / refHeight;

        return new float[]{cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian)};
    }

    private boolean sendPenEventForPointer(View view, MotionEvent event, byte eventType, byte toolType, int pointerIndex) {
        byte penButtons = 0;
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_PRIMARY;
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_SECONDARY;
        }

        byte tiltDegrees = MoonBridge.LI_TILT_UNKNOWN;
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.getSource()) != null) {
                tiltDegrees = (byte) Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex));
            }
        }

        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return game.conn.sendPenEvent(eventType, toolType, penButtons,
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex), tiltDegrees) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private static byte convertToolTypeToStylusToolType(MotionEvent event, int pointerIndex) {
        switch (event.getToolType(pointerIndex)) {
            case MotionEvent.TOOL_TYPE_ERASER:
                return MoonBridge.LI_TOOL_TYPE_ERASER;
            case MotionEvent.TOOL_TYPE_STYLUS:
                return MoonBridge.LI_TOOL_TYPE_PEN;
            default:
                return MoonBridge.LI_TOOL_TYPE_UNKNOWN;
        }
    }

    private boolean trySendPenEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            boolean handledStylusEvent = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                byte toolType = convertToolTypeToStylusToolType(event, i);
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    continue;
                } else {
                    handledStylusEvent = true;
                }

                if (game.prefConfig.enableEnhancedTouch) {
                    NativeTouchContext.Pointer pointer = nativeTouchPointerMap.get(event.getPointerId(i));
                    if (pointer != null) {
                        pointer.updatePointerCoords(event, i);
                    }
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    return false;
                }
            }
            return handledStylusEvent;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            return game.conn.sendPenEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, (byte) 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        } else {
            byte toolType = convertToolTypeToStylusToolType(event, event.getActionIndex());
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                return false;
            }

            if (game.prefConfig.enableEnhancedTouch) {
                int actionIndex = event.getActionIndex();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_HOVER_ENTER:
                        NativeTouchContext.Pointer pointer = new NativeTouchContext.Pointer(event);
                        nativeTouchPointerMap.put(pointer.getPointerId(), pointer);
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_HOVER_EXIT:
                        nativeTouchPointerMap.remove(event.getPointerId(actionIndex));
                        break;
                    case MotionEvent.ACTION_HOVER_MOVE:
                        NativeTouchContext.Pointer hoverPointer = nativeTouchPointerMap.get(event.getPointerId(actionIndex));
                        if (hoverPointer != null) {
                            hoverPointer.updatePointerCoords(event, actionIndex);
                        }
                        break;
                }
            }

            return sendPenEventForPointer(view, event, eventType, toolType, event.getActionIndex());
        }
    }

    private boolean sendTouchEventForPointer(View view, MotionEvent event, byte eventType, int pointerIndex) {
        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return game.conn.sendTouchEvent(eventType, event.getPointerId(pointerIndex),
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex)) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private boolean trySendTouchEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int pointerCount = event.getPointerCount();
            if (game.prefConfig.enableEnhancedTouch) {
                for (int i = 0; i < pointerCount; i++) {
                    NativeTouchContext.Pointer pointer = nativeTouchPointerMap.get(event.getPointerId(i));
                    if (pointer != null) {
                        pointer.updatePointerCoords(event, i);
                    }
                    if (!sendTouchEventForPointer(view, event, eventType, i)) {
                        return false;
                    }
                }
            } else {
                for (int i = 0; i < pointerCount; i++) {
                    if (!sendTouchEventForPointer(view, event, eventType, i)) {
                        return false;
                    }
                }
            }
            return true;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            return game.conn.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        } else {
            int actionIndex = event.getActionIndex();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    multiFingerTapChecker(event);
                case MotionEvent.ACTION_DOWN:
                    if (game.prefConfig.enableEnhancedTouch) {
                        NativeTouchContext.Pointer pointer = new NativeTouchContext.Pointer(event);
                        nativeTouchPointerMap.put(pointer.getPointerId(), pointer);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - multiFingerDownTime < MULTI_FINGER_TAP_THRESHOLD) {
                        game.toggleKeyboard();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (game.prefConfig.enableEnhancedTouch) {
                        nativeTouchPointerMap.remove(event.getPointerId(actionIndex));
                    }
                    break;
            }
            return sendTouchEventForPointer(view, event, eventType, actionIndex);
        }
    }

    private void multiFingerTapChecker(MotionEvent event) {
        if (event.getPointerCount() == game.prefConfig.nativeTouchFingersToToggleKeyboard) {
            multiFingerDownTime = event.getEventTime();
        }
    }

    // ---- 坐标归一化 ----

    private float[] getNormalizedCoordinates(View streamView, float rawX, float rawY) {
        if (streamView == null) {
            return new float[]{rawX, rawY};
        }

        if (game.externalDisplayManager != null && game.externalDisplayManager.isUsingExternalDisplay()) {
            StreamView active = game.getActiveStreamView();
            if (active != null && active.getWidth() > 0 && active.getHeight() > 0) {
                Point size = new Point();
                game.getWindowManager().getDefaultDisplay().getRealSize(size);
                float scaleX = (float) active.getWidth() / size.x;
                float scaleY = (float) active.getHeight() / size.y;
                return new float[]{rawX * scaleX, rawY * scaleY};
            }
            return new float[]{rawX, rawY};
        }

        float scaleX = streamView.getScaleX();
        float scaleY = streamView.getScaleY();

        if (scaleX == 0 || scaleY == 0) {
            return new float[]{rawX, rawY};
        }

        float normalizedX = (rawX - streamView.getX()) / scaleX;
        float normalizedY = (rawY - streamView.getY()) / scaleY;

        return new float[]{normalizedX, normalizedY};
    }

    // ---- 工具方法 ----

    private TouchContext getTouchContext(int actionIndex) {
        if (actionIndex < touchContextMap.length) {
            return touchContextMap[actionIndex];
        } else {
            return null;
        }
    }

    public RelativeTouchContext[] getRelativeTouchContextMap() {
        RelativeTouchContext[] result = new RelativeTouchContext[relativeTouchContextMap.length];
        for (int i = 0; i < relativeTouchContextMap.length; i++) {
            if (relativeTouchContextMap[i] instanceof RelativeTouchContext) {
                result[i] = (RelativeTouchContext) relativeTouchContextMap[i];
            }
        }
        return result;
    }

    public void setTouchMode(boolean enableRelativeTouch) {
        for (int i = 0; i < touchContextMap.length; i++) {
            if (enableRelativeTouch) {
                game.prefConfig.touchscreenTrackpad = true;
                game.prefConfig.enableNativeMousePointer = false;
                touchContextMap = relativeTouchContextMap;
                game.cursorServiceManager.refreshLocalCursorState(game.prefConfig.enableLocalCursorRendering);
            } else {
                game.prefConfig.touchscreenTrackpad = false;
                touchContextMap = absoluteTouchContextMap;
                game.cursorServiceManager.refreshLocalCursorState(false);
            }
        }
    }

    public void setEnhancedTouch(boolean enableRelativeTouch) {
        game.prefConfig.enableEnhancedTouch = enableRelativeTouch;
        if (game.prefConfig.enableEnhancedTouch) {
            game.prefConfig.enableNativeMousePointer = false;
        }
    }

    /**
     * 初始化触控上下文（由 Game 在 onCreate / prepareConnection 中调用）
     */
    public void initTouchContexts(com.limelight.nvstream.NvConnection conn, StreamView streamView,
                                   com.limelight.preferences.PreferenceConfiguration prefConfig) {
        for (int i = 0; i < TOUCH_CONTEXT_LENGTH; i++) {
            absoluteTouchContextMap[i] = new AbsoluteTouchContext(conn, i, streamView);
            relativeTouchContextMap[i] = new RelativeTouchContext(conn, i, streamView, prefConfig);
        }
        if (!prefConfig.touchscreenTrackpad) {
            touchContextMap = absoluteTouchContextMap;
        } else {
            touchContextMap = relativeTouchContextMap;
        }
    }
}
