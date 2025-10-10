package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.limelight.Game;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

public class RelativeTouchContext implements TouchContext {
    private int lastTouchX = 0;
    private int lastTouchY = 0;
    private int originalTouchX = 0;
    private int originalTouchY = 0;
    private long originalTouchTime = 0;
    private boolean cancelled;
    private boolean confirmedMove;
    private boolean confirmedDrag;
    private boolean confirmedScroll;
    private double distanceMoved;
    private double xFactor = 0.6;
    private double yFactor = 0.6;
    private double sense = 1;
    private int pointerCount;
    private int maxPointerCountInGesture;

    private long lastTapUpTime = 0;
    /** 记录上一次成功单击的结束位置X */
    private int lastTapUpX = 0;
    /** 记录上一次成功单击的结束位置Y */
    private int lastTapUpY = 0;
    /** 标志位，表示当前是否处于“双击并按住”触发的拖拽模式 */
    private boolean isDoubleClickDrag = false;
    private boolean isPotentialDoubleClick = false;

    private final NvConnection conn;
    private final int actionIndex;
    private final View targetView;
    private final PreferenceConfiguration prefConfig;
    private final Handler handler;

    private final Runnable[] buttonUpRunnables;

    private final Runnable dragTimerRunnable = new Runnable() {
        @Override
        public void run() {
            // Check if someone already set move
            if (confirmedMove) {
                return;
            }

            // The drag should only be processed for the primary finger
            if (actionIndex != maxPointerCountInGesture - 1) {
                return;
            }

            // We haven't been cancelled before the timer expired so begin dragging
            confirmedDrag = true;
            conn.sendMouseButtonDown(getMouseButtonIndex());
        }
    };

    private static final int TAP_MOVEMENT_THRESHOLD = 40;
    private static final int TAP_DISTANCE_THRESHOLD = 50;
    private static final int TAP_TIME_THRESHOLD = 250;
    private static final int DRAG_TIME_THRESHOLD = 650;
    private static final int DRAG_START_THRESHOLD = 10;
    private static final int DOUBLE_TAP_TIME_THRESHOLD = 300;
    /** 定义双击时，两次点击位置的最大允许偏差 */
    private static final int DOUBLE_TAP_MOVEMENT_THRESHOLD = 40;

    private static final int SCROLL_SPEED_FACTOR = 5;

    public RelativeTouchContext(NvConnection conn, int actionIndex,
                                View view, PreferenceConfiguration prefConfig)
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.targetView = view;
        this.prefConfig = prefConfig;
        this.handler = new Handler(Looper.getMainLooper());
        this.buttonUpRunnables = new Runnable[] {
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT),
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE),
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT),
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1),
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2)
        };

    }

    @Override
    public int getActionIndex() { return actionIndex; }

    private boolean isWithinTapBounds(int touchX, int touchY)
    {
        int xDelta = Math.abs(touchX - originalTouchX);
        int yDelta = Math.abs(touchY - originalTouchY);
        return xDelta <= TAP_MOVEMENT_THRESHOLD && yDelta <= TAP_MOVEMENT_THRESHOLD;
    }

    private boolean isTap(long eventTime)
    {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false;
        }

        // If this input wasn't the last finger down, do not report
        // a tap. This ensures we don't report duplicate taps for each
        // finger on a multi-finger tap gesture
        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD;
    }

    private byte getMouseButtonIndex() {
        return (actionIndex == 1) ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT;
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger)
    {
        // Get the view dimensions to scale inputs on this touch
        xFactor = Game.REFERENCE_HORIZ_RES / (double)targetView.getWidth() * sense;
        yFactor = Game.REFERENCE_VERT_RES / (double)targetView.getHeight() * sense;

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;

        if (isNewFinger) {
            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedDrag = confirmedMove = confirmedScroll = isDoubleClickDrag = false;
            distanceMoved = 0;

            // 只有当功能开关开启时，才检查双击（动态读取配置）
            if (prefConfig.enableDoubleClickDrag) {
                long timeSinceLastTap = eventTime - lastTapUpTime;
                int xDelta = Math.abs(eventX - lastTapUpX);
                int yDelta = Math.abs(eventY - lastTapUpY);

                if (actionIndex == 0 && timeSinceLastTap <= DOUBLE_TAP_TIME_THRESHOLD &&
                        xDelta <= DOUBLE_TAP_MOVEMENT_THRESHOLD && yDelta <= DOUBLE_TAP_MOVEMENT_THRESHOLD) {
                    // It's a double-tap. The first tap has already been sent,
                    // so we just need to prevent the drag timer from firing for
                    // this second tap and flag that we're in a potential double-click.
                    isPotentialDoubleClick = true;
                    cancelDragTimer();
                    return true;
                }
            }

            isPotentialDoubleClick = false;

            if (actionIndex == 0) {
                // Start the timer for engaging a drag
                startDragTimer();
            }
        }

        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return;
        }

        // 决策点1：如果在“待定”状态下抬起，说明用户意图是“双击”
        if (isPotentialDoubleClick) {
            // This is the second tap of a double-tap. Send the click now.
            isPotentialDoubleClick = false;

            byte buttonIndex = MouseButtonPacket.BUTTON_LEFT;
            conn.sendMouseButtonDown(buttonIndex);
            Runnable buttonUpRunnable = buttonUpRunnables[buttonIndex - 1];
            handler.removeCallbacks(buttonUpRunnable);
            handler.postDelayed(buttonUpRunnable, 100);

            // Invalidate the tap time to prevent a triple-tap from becoming a double-tap drag
            lastTapUpTime = 0;
            return;
        }

        if (isDoubleClickDrag) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            isDoubleClickDrag = false;
            // 重置tap记录，确保下次是全新的单击
            lastTapUpTime = 0;
            return;
        }
        // --- 结束新增逻辑 ---

        // Cancel the drag timer
        cancelDragTimer();

        byte buttonIndex = getMouseButtonIndex();

        if (confirmedDrag) {
            // Raise the button after a drag
            conn.sendMouseButtonUp(buttonIndex);
        }
        else if (isTap(eventTime))
        {

            // --- 新增逻辑: 在确认是tap后，记录时间和位置 ---
            // 只有左键单击（主手指）才记录，用于双击检测
            if (buttonIndex == MouseButtonPacket.BUTTON_LEFT) {
                lastTapUpTime = eventTime;
                lastTapUpX = eventX;
                lastTapUpY = eventY;
            } else {
                // 如果是右键或其他点击，则清除记录，打断双击链
                lastTapUpTime = 0;
            }
            // --- 结束新增逻辑 ---

            // Lower the mouse button
            conn.sendMouseButtonDown(buttonIndex);

            // Release the mouse button in 100ms to allow for apps that use polling
            // to detect mouse button presses.
            Runnable buttonUpRunnable = buttonUpRunnables[buttonIndex - 1];
            handler.removeCallbacks(buttonUpRunnable);
            handler.postDelayed(buttonUpRunnable, 100);
        } else {
            // 无效点击，重置
            lastTapUpTime = 0;
        }
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        // 决策点2：如果在“待定”状态下移动，说明用户意图是“双击拖拽”
        if (isPotentialDoubleClick) {
            int xDelta = Math.abs(eventX - originalTouchX);
            int yDelta = Math.abs(eventY - originalTouchY);
            if (xDelta > DRAG_START_THRESHOLD || yDelta > DRAG_START_THRESHOLD) {
                // Start a double-tap drag
                isPotentialDoubleClick = false;
                isDoubleClickDrag = true;
                confirmedMove = true;

                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            }
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            checkForConfirmedMove(eventX, eventY);
            checkForConfirmedScroll();

            // We only send moves and drags for the primary touch point
            if (actionIndex == 0) {
                int deltaX = eventX - lastTouchX;
                int deltaY = eventY - lastTouchY;
                deltaX = (int) Math.round(Math.abs(deltaX) * xFactor * (eventX < lastTouchX ? -1 : 1));
                deltaY = (int) Math.round(Math.abs(deltaY) * yFactor * (eventY < lastTouchY ? -1 : 1));

                if (pointerCount == 2) {
                    if (confirmedScroll) {
                        conn.sendMouseHighResScroll((short)(deltaY * SCROLL_SPEED_FACTOR));
                    }
                } else if (confirmedMove || isDoubleClickDrag || confirmedDrag) { // 只在确认移动/拖拽时发送
                    if (prefConfig.absoluteMouseMode) {
                        conn.sendMouseMoveAsMousePosition(
                                (short) deltaX,
                                (short) deltaY,
                                (short) targetView.getWidth(),
                                (short) targetView.getHeight());
                    }
                    else {
                        conn.sendMouseMove((short) deltaX, (short) deltaY);
                    }
                }

                // If the scaling factor ended up rounding deltas to zero, wait until they are
                // non-zero to update lastTouch that way devices that report small touch events often
                // will work correctly
                if (deltaX != 0) {
                    lastTouchX = eventX;
                }
                if (deltaY != 0) {
                    lastTouchY = eventY;
                }
            }
            else {
                lastTouchX = eventX;
                lastTouchY = eventY;
            }
        }

        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true;

        // Cancel the drag timer
        cancelDragTimer();

        // --- 新增逻辑 ---
        if (isDoubleClickDrag) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            isDoubleClickDrag = false;
        }
        // --- 结束新增逻辑 ---

        // If it was a confirmed drag, we'll need to raise the button now
        if (confirmedDrag) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
        }
        // 重置所有状态
        lastTapUpTime = 0;
        isPotentialDoubleClick = false;
    }

    private void startDragTimer() {
        cancelDragTimer();
        handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD);
    }
    private void cancelDragTimer() { handler.removeCallbacks(dragTimerRunnable); }
    private void checkForConfirmedMove(int eventX, int eventY) {
        if (confirmedMove || confirmedDrag || isPotentialDoubleClick) return; // 在待定状态下，由moveEvent自己决策
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true;
            cancelDragTimer();
            return;
        }
        distanceMoved += Math.sqrt(Math.pow(eventX - lastTouchX, 2) + Math.pow(eventY - lastTouchY, 2));
        if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
            confirmedMove = true;
            cancelDragTimer();
        }
    }
    private void checkForConfirmedScroll() {
        confirmedScroll = (actionIndex == 0 && pointerCount == 2 && confirmedMove);
    }
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }
    }

    public void adjustMsense(double sense){
        this.sense = sense;
    }
}
