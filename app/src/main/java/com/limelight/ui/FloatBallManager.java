package com.limelight.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.LayoutInflater;
import android.graphics.PixelFormat;
import android.content.ComponentCallbacks;
import android.content.res.Configuration;
import com.limelight.R;

import java.lang.ref.WeakReference;

/**
 * 悬浮球管理器核心类
 * 功能特点：
 * 1. 支持上下左右四边缘吸附
 * 2. 位置记忆功能（使用SharedPreferences持久化保存最后位置）
 * 3. 2秒无操作自动半隐藏（仅显示一半在屏幕内）
 * 4. 触摸交互：拖动、点击事件处理
 */
public class FloatBallManager {
    private static final String TAG = "FloatBallManager";
    private static final String PREFS_NAME = "FloatBallPrefs";
    private static final String KEY_LAST_X = "lastX";
    private static final String KEY_LAST_Y = "lastY";
    private static final String KEY_IS_HALF_SHOW = "isHalfShow";
    // 自动半隐藏延迟时间（毫秒）
    private static final long AUTO_HIDE_DELAY = 2000;
    // 点击事件防抖时间间隔（毫秒）
    private static final long CLICK_INTERVAL = 500;
    // 替换原有的mContext
    private WeakReference<Context> mContextRef;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mLayoutParams;
    private View mFloatBall;
    private SharedPreferences mSharedPreferences;

    // 屏幕和悬浮球尺寸相关变量
    private int screenWidth;
    private int screenHeight;
    private int ballSize;
    private OnFloatBallClickListener mListener;

    // 状态控制变量
    private boolean isDragging = false;
    private boolean isHalfShow = false;
    private float downX, downY;
    private float originalX, originalY;
    private int lastSavedX, lastSavedY;

    // 自动半隐藏相关
    private AutoHideRunnable mAutoHideRunnable;
    private Handler mHandler;
    private ComponentCallbacks mComponentCallbacks;

    // 防抖相关变量
    private long lastClickTime = 0;

    private enum Side { LEFT, RIGHT, TOP, BOTTOM }
    private Side currentSide = Side.RIGHT; // 默认停靠右侧
    private float relativePos = 0.5f; // 沿边缘的相对位置百分比 (0.0 - 1.0)

    public interface OnFloatBallClickListener {
        void onFloatBallClick();
    }

    /**
     * 构造函数
     *
     * @param context 应用上下文
     */
    public FloatBallManager(Context context) {
        // 使用弱引用存储Activity Context
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

        // 注册屏幕旋转监听
        mComponentCallbacks = new ComponentCallbacks() {
            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                handleConfigurationChanged();
            }

            @Override
            public void onLowMemory() {
            }
        };
        context.registerComponentCallbacks(mComponentCallbacks);

        // 初始化屏幕尺寸
        updateScreenSize();
        // 初始化悬浮球视图
        initFloatBallView();
        // 初始化布局参数
        initLayoutParams();
        // 从SharedPreferences恢复上次位置
        restoreLastPosition();
    }

    // 添加上下文获取工具方法
    private Context getContext() {
        return mContextRef != null ? mContextRef.get() : null;
    }

    private void handleConfigurationChanged() {
        if (mFloatBall == null) return;

        // 不要用 post，直接用 ViewTreeObserver 监听全局布局的真正完成！
        // 只有当底层真正把宽高量好后，我们才去重新计算悬浮球位置。
        mFloatBall.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        // 确保只回调一次，防止死循环
                        mFloatBall.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                        // 此时获取的屏幕宽高绝对是准确的
                        updateScreenSize();

                        if (!isDragging) {
                            boolean wasHalfShow = isHalfShow;
                            isHalfShow = false; // 暂时重置，防止边界检查卡死

                            // 重新根据比例贴边
                            applyRelativePosition();

                            // 恢复半隐藏状态
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
        
        // 判定当前侧边
        if (mLayoutParams.x <= 0) currentSide = Side.LEFT;
        else if (mLayoutParams.x >= screenWidth - ballSize) currentSide = Side.RIGHT;
        else if (mLayoutParams.y <= 0) currentSide = Side.TOP;
        else if (mLayoutParams.y >= screenHeight - ballSize) currentSide = Side.BOTTOM;

        // 计算比例
        if (currentSide == Side.LEFT || currentSide == Side.RIGHT) {
            relativePos = (float) mLayoutParams.y / (screenHeight - ballSize);
        } else {
            relativePos = (float) mLayoutParams.x / (screenWidth - ballSize);
        }
        // 约束 0~1
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
        mLayoutParams.y = lastSavedY; // 移除所有状态栏高度校正逻辑，直接使用屏幕坐标

        // 如果上次是半显示状态，直接应用半显示位置
        if (isHalfShow) {
            applyHalfShowPosition();
        }
        
        // 关键：初始化锚点和比例，确保启动后立即旋转也能正确适配
        calculateRelativePosition();

        Log.d(TAG, "恢复上次位置: x=" + lastSavedX + ", y=" + lastSavedY + ", 半显示状态=" + isHalfShow);
    }

    /**
     * 处理悬浮球触摸事件
     * 支持拖动、点击和自动隐藏逻辑
     */
    private boolean handleTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 触摸开始：取消自动隐藏任务，恢复完全显示状态
                isDragging = false;
                downX = event.getRawX();
                downY = event.getRawY();
                originalX = mLayoutParams.x;
                originalY = mLayoutParams.y;

                // 取消延迟半隐藏任务
                mHandler.removeCallbacks(mAutoHideRunnable);
                // 如果当前是半显示状态，恢复到完全显示位置
                if (isHalfShow) {
                    restoreFullShowPosition();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                // 触摸移动：更新悬浮球位置，标记为拖动状态
                float moveX = event.getRawX() - downX;
                float moveY = event.getRawY() - downY;

                // 如果移动距离超过阈值，视为拖动操作
                if (Math.abs(moveX) > 5 || Math.abs(moveY) > 5) {
                    isDragging = true;
                    mLayoutParams.x = (int) (originalX + moveX);
                    mLayoutParams.y = (int) (originalY + moveY);
                    updateViewPosition(); // 更新悬浮球位置
                }
                return true;

            case MotionEvent.ACTION_UP:
                // 触摸结束：如果是拖动操作则吸附到最近边缘，启动自动隐藏任务
                if (isDragging) {
                    // 吸附到最近边缘
                    attachToNearestEdge();
                    // 记录此时的相对侧边和比例，用于下次旋屏
                    calculateRelativePosition();
                    // 保存当前位置
                    saveCurrentPosition();
                } else {
                    // 如果不是拖动操作，视为点击事件（可在此处添加点击处理逻辑）
                    handleBallClick();
                }

                // 无论是否拖动，都启动2秒后自动半隐藏任务
                mHandler.postDelayed(mAutoHideRunnable, AUTO_HIDE_DELAY);
                isDragging = false;
                return true;

            default:
                return false;
        }
    }

    /**
     * 吸附到最近的边缘
     * 计算当前位置到上下左右四个边缘的距离，吸附到最近的边缘
     */
    private void attachToNearestEdge() {
        // 计算到各边缘的距离
        int distanceToLeft = mLayoutParams.x;
        int distanceToRight = screenWidth - (mLayoutParams.x + ballSize);
        int distanceToTop = mLayoutParams.y;
        int distanceToBottom = screenHeight - (mLayoutParams.y + ballSize);

        // 找出最小距离
        int minDistance = Math.min(Math.min(distanceToLeft, distanceToRight), Math.min(distanceToTop, distanceToBottom));

        // 根据最小距离吸附到相应边缘
        if (minDistance == distanceToLeft) {
            mLayoutParams.x = 0; // 吸附到左边缘
        } else if (minDistance == distanceToRight) {
            mLayoutParams.x = screenWidth - ballSize; // 吸附到右边缘
        } else if (minDistance == distanceToTop) {
            mLayoutParams.y = 0; // 吸附到上边缘
        } else {
            mLayoutParams.y = screenHeight - ballSize; // 吸附到下边缘
        }

        updateViewPosition();
        Log.d(TAG, "吸附到最近边缘: x=" + mLayoutParams.x + ", y=" + mLayoutParams.y);
    }

    /**
     * 应用半显示位置
     * 根据当前吸附的边缘，将悬浮球一半隐藏到屏幕外
     */
    private void applyHalfShowPosition() {

        // 根据当前位置判断吸附的边缘
        if (mLayoutParams.x <= 0) {
            // 左边缘吸附：向左隐藏一半
            mLayoutParams.x = -ballSize / 2;
        } else if (mLayoutParams.x >= screenWidth - ballSize) {
            // 右边缘吸附：向右隐藏一半
            mLayoutParams.x = screenWidth - ballSize / 2;
        } else if (mLayoutParams.y <= 0) {
            // 上边缘吸附：向上隐藏一半
            mLayoutParams.y = -ballSize / 2;
        } else if (mLayoutParams.y >= screenHeight - ballSize) {
            // 下边缘吸附：向下隐藏一半
            mLayoutParams.y = screenHeight - ballSize / 2;
        }

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

        // 根据当前半显示位置判断原吸附边缘
        if (mLayoutParams.x <= -ballSize / 2) {
            mLayoutParams.x = 0; // 恢复左边缘完全显示
        } else if (mLayoutParams.x >= screenWidth - ballSize / 2) {
            mLayoutParams.x = screenWidth - ballSize; // 恢复右边缘完全显示
        } else if (mLayoutParams.y <= -ballSize / 2) {
            mLayoutParams.y = 0; // 恢复上边缘完全显示
        } else if (mLayoutParams.y >= screenHeight - ballSize / 2) {
            mLayoutParams.y = screenHeight - ballSize; // 恢复下边缘完全显示
        }

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
        // 新增：检查关键资源是否已释放
        if (mWindowManager == null || mFloatBall == null || context == null) {
            Log.w(TAG, "资源已释放，跳过视图更新");
            return;
        }
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
        // 直接保存当前窗口坐标，不再进行状态栏补偿
        int actualX = mLayoutParams.x;
        int actualY = mLayoutParams.y;

        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putInt(KEY_LAST_X, actualX);
        editor.putInt(KEY_LAST_Y, actualY);
        editor.putBoolean(KEY_IS_HALF_SHOW, isHalfShow);
        editor.apply();

        lastSavedX = actualX;
        lastSavedY = actualY;
        Log.d(TAG, "保存当前位置: x=" + actualX + ", y=" + actualY + ", 半显示状态=" + isHalfShow);
    }

    /**
     * 处理悬浮球点击事件
     * 可在此处添加悬浮球点击后的具体功能逻辑
     */
    private void handleBallClick() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < CLICK_INTERVAL) {
            Log.d(TAG, "点击间隔过短，已忽略");
            return;
        }
        lastClickTime = currentTime;
        
        if (mListener != null) {
            mListener.onFloatBallClick();
        }
        Log.d(TAG, "悬浮球被点击");
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
            // 1. 先彻底清除所有Handler任务，避免后续任务触发视图更新
            if (mHandler != null) {
                mHandler.removeCallbacksAndMessages(null);
            }
            // 2. 移除视图
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
        Log.d(TAG, "资源已释放");
    }

    public void setOnFloatBallClickListener(OnFloatBallClickListener listener) {
        this.mListener = listener;
    }

    /**
     * 自动半隐藏任务
     * 实现2秒无操作后自动将悬浮球半隐藏到边缘
     */
    private class AutoHideRunnable implements Runnable {
        @Override
        public void run() {
            // 如果正在拖动或已经是半显示状态，不执行
            if (isDragging || isHalfShow) {
                return;
            }

            // 应用半显示位置
            applyHalfShowPosition();
            // 保存半显示状态（位置会在ACTION_UP时保存）
            saveCurrentPosition();
        }
    }

    private int dip2px(float dpValue) {
        // dp转px单位
        Context context = getContext();
        if (context == null) {
            return 0;
        }
        final float scale = getContext().getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }
}
