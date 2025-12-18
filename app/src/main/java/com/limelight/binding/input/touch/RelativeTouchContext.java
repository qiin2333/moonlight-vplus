package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.SurfaceHolder;

import com.limelight.Game;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.CursorView;

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
    /** 标志位，表示当前是否处于"双击并按住"触发的拖拽模式 */
    private boolean isDoubleClickDrag = false;
    /** 标志位，表示当前手势可能是双击的第二次点击，处于"待定"状态 */
    private boolean isPotentialDoubleClick = false;

    private final NvConnection conn;
    private final int actionIndex;
    private final View targetView;
    private final PreferenceConfiguration prefConfig;
    private final Handler handler;

    private final Runnable[] buttonUpRunnables;

    // 用于延迟发送单击事件的Runnable
    private Runnable singleTapRunnable;
    //  用于处理“双击并按住”的计时器
    private Runnable doubleTapHoldRunnable;

    // 本地光标渲染器 - 用于显示虚拟鼠标光标
    private LocalCursorRenderer localCursorRenderer;
    // 是否启用本地光标渲染
    private boolean enableLocalCursorRendering = true;

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
    // 定义2次点击的间隔小于多久才为双击按住
    private final int DOUBLE_TAP_TIME_THRESHOLD;
    //  定义双击后按住多久确认为拖拽
    private static final int DOUBLE_TAP_HOLD_TO_DRAG_THRESHOLD = 200;
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
        
        // 从配置中读取双击时间阈值
        this.DOUBLE_TAP_TIME_THRESHOLD = prefConfig.doubleTapTimeThreshold;
        
        this.buttonUpRunnables = new Runnable[] {
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT),
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE),
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT),
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1),
                () -> conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2)
        };

    }

    /**
     * 初始化本地光标渲染器
     */
    public void initializeLocalCursorRenderer(CursorView cursorOverlay, int width, int height) {
        if (localCursorRenderer != null) {
            localCursorRenderer.destroy();
        }
        localCursorRenderer = new LocalCursorRenderer(cursorOverlay, width, height);
    }

    /**
     * 销毁本地光标渲染器
     */
    public void destroyLocalCursorRenderer() {
        if (localCursorRenderer != null) {
            localCursorRenderer.hide();
            localCursorRenderer.destroy();
            localCursorRenderer = null;
        }
    }

    /**
     * 设置是否启用本地光标渲染
     */
    public void setEnableLocalCursorRendering(boolean enable) {
        this.enableLocalCursorRendering = enable;
        if (localCursorRenderer != null) {
            if (enable) {
                localCursorRenderer.show();
            } else {
                localCursorRenderer.hide();
            }
        }
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
            // 新手势开始时，取消可能存在的延迟单击任务
            cancelSingleTapTimer();
            //  新手势开始，取消任何可能存在的按住计时器
            cancelDoubleTapHoldTimer();

            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedDrag = confirmedMove = confirmedScroll = isDoubleClickDrag = false;
            distanceMoved = 0;

            isPotentialDoubleClick = false; // 重置双击待定状态

            if (prefConfig.enableDoubleClickDrag) {
                long timeSinceLastTap = eventTime - lastTapUpTime;
                int xDelta = Math.abs(eventX - lastTapUpX);
                int yDelta = Math.abs(eventY - lastTapUpY);

                if (actionIndex == 0 && timeSinceLastTap <= DOUBLE_TAP_TIME_THRESHOLD &&
                        xDelta <= DOUBLE_TAP_MOVEMENT_THRESHOLD && yDelta <= DOUBLE_TAP_MOVEMENT_THRESHOLD) {

                    //  符合双击条件，取消第一次单击的发送，进入“待定”状态
                    cancelSingleTapTimer(); // 关键：阻止第一次单击事件发送
                    isPotentialDoubleClick = true;
                    cancelDragTimer();

                    //  启动“按住确认拖拽”计时器
                    startDoubleTapHoldTimer();
                    return true;
                }
            }

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
            //  用户抬起了，说明是双击，取消“按住确认拖拽”计时器
            cancelDoubleTapHoldTimer();

            isPotentialDoubleClick = false;

            // 立即发送一次完整的点击 (模拟第一次点击)
            byte buttonIndex = MouseButtonPacket.BUTTON_LEFT;
            conn.sendMouseButtonDown(buttonIndex);
            conn.sendMouseButtonUp(buttonIndex);

            // 紧接着发送第二次点击
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
            lastTapUpTime = 0;
            return;
        }

        cancelDragTimer();

        byte buttonIndex = getMouseButtonIndex();

        if (confirmedDrag) {
            conn.sendMouseButtonUp(buttonIndex);
            // 拖动结束后重置点击时间，避免影响后续的双指右键
            lastTapUpTime = 0;
        }
        else if (isTap(eventTime))
        {
            // 只有在双击拖拽功能开启时，才需要延迟单击以判断是否为双击
            if (prefConfig.enableDoubleClickDrag && buttonIndex == MouseButtonPacket.BUTTON_LEFT) {
                // 记录时间和位置，用于下一次的touchDown判断
                lastTapUpTime = eventTime;
                lastTapUpX = eventX;
                lastTapUpY = eventY;

                // 创建一个“单击”任务，并延迟执行
                singleTapRunnable = () -> {
                    conn.sendMouseButtonDown(buttonIndex);
                    Runnable buttonUpRunnable = buttonUpRunnables[buttonIndex - 1];
                    handler.postDelayed(buttonUpRunnable, 100);
                    singleTapRunnable = null; // 执行后清空
                };
                handler.postDelayed(singleTapRunnable, DOUBLE_TAP_TIME_THRESHOLD);
            } else {
                // 如果功能关闭，或者不是左键单击（如右键），则立即发送，不延迟
                lastTapUpTime = 0; // 清除非左键单击的记录

                conn.sendMouseButtonDown(buttonIndex);

                // Release the mouse button in 100ms to allow for apps that use polling
                // to detect mouse button presses.
                Runnable buttonUpRunnable = buttonUpRunnables[buttonIndex - 1];
                handler.removeCallbacks(buttonUpRunnable);
                handler.postDelayed(buttonUpRunnable, 100);
            }
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
                //  用户移动了，说明是拖拽，取消“按住确认拖拽”计时器
                cancelDoubleTapHoldTimer();
                // 确认是双击拖拽，此时才发送鼠标按下事件
                isPotentialDoubleClick = false;
                isDoubleClickDrag = true;
                confirmedMove = true; // 标记为已移动，避免后续逻辑冲突

                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            }
        }

        //  如果发生移动，说明不是单击，取消待处理的单击任务
        if (!isWithinTapBounds(eventX, eventY)) {
            cancelSingleTapTimer();
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            checkForConfirmedMove(eventX, eventY);
            checkForConfirmedScroll();

            if (actionIndex == 0) {
                int deltaX = eventX - lastTouchX;
                int deltaY = eventY - lastTouchY;
                deltaX = (int) Math.round(Math.abs(deltaX) * xFactor * (eventX < lastTouchX ? -1 : 1));
                deltaY = (int) Math.round(Math.abs(deltaY) * yFactor * (eventY < lastTouchY ? -1 : 1));

                if (pointerCount == 2) {
                    if (confirmedScroll) {
                        conn.sendMouseHighResScroll((short)(deltaY * SCROLL_SPEED_FACTOR));
                    }
                } else if (confirmedMove || isDoubleClickDrag || confirmedDrag) {

                    if (localCursorRenderer != null && this.enableLocalCursorRendering) {
                        // 1. 本地模式：更新本地光标
                        localCursorRenderer.updateCursorPosition(deltaX, deltaY);
                        // 2. 获取绝对坐标并发送给服务器 (保持同步)
                        float[] absPos = localCursorRenderer.getCursorAbsolutePosition();
                        conn.sendMousePosition(
                                (short) absPos[0],
                                (short) absPos[1],
                                (short) targetView.getWidth(),
                                (short) targetView.getHeight());
                    } else if (prefConfig.absoluteMouseMode) {
                        // 3. 旧版绝对模式
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

        cancelDragTimer();
        //  取消手势时，清除待处理的单击任务
        cancelSingleTapTimer();
        //  取消手势时，也要清理这个新计时器
        cancelDoubleTapHoldTimer();

        if (isDoubleClickDrag) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            isDoubleClickDrag = false;
        }

        if (confirmedDrag) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
        }

        lastTapUpTime = 0;
        isPotentialDoubleClick = false;
    }

    //  启动“按住确认拖拽”计时器的方法
    private void startDoubleTapHoldTimer() {
        cancelDoubleTapHoldTimer(); // 防御性取消
        doubleTapHoldRunnable = () -> {
            // 计时器触发，说明用户按住不动，我们主动确认为拖拽
            if (isPotentialDoubleClick) {
                isPotentialDoubleClick = false;
                isDoubleClickDrag = true;
                confirmedMove = true;
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            }
        };
        handler.postDelayed(doubleTapHoldRunnable, DOUBLE_TAP_HOLD_TO_DRAG_THRESHOLD);
    }

    //  取消“按住确认拖拽”计时器的方法
    private void cancelDoubleTapHoldTimer() {
        if (doubleTapHoldRunnable != null) {
            handler.removeCallbacks(doubleTapHoldRunnable);
            doubleTapHoldRunnable = null;
        }
    }

    private void startDragTimer() {
        cancelDragTimer();
        handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD);
    }

    private void cancelDragTimer() {
        handler.removeCallbacks(dragTimerRunnable);
    }

    // 用于取消延迟单击任务的辅助方法
    private void cancelSingleTapTimer() {
        if (singleTapRunnable != null) {
            handler.removeCallbacks(singleTapRunnable);
            singleTapRunnable = null;
        }
    }

    private void checkForConfirmedMove(int eventX, int eventY) {
        if (confirmedMove || confirmedDrag || isPotentialDoubleClick) return;
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
