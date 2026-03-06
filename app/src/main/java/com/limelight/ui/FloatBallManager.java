package com.limelight.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ComponentCallbacks;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.limelight.R;

import java.lang.ref.WeakReference;

import android.animation.ValueAnimator;
import android.view.animation.OvershootInterpolator;

/**
 * 悬浮球管理器核心类
 * 功能特点：
 * 1. 支持上下左右四边缘吸附
 * 2. 位置记忆功能
 * 3. 2秒无操作自动半隐藏
 * 4. 丰富交互：拖动、单击、双击、长按、滑动
 */
public class FloatBallManager {
    private static final String TAG = "FloatBallManager";
    private static final String PREFS_NAME = "FloatBallPrefs";
    private static final String KEY_LAST_X = "lastX";
    private static final String KEY_LAST_Y = "lastY";
    private static final String KEY_IS_HALF_SHOW = "isHalfShow";
    private static final long AUTO_HIDE_DELAY = 2000;

    private WeakReference<Context> mContextRef;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mLayoutParams;
    private View mFloatBall;
    private SharedPreferences mSharedPreferences;

    private int screenWidth;
    private int screenHeight;
    private int ballSize;

    private OnFloatBallInteractListener mListener;

    private boolean isDragging = false;
    private boolean isFlinging = false; // 标记是否触发了快速滑动
    private boolean isHalfShow = false;
    private float downX, downY;
    private float originalX, originalY;
    private int lastSavedX, lastSavedY;

    private AutoHideRunnable mAutoHideRunnable;
    private Handler mHandler;
    private ComponentCallbacks mComponentCallbacks;

    // 手势检测器
    private GestureDetector mGestureDetector;

    private enum Side { LEFT, RIGHT, TOP, BOTTOM }
    private Side currentSide = Side.RIGHT;
    private float relativePos = 0.5f;

    // 滑动方向枚举
    public enum SwipeDirection { UP, DOWN, LEFT, RIGHT }

    // ====== 扩展的交互接口 ======
    public interface OnFloatBallInteractListener {
        void onSingleClick();
        void onDoubleClick();
        void onLongClick();
        void onSwipe(SwipeDirection direction);
    }

    public FloatBallManager(Context context) {
        mContextRef = new WeakReference<>(context);
        Context cxt = mContextRef.get();
        if (cxt == null) {
            Log.e(TAG, "上下文为空，无法初始化悬浮球");
            return;
        }

        this.ballSize = dip2px(50f);
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
                if (mListener != null) {
                    mListener.onSingleClick();
                }
                Log.i(TAG, "判断为点击");
                return super.onSingleTapConfirmed(e);
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mListener != null) {
                    mListener.onDoubleClick();
                }
                Log.i(TAG, "判断为双击");
                return super.onDoubleTap(e);
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!isDragging && mListener != null) {
                    mListener.onLongClick();
                }
                Log.i(TAG, "判断为长按");
                super.onLongPress(e);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                // 安全校验
                if (e1 == null || e2 == null) return false;

                // 1.限制滑动时间
                // 如果从按下到抬起的时间超过 300 毫秒，说明用户是在“思考并拖拽”，拒绝识别为快速滑动
                long duration = e2.getEventTime() - e1.getEventTime();
                if (duration > 300) {
                    return false;
                }

                // 2.限制最短滑动距离
                // 使用 getRawX/Y 获取屏幕绝对坐标，防止悬浮球自身移动导致坐标系混乱
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
                        if (deltaX > 0) mListener.onSwipe(SwipeDirection.RIGHT);
                        else mListener.onSwipe(SwipeDirection.LEFT);
                    } else {
                        if (deltaY > 0) mListener.onSwipe(SwipeDirection.DOWN);
                        else mListener.onSwipe(SwipeDirection.UP);
                    }
                    Log.d(TAG, "判定为快速滑动指令，方向触发");
                }
                return super.onFling(e1, e2, velocityX, velocityY);
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
                            isHalfShow = false;
                            applyRelativePosition();
                            if (wasHalfShow) {
                                applyHalfShowPosition();
                            }
                            saveCurrentPosition();
                        }
                    }
                });
    }

    /**
     * 计算当前球在边缘的相对比例
     */
    private void calculateRelativePosition() {
        if (screenWidth <= 0 || screenHeight <= 0) return;
        if (mLayoutParams.x <= 0) currentSide = Side.LEFT;
        else if (mLayoutParams.x >= screenWidth - ballSize) currentSide = Side.RIGHT;
        else if (mLayoutParams.y <= 0) currentSide = Side.TOP;
        else if (mLayoutParams.y >= screenHeight - ballSize) currentSide = Side.BOTTOM;

        if (currentSide == Side.LEFT || currentSide == Side.RIGHT) {
            relativePos = (float) mLayoutParams.y / (screenHeight - ballSize);
        } else {
            relativePos = (float) mLayoutParams.x / (screenWidth - ballSize);
        }
        relativePos = Math.max(0f, Math.min(1f, relativePos));
    }

    /**
     * 根据记录的侧边和比例，在新的屏幕尺寸下重新定位
     */
    private void applyRelativePosition() {
        switch (currentSide) {
            case LEFT:
                mLayoutParams.x = 0;
                mLayoutParams.y = (int) (relativePos * (screenHeight - ballSize));
                break;
            case RIGHT:
                mLayoutParams.x = screenWidth - ballSize;
                mLayoutParams.y = (int) (relativePos * (screenHeight - ballSize));
                break;
            case TOP:
                mLayoutParams.y = 0;
                mLayoutParams.x = (int) (relativePos * (screenWidth - ballSize));
                break;
            case BOTTOM:
                mLayoutParams.y = screenHeight - ballSize;
                mLayoutParams.x = (int) (relativePos * (screenWidth - ballSize));
                break;
        }
        checkAndFixBounds();
        updateViewPosition();
    }

    private void checkAndFixBounds() {
        if (screenWidth <= 0 || screenHeight <= 0) return;
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

        // 使用 getRealSize 获取物理屏幕真实尺寸，覆盖刘海屏和导航栏区域
        android.graphics.Point size = new android.graphics.Point();
        mWindowManager.getDefaultDisplay().getRealSize(size);
        screenWidth = size.x;
        screenHeight = size.y;

        // 判定横竖屏：完全遵循系统 Configuration，解决分屏/多窗口下的逻辑错误
        int orientation = context.getResources().getConfiguration().orientation;
        boolean isLandscape = (orientation == Configuration.ORIENTATION_LANDSCAPE);

        Log.d(TAG, "屏幕尺寸更新: width=" + screenWidth + ", height=" + screenHeight + ", 橫屏模式=" + isLandscape);
    }

    /**
     * 丝滑滚动到指定坐标（支持回弹效果）
     */
    private void smoothScrollTo(int targetX, int targetY) {
        int startX = mLayoutParams.x;
        int startY = mLayoutParams.y;

        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(300); // 300毫秒动画
        animator.setInterpolator(new OvershootInterpolator(1.0f));

        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float fraction = (float) animation.getAnimatedValue();
                mLayoutParams.x = (int) (startX + (targetX - startX) * fraction);
                mLayoutParams.y = (int) (startY + (targetY - startY) * fraction);
                updateViewPosition();
            }
        });
        animator.start();
    }

    /**
     * 初始化悬浮球视图
     * 通过 LayoutInflater 加载预设的优雅 XML 布局
     */
    @SuppressLint("ClickableViewAccessibility")
    private void initFloatBallView() {
        Context context = getContext();
        if (context == null) return;
        
        // 从 XML 加载悬浮球 UI
        mFloatBall = LayoutInflater.from(context).inflate(R.layout.float_ball_layout, null);

        // 设置触摸事件监听
        mFloatBall.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouchEvent(event);
            }
        });
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
     * 从SharedPreferences恢复上次保存的位置
     * 如果没有保存的位置，默认显示在屏幕右下角
     */
    private void restoreLastPosition() {
        lastSavedX = mSharedPreferences.getInt(KEY_LAST_X, screenWidth - ballSize);
        lastSavedY = mSharedPreferences.getInt(KEY_LAST_Y, screenHeight / 2);
        isHalfShow = mSharedPreferences.getBoolean(KEY_IS_HALF_SHOW, false);

        mLayoutParams.x = lastSavedX;
        mLayoutParams.y = lastSavedY;

        // 如果上次是半显示状态，直接应用半显示位置
        if (isHalfShow) {
            applyHalfShowPosition();
        }
        
        // 关键：初始化锚点和比例，确保启动后立即旋转也能正确适配
        calculateRelativePosition();

        Log.d(TAG, "恢复上次位置: x=" + lastSavedX + ", y=" + lastSavedY + ", 半显示状态=" + isHalfShow);
    }

    private boolean handleTouchEvent(MotionEvent event) {
        // 1. 优先将事件交给 GestureDetector 处理单击、双击、长按和滑动
        mGestureDetector.onTouchEvent(event);

        // 2. 处理拖动逻辑和生命周期
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDragging = false;
                isFlinging = false; // 重置滑动标记

                downX = event.getRawX();
                downY = event.getRawY();
                originalX = mLayoutParams.x;
                originalY = mLayoutParams.y;

                mHandler.removeCallbacks(mAutoHideRunnable);
                if (isHalfShow) {
                    restoreFullShowPosition();
                }
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
                    //如果是快速滑动，触发平滑回弹到原始位置
                    smoothScrollTo((int)originalX, (int)originalY);
                } else if (isDragging) {
                    // 如果是慢速拖动，就近吸附到屏幕边缘
                    attachToNearestEdge();
                    calculateRelativePosition();
                    saveCurrentPosition();
                }

                mHandler.postDelayed(mAutoHideRunnable, AUTO_HIDE_DELAY);
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

        if (minDistance == distanceToLeft) {
            targetX = 0;
        } else if (minDistance == distanceToRight) {
            targetX = screenWidth - ballSize;
        } else if (minDistance == distanceToTop) {
            targetY = 0;
        } else {
            targetY = screenHeight - ballSize;
        }

        smoothScrollTo(targetX, targetY);
        Log.d(TAG, "吸附到最近边缘: x=" + mLayoutParams.x + ", y=" + mLayoutParams.y);
    }

    /**
     * 应用半显示位置
     * 根据当前吸附的边缘，将悬浮球一半隐藏到屏幕外
     */
    private void applyHalfShowPosition() {
        if (mLayoutParams.x <= 0) mLayoutParams.x = -ballSize / 2;
        else if (mLayoutParams.x >= screenWidth - ballSize) mLayoutParams.x = screenWidth - ballSize / 2;
        else if (mLayoutParams.y <= 0) mLayoutParams.y = -ballSize / 2;
        else if (mLayoutParams.y >= screenHeight - ballSize) mLayoutParams.y = screenHeight - ballSize / 2;

        isHalfShow = true;
        updateViewPosition();
        Log.d(TAG, "应用半显示位置: x=" + mLayoutParams.x + ", y=" + mLayoutParams.y);
    }

    /**
     * 恢复完全显示位置
     * 将半隐藏的悬浮球恢复到屏幕内完全显示
     */
    private void restoreFullShowPosition() {
        if (!isHalfShow) return;
        if (mLayoutParams.x <= -ballSize / 2) mLayoutParams.x = 0;
        else if (mLayoutParams.x >= screenWidth - ballSize / 2) mLayoutParams.x = screenWidth - ballSize;
        else if (mLayoutParams.y <= -ballSize / 2) mLayoutParams.y = 0;
        else if (mLayoutParams.y >= screenHeight - ballSize / 2) mLayoutParams.y = screenHeight - ballSize;

        isHalfShow = false;
        updateViewPosition();
        Log.d(TAG, "恢复完全显示位置: x=" + mLayoutParams.x + ", y=" + mLayoutParams.y);
    }

    /**
     * 更新悬浮球视图位置
     * 调用WindowManager更新布局参数
     */
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
            Log.e(TAG, "更新悬浮球位置失败: " + e.getMessage());
        }
    }

    private void saveCurrentPosition() {
        int actualX = mLayoutParams.x;
        int actualY = mLayoutParams.y;
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(KEY_LAST_X, actualX);
        editor.putInt(KEY_LAST_Y, actualY);
        editor.putBoolean(KEY_IS_HALF_SHOW, isHalfShow);
        editor.apply();
        lastSavedX = actualX;
        lastSavedY = actualY;
    }

    /**
     * 显示悬浮球
     */
    public void showFloatBall() {
        updateViewPosition();
        // 显示后立即启动自动隐藏任务
        mHandler.postDelayed(mAutoHideRunnable, AUTO_HIDE_DELAY);
        Log.d(TAG, "显示悬浮球");
    }

    /**
     * 隐藏悬浮球
     */
    public void hideFloatBall() {
        try {
            if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
            if (mFloatBall != null && mFloatBall.getParent() != null && mWindowManager != null) {
                mWindowManager.removeView(mFloatBall);
            }
            Log.d(TAG, "悬浮球已彻底移除");
        } catch (Exception e) {
            Log.e(TAG, "隐藏悬浮球失败: " + e.getMessage());
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        Context context = getContext();
        if (context != null && mComponentCallbacks != null) {
            context.unregisterComponentCallbacks(mComponentCallbacks);
        }

        // 3. 释放所有引用（包括弱引用本身）
        if (mContextRef != null) {
            mContextRef.clear();
            mContextRef = null;
        }
        mWindowManager = null;
        mFloatBall = null;
        mHandler = null;
        mListener = null;
        mGestureDetector = null;
        Log.d(TAG, "资源已释放");
    }

    public void setOnFloatBallInteractListener(OnFloatBallInteractListener listener) {
        this.mListener = listener;
    }

    private class AutoHideRunnable implements Runnable {
        @Override
        public void run() {
            if (isDragging || isHalfShow) return;
            applyHalfShowPosition();
            saveCurrentPosition();
        }
    }

    private int dip2px(float dpValue) {
        Context context = getContext();
        if (context == null) return 0;
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }
}