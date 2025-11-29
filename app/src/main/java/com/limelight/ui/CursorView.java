package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;
import com.limelight.R;

public class CursorView extends View {

    // 网络接收到的光标
    private Bitmap cursorBitmap;
    private float pivotX;
    private float pivotY;

    // === 兜底方案 (默认光标) ===
    private Bitmap defaultCursorBitmap;
    private float defaultPivotX;
    private float defaultPivotY;

    // 状态
    private float cursorX = -100;
    private float cursorY = -100;
    private boolean isVisible = false;
    private Paint paint = new Paint();

    // 默认光标大小 (像素)
    private static final int DEFAULT_SIZE = 24;

    public CursorView(Context context) {
        super(context);
        init(context);
    }

    public CursorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setElevation(100f);
        setWillNotDraw(false);

        // === 加载本地 SVG 作为兜底 ===
        Drawable vectorDrawable = ContextCompat.getDrawable(context, R.drawable.arrow);
        if (vectorDrawable != null) {
            // 将 VectorDrawable 转为 Bitmap
            defaultCursorBitmap = Bitmap.createBitmap(DEFAULT_SIZE, DEFAULT_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(defaultCursorBitmap);
            vectorDrawable.setBounds(0, 0, DEFAULT_SIZE, DEFAULT_SIZE);
            vectorDrawable.draw(canvas);

            // 设置默认热点 (箭头尖端: 6/24, 3/24)
            defaultPivotX = DEFAULT_SIZE * (6f / 24f);
            defaultPivotY = DEFAULT_SIZE * (3f / 24f);
        }
    }

    /**
     * 设置网络光标 (收到 UDP 包时调用)
     */
    public void setCursorBitmap(Bitmap bitmap, int hotX, int hotY) {
        this.cursorBitmap = bitmap;
        this.pivotX = hotX;
        this.pivotY = hotY;
        invalidate();
    }

    /**
     * 重置为默认光标 (断连或初始化时调用)
     */
    public void resetToDefault() {
        this.cursorBitmap = null; // 清空网络图片，触发 onDraw 里的回退逻辑
        invalidate();
    }

    public void updateCursorPosition(float x, float y) {
        this.cursorX = x;
        this.cursorY = y;
        invalidate();
    }

    public void show() {
        isVisible = true;
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        isVisible = false;
        setVisibility(View.GONE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isVisible) return;

        Bitmap bmpToDraw;
        float pX, pY;

        // === 核心逻辑：优先用网络图，没有就用兜底图 ===
        if (cursorBitmap != null) {
            bmpToDraw = cursorBitmap;
            pX = pivotX;
            pY = pivotY;
        } else {
            // 兜底方案
            bmpToDraw = defaultCursorBitmap;
            pX = defaultPivotX;
            pY = defaultPivotY;
        }

        if (bmpToDraw != null) {
            float left = cursorX - pX;
            float top = cursorY - pY;
            canvas.drawBitmap(bmpToDraw, left, top, paint);
        }
    }
}