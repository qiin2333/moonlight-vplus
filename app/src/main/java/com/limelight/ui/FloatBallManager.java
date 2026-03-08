package com.limelight.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;

import com.limelight.R;

import java.lang.ref.WeakReference;

/**
 * 悬浮球管理器核心类
 */
public class FloatBallManager {
    private static final String TAG = "FloatBallManager";
    private static final String PREFS_NAME = "FloatBallPrefs";
    private static final String KEY_LAST_X = "lastX";
    private static final String KEY_LAST_Y = "lastY";
    private static final String KEY_IS_HALF_SHOW = "isHalfShow";

    // 默认配置
    private static final long DEFAULT_AUTO_HIDE_DELAY = 2000;
    private static final int DEFAULT_BALL_SIZE = 50;
    private static final int DEFAULT_OPACITY = 100;

    private WeakReference<Context> mContextRef;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mLayoutParams;
    private View mFloatBall;
    private SharedPreferences mSharedPreferences;

    private int screenWidth;
    private int screenHeight;
    private int ballSize;
    private int ballOpacity;
    private long autoHideDelay;
    private boolean enableEdgeSnap; // 是否启用边缘吸附

    private OnFloatBallInteractListener mListener;

    private boolean isDragging = false;
    private boolean isFlinging = false; // 标记是否触发了快速滑动
    private boolean isHalfShow = false;
    private float downX, downY;
    private float originalX, originalY;

    // 核心锚点：永远记录球体完全显示时的安全坐标
    private int lastSavedX, lastSavedY;

    private AutoHideRunnable mAutoHideRunnable;
    private Handler mHandler;
    private ComponentCallbacks mComponentCallbacks;

    // 手势检测器
    private GestureDetector mGestureDetector;

    private enum Side { LEFT, RIGHT, TOP, BOTTOM }
    private Side currentSide = Side.RIGHT;

    // 相对位置比例，用于屏幕旋转和非吸附模式下的位置记忆
    private float relativeX = 1.0f;
    private float relativeY = 0.5f;

    public enum SwipeDirection { UP, DOWN, LEFT, RIGHT }

    public interface OnFloatBallInteractListener {
        void onSingleClick();
        void onDoubleClick();
        void onLongClick();
        void onSwipe(SwipeDirection direction);
    }

    public FloatBallManager(Context context) {
        this(context, DEFAULT_BALL_SIZE, DEFAULT_OPACITY, DEFAULT_AUTO_HIDE_DELAY, true);
    }

    public FloatBallManager(Context context, int sizeInDp, int opacityPercent, long autoHideDelayMs, boolean enableEdgeSnap) {
        mContextRef = new WeakReference<>(context);
        Context cxt = mContextRef.get();
        if (cxt == null) return;

        this.ballSize = dip2px(sizeInDp);
        this.ballOpacity = opacityPercent;
        this.autoHideDelay = autoHideDelayMs;
        this.enableEdgeSnap = enableEdgeSnap;

        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mSharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mHandler = new Handler(context.getMainLooper());
        mAutoHideRunnable = new AutoHideRunnable();

        // 初始化手势检测器
        initGestureDetector(cxt);

        mComponentCallbacks = new ComponentCallbacks() {
            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                handleConfigurationChanged();
            }
            @Override
            public void onLowMemory() {}
        };
        context.registerComponentCallbacks(mComponentCallbacks);

        updateScreenSize();
        initFloatBallView();
        initLayoutParams();

        // 放在最后一步，确保所有参数初始化完毕后再恢复位置
        restoreLastPosition();
    }

    private Context getContext() {
        return mContextRef != null ? mContextRef.get() : null;
    }

    /**
     * 初始化手势检测器
     */
    private void initGestureDetector(Context context) {
        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mListener != null) mListener.onSingleClick();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mListener != null) mListener.onDoubleClick();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!isDragging && mListener != null) mListener.onLongClick();
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                if (Math.abs(e2.getEventTime() - e1.getEventTime()) > 300) return false;

                float deltaX = e2.getRawX() - e1.getRawX();
                float deltaY = e2.getRawY() - e1.getRawY();
                float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);

                // 如果滑动距离小于 30dp，或者是速度不够，则认为是普通拖拽时的手抖，拒绝识别
                if (distance < dip2px(30f) || (Math.abs(velocityX) < 500 && Math.abs(velocityY) < 500)) {
                    return false;
                }

                isFlinging = true;
                if (mListener != null) {
                    // 判断是横向还是纵向为主
                    if (Math.abs(deltaX) > Math.abs(deltaY)) {
                        mListener.onSwipe(deltaX > 0 ? SwipeDirection.RIGHT : SwipeDirection.LEFT);
                    } else {
                        mListener.onSwipe(deltaY > 0 ? SwipeDirection.DOWN : SwipeDirection.UP);
                    }
                }
                return true;
            }
        });
    }

    private void handleConfigurationChanged() {
        if (mFloatBall == null) return;
        mFloatBall.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mFloatBall.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        updateScreenSize();
                        if (!isDragging) {
                            boolean wasHalfShow = isHalfShow;

                            // 1. 先强制恢复到完全显示，根据 relativeX/Y 计算新坐标
                            isHalfShow = false;
                            applyRelativePosition();

                            // 2. 立即保存这个新的安全坐标
                            savePosition(mLayoutParams.x, mLayoutParams.y, wasHalfShow);

                            // 3. 如果之前是半隐藏，重新应用半隐藏
                            if (wasHalfShow) {
                                applyHalfShowPosition();
                            }
                        }
                    }
                });
    }

    /**
     * 计算当前坐标在屏幕中的相对比例 (0.0 - 1.0)
     * 同时也负责更新 currentSide
     */
    private void calculateRelativePosition(int x, int y) {
        if (screenWidth <= 0 || screenHeight <= 0) return;

        // 更新 Side
        if (x <= 0) currentSide = Side.LEFT;
        else if (x >= screenWidth - ballSize) currentSide = Side.RIGHT;
        else if (y <= 0) currentSide = Side.TOP;
        else if (y >= screenHeight - ballSize) currentSide = Side.BOTTOM;

        // 计算相对比例
        relativeX = (float) x / (screenWidth - ballSize);
        relativeY = (float) y / (screenHeight - ballSize);

        // 限制范围防止溢出
        relativeX = Math.max(0f, Math.min(1f, relativeX));
        relativeY = Math.max(0f, Math.min(1f, relativeY));
    }

    /**
     * 根据 relativeX/Y 和 屏幕尺寸，将球放到正确的位置
     */
    private void applyRelativePosition() {
        if (enableEdgeSnap) {
            // 启用吸附：强制贴边
            switch (currentSide) {
                case LEFT: mLayoutParams.x = 0; mLayoutParams.y = (int) (relativeY * (screenHeight - ballSize)); break;
                case RIGHT: mLayoutParams.x = screenWidth - ballSize; mLayoutParams.y = (int) (relativeY * (screenHeight - ballSize)); break;
                case TOP: mLayoutParams.y = 0; mLayoutParams.x = (int) (relativeX * (screenWidth - ballSize)); break;
                case BOTTOM: mLayoutParams.y = screenHeight - ballSize; mLayoutParams.x = (int) (relativeX * (screenWidth - ballSize)); break;
            }
        } else {
            // 不启用吸附：根据比例自由恢复
            mLayoutParams.x = (int) (relativeX * (screenWidth - ballSize));
            mLayoutParams.y = (int) (relativeY * (screenHeight - ballSize));
        }

        checkAndFixBounds(); // 再次确保不越界
        updateViewPosition();
    }

    private void checkAndFixBounds() {
        if (screenWidth <= 0 || screenHeight <= 0) return;
        // 如果不是半隐藏状态，必须限制在屏幕内
        if (mLayoutParams.x > screenWidth - ballSize) mLayoutParams.x = screenWidth - ballSize;
        if (mLayoutParams.y > screenHeight - ballSize) mLayoutParams.y = screenHeight - ballSize;
        if (mLayoutParams.x < 0 && !isHalfShow) mLayoutParams.x = 0;
        if (mLayoutParams.y < 0 && !isHalfShow) mLayoutParams.y = 0;
    }

    /**
     * 更新屏幕尺寸信息
     */
    private void updateScreenSize() {
        Context context = getContext();
        if (context == null) return;
        Point size = new Point();
        mWindowManager.getDefaultDisplay().getRealSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
    }

    private void smoothScrollTo(int targetX, int targetY, final Runnable onComplete) {
        int startX = mLayoutParams.x;
        int startY = mLayoutParams.y;
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(300);
        animator.setInterpolator(new OvershootInterpolator(1.0f));
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            mLayoutParams.x = (int) (startX + (targetX - startX) * fraction);
            mLayoutParams.y = (int) (startY + (targetY - startY) * fraction);
            updateViewPosition();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) onComplete.run();
            }
        });
        animator.start();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initFloatBallView() {
        Context context = getContext();
        if (context == null) return;
        mFloatBall = LayoutInflater.from(context).inflate(R.layout.float_ball_layout, null);
        mFloatBall.setOnTouchListener((v, event) -> handleTouchEvent(event));
    }

    /**
     * 初始化悬浮球布局参数
     * 设置悬浮球类型、大小、透明度等窗口属性
     */
    private void initLayoutParams() {
        mLayoutParams = new WindowManager.LayoutParams();
        // 关键：TYPE_APPLICATION 确保它作为 Activity 窗口的一部分，跟随 Activity 生命周期和旋转
        mLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION;
        // 关键：设置窗口格式为透明
        mLayoutParams.format = PixelFormat.TRANSLUCENT;
        // 关键：FLAG_LAYOUT_IN_SCREEN 强制以屏幕物理左上角为 (0,0)
        // FLAG_NOT_FOCUSABLE 允许触摸穿透到后面，FLAG_LAYOUT_NO_LIMITS 允许超出屏幕边缘（用于半隐藏）
        mLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;

        // 适配刘海屏，确保在剪裁区域也能正常显示
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            mLayoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // 设置布局对齐方式为左上角
        mLayoutParams.gravity = Gravity.TOP | Gravity.START;
        // 设置悬浮球宽高
        mLayoutParams.width = ballSize;
        mLayoutParams.height = ballSize;
    }

    /**
     * 核心：恢复上次位置
     */
    private void restoreLastPosition() {
        // 1. 读取保存的“锚点”坐标（完全显示时的坐标）
        lastSavedX = mSharedPreferences.getInt(KEY_LAST_X, screenWidth - ballSize);
        lastSavedY = mSharedPreferences.getInt(KEY_LAST_Y, screenHeight / 2);
        isHalfShow = mSharedPreferences.getBoolean(KEY_IS_HALF_SHOW, false);

        // 2. 赋值给 LayoutParams
        mLayoutParams.x = lastSavedX;
        mLayoutParams.y = lastSavedY;

        // 3. 关键：确保读取的坐标在当前屏幕范围内（防止屏幕尺寸变化导致的越界）
        checkAndFixBounds();

        // 4. 关键：根据恢复的坐标，更新 relativeX/Y，保证下次旋转屏幕时位置正确
        // 如果不更新，relativeX 可能是默认的 0.5，一旋转球就跑中间去了
        calculateRelativePosition(mLayoutParams.x, mLayoutParams.y);

        // 5. 如果是半隐藏，推到屏幕外
        if (isHalfShow) {
            applyHalfShowPosition();
        }

        updateViewPosition();
        Log.d(TAG, "恢复位置: x=" + mLayoutParams.x + ", y=" + mLayoutParams.y + ", isHalf=" + isHalfShow);
    }

    private boolean handleTouchEvent(MotionEvent event) {
        mGestureDetector.onTouchEvent(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDragging = false;
                isFlinging = false;
                downX = event.getRawX();
                downY = event.getRawY();

                mHandler.removeCallbacks(mAutoHideRunnable);
                if (isHalfShow) {
                    restoreFullShowPosition();
                }
                originalX = mLayoutParams.x;
                originalY = mLayoutParams.y;
                break;

            case MotionEvent.ACTION_MOVE:
                float moveX = event.getRawX() - downX;
                float moveY = event.getRawY() - downY;
                // 移动距离大于10像素视为拖拽或滑动过程
                if (Math.abs(moveX) > 10 || Math.abs(moveY) > 10) {
                    isDragging = true;
                    mLayoutParams.x = (int) (originalX + moveX);
                    mLayoutParams.y = (int) (originalY + moveY);
                    updateViewPosition();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isFlinging) {
                    // 快速滑动：回弹原位
                    savePosition((int) originalX, (int) originalY, false);
                    smoothScrollTo((int) originalX, (int) originalY, () -> startAutoHideTimer());
                } else if (isDragging) {
                    if (enableEdgeSnap) {
                        // 吸附模式：吸附并保存目标位置
                        attachToNearestEdge();
                    } else {
                        // 自由模式：保存当前位置，并同步更新相对比例
                        checkAndFixBounds(); // 确保拖出屏幕外时被拉回
                        savePosition(mLayoutParams.x, mLayoutParams.y, false);
                        calculateRelativePosition(mLayoutParams.x, mLayoutParams.y); // 必须更新比例
                        startAutoHideTimer();
                    }
                } else {
                    // 单纯点击
                    startAutoHideTimer();
                }
                isDragging = false;
                isFlinging = false;
                break;
        }
        return true;
    }
    /**
     * 吸附到最近的边缘
     * 计算当前位置到上下左右四个边缘的距离，吸附到最近的边缘
     */
    private void attachToNearestEdge() {
        int distanceToLeft = mLayoutParams.x;
        int distanceToRight = screenWidth - (mLayoutParams.x + ballSize);
        int distanceToTop = mLayoutParams.y;
        int distanceToBottom = screenHeight - (mLayoutParams.y + ballSize);

        int minDistance = Math.min(Math.min(distanceToLeft, distanceToRight), Math.min(distanceToTop, distanceToBottom));

        int targetX = mLayoutParams.x;
        int targetY = mLayoutParams.y;

        if (minDistance == distanceToLeft) targetX = 0;
        else if (minDistance == distanceToRight) targetX = screenWidth - ballSize;
        else if (minDistance == distanceToTop) targetY = 0;
        else targetY = screenHeight - ballSize;

        // 关键：动画开始前立即保存目标坐标，确保数据安全
        savePosition(targetX, targetY, false);
        calculateRelativePosition(targetX, targetY);

        smoothScrollTo(targetX, targetY, () -> startAutoHideTimer());
    }

    /**
     * 应用半显示位置
     * 根据当前吸附的边缘，将悬浮球一半隐藏到屏幕外
     */
    private void applyHalfShowPosition() {
        // 根据当前位置计算半隐藏坐标，但不保存到SharedPreferences
        if (mLayoutParams.x <= 0) mLayoutParams.x = -ballSize / 2;
        else if (mLayoutParams.x >= screenWidth - ballSize) mLayoutParams.x = screenWidth - ballSize / 2;
        else if (mLayoutParams.y <= 0) mLayoutParams.y = -ballSize / 2;
        else if (mLayoutParams.y >= screenHeight - ballSize) mLayoutParams.y = screenHeight - ballSize / 2;

        if (mFloatBall != null) {
            mFloatBall.setAlpha((ballOpacity / 100.0f) * 0.5f);
        }

        isHalfShow = true;
        updateViewPosition();

        // 只更新状态标记，不更新坐标！
        mSharedPreferences.edit().putBoolean(KEY_IS_HALF_SHOW, true).apply();
    }

    /**
     * 恢复完全显示位置
     * 将半隐藏的悬浮球恢复到屏幕内完全显示
     */
    private void restoreFullShowPosition() {
        if (!isHalfShow) return;

        // 恢复到锚点坐标
        mLayoutParams.x = lastSavedX;
        mLayoutParams.y = lastSavedY;

        if (mFloatBall != null) {
            mFloatBall.setAlpha(ballOpacity / 100.0f);
        }

        isHalfShow = false;
        mSharedPreferences.edit().putBoolean(KEY_IS_HALF_SHOW, false).apply();
        updateViewPosition();
    }

    private void updateViewPosition() {
        Context context = getContext();
        if (mWindowManager == null || mFloatBall == null || context == null) return;
        try {
            if (mFloatBall.getParent() == null) {
                mWindowManager.addView(mFloatBall, mLayoutParams);
            } else {
                mWindowManager.updateViewLayout(mFloatBall, mLayoutParams);
            }
        } catch (Exception e) {
            Log.e(TAG, "Update view failed: " + e.getMessage());
        }
    }

    /**
     * 保存位置的唯一入口
     * 只有在完全显示（Anchor）状态下才允许调用此方法更新坐标
     */
    private void savePosition(int x, int y, boolean isHalf) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(KEY_LAST_X, x);
        editor.putInt(KEY_LAST_Y, y);
        editor.putBoolean(KEY_IS_HALF_SHOW, isHalf);
        editor.apply();

        // 同步内存数据
        lastSavedX = x;
        lastSavedY = y;
        isHalfShow = isHalf;
    }

    private void startAutoHideTimer() {
        mHandler.removeCallbacks(mAutoHideRunnable);
        if (autoHideDelay > 0) {
            mHandler.postDelayed(mAutoHideRunnable, autoHideDelay);
        }
    }

    public void showFloatBall() {
        updateViewPosition();
        startAutoHideTimer();
    }

    public void hideFloatBall() {
        try {
            mHandler.removeCallbacksAndMessages(null);
            if (mFloatBall != null && mFloatBall.getParent() != null) {
                mWindowManager.removeView(mFloatBall);
            }
        } catch (Exception e) {
            Log.e(TAG, "Hide failed: " + e.getMessage());
        }
    }

    public void release() {
        Context context = getContext();
        if (context != null) context.unregisterComponentCallbacks(mComponentCallbacks);
        if (mContextRef != null) mContextRef.clear();
        mWindowManager = null;
        mFloatBall = null;
        mHandler.removeCallbacksAndMessages(null);
    }

    public void setOnFloatBallInteractListener(OnFloatBallInteractListener listener) {
        this.mListener = listener;
    }

    private class AutoHideRunnable implements Runnable {
        @Override
        public void run() {
            if (isDragging || isHalfShow || isFlinging) return;
            applyHalfShowPosition();
        }
    }

    private int dip2px(float dpValue) {
        Context context = getContext();
        if (context == null) return 0;
        return (int) (dpValue * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}