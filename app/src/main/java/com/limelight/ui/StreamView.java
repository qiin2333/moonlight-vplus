// StreamView.java
package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceView;

public class StreamView extends SurfaceView {
    // 原始成员变量
    private double desiredAspectRatio;
    private InputCallbacks inputCallbacks;

    // --- 手势处理成员变量 (已移除旋转相关变量) ---
    private ScaleGestureDetector mScaleDetector;
    private float mScaleFactor = 1.0f;
    private float mPosX = 0.0f;
    private float mPosY = 0.0f;

    private float mLastTouchX;
    private float mLastTouchY;
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;

    // --- 手势功能的总开关 ---
    private boolean mGesturesEnabled = false;

    // --- 构造函数 ---
    public StreamView(Context context) {
        super(context);
        init(context);
    }

    public StreamView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public StreamView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public StreamView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    // --- 初始化方法 ---
    private void init(Context context) {
        mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    // --- 原始方法 (保留) ---
    public void setDesiredAspectRatio(double aspectRatio) {
        this.desiredAspectRatio = aspectRatio;
        requestLayout();
    }

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.inputCallbacks = callbacks;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (desiredAspectRatio == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int measuredHeight, measuredWidth;
        if (widthSize > heightSize * desiredAspectRatio) {
            measuredHeight = heightSize;
            measuredWidth = (int) (measuredHeight * desiredAspectRatio);
        } else {
            measuredWidth = widthSize;
            measuredHeight = (int) (measuredWidth / desiredAspectRatio);
        }
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (inputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (inputCallbacks.handleKeyDown(event)) return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (inputCallbacks.handleKeyUp(event)) return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
    }

    // --- 手势处理方法 ---
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 将变换中心设为视图中心，确保缩放是围绕中心进行的
        setPivotX(w / 2f);
        setPivotY(h / 2f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mGesturesEnabled) {
            return super.onTouchEvent(ev);
        }

        mScaleDetector.onTouchEvent(ev);

        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mLastTouchX = ev.getX();
                mLastTouchY = ev.getY();
                mActivePointerId = ev.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                // 只处理单指平移
                if (!mScaleDetector.isInProgress() && ev.getPointerCount() == 1) {
                    final int pointerIndex = ev.findPointerIndex(mActivePointerId);
                    if (pointerIndex != -1) {
                        final float x = ev.getX(pointerIndex);
                        final float y = ev.getY(pointerIndex);
                        final float dx = x - mLastTouchX;
                        final float dy = y - mLastTouchY;
                        mPosX += dx;
                        mPosY += dy;
                        mLastTouchX = x;
                        mLastTouchY = y;
                        // 应用变换，内部会自动处理边界检查
                        applyTransformations();
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerIndex = (ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                final int pointerId = ev.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                    if (ev.getPointerCount() > newPointerIndex) {
                        mLastTouchX = ev.getX(newPointerIndex);
                        mLastTouchY = ev.getY(newPointerIndex);
                        mActivePointerId = ev.getPointerId(newPointerIndex);
                    }
                }
                break;
            }
        }

        return true;
    }

    /**
     * 新增：一个用于限制平移范围的辅助方法，防止移出边界
     */
    private void clampTranslations() {
        // 如果视图还没有尺寸，则不执行任何操作
        if (getWidth() == 0 || getHeight() == 0) return;

        float viewWidth = getWidth();
        float viewHeight = getHeight();

        // 计算在当前缩放级别下，X和Y方向上可以移动的最大距离
        // 如果视图没有被放大 (mScaleFactor <= 1)，则最大移动距离为0
        float maxTranslationX = Math.max(0, (viewWidth * mScaleFactor - viewWidth) / 2);
        float maxTranslationY = Math.max(0, (viewHeight * mScaleFactor - viewHeight) / 2);

        // 使用 Math.max 和 Math.min 将 mPosX 和 mPosY 限制在计算出的范围内
        mPosX = Math.max(-maxTranslationX, Math.min(mPosX, maxTranslationX));
        mPosY = Math.max(-maxTranslationY, Math.min(mPosY, maxTranslationY));
    }

    /**
     * 应用所有变换（平移、缩放），并内置边界检查
     */
    private void applyTransformations() {
        // 在应用变换之前，先调用方法确保平移值在合法范围内
        clampTranslations();

        // 应用限制后的平移和缩放值
        setTranslationX(mPosX);
        setTranslationY(mPosY);
        setScaleX(mScaleFactor);
        setScaleY(mScaleFactor);
    }
    
    /**
     * 重置所有变换到初始状态
     */
    public void resetTransformations() {
        mScaleFactor = 1.0f;
        mPosX = 0.0f;
        mPosY = 0.0f;
        applyTransformations();
    }

    /**
     * 内部类，用于监听缩放手势
     */
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            mScaleFactor *= detector.getScaleFactor();
            // 限制缩放范围在 1x (原始大小) 到 5x 之间
            mScaleFactor = Math.max(1.0f, Math.min(mScaleFactor, 5.0f));

            // 每次缩放后，都需要应用变换并检查边界
            applyTransformations();
            return true;
        }
    }

    // --- 公共方法，用于从外部控制手势功能 ---
    public void setGesturesEnabled(boolean enabled) {
        // 这个方法现在只负责更新开关状态，不再影响视图的变换
        this.mGesturesEnabled = enabled;
    }
}