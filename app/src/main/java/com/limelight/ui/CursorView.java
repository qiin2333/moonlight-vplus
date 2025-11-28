package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;
import com.limelight.R;

public class CursorView extends View {

    // 光标显示的宽度和高度（单位：像素）
    private static final int CURSOR_WIDTH = 32;
    private static final int CURSOR_HEIGHT = 32;

    // 光标的热点偏移量 (Hotspot)
    private static final float PIVOT_X = CURSOR_WIDTH * (6f / 24f);
    private static final float PIVOT_Y = CURSOR_HEIGHT * (3f / 24f);

    private float cursorX = -100;
    private float cursorY = -100;
    private boolean isVisible = false;

    private Drawable cursorDrawable;

    public CursorView(Context context) {
        super(context);
        init(context);
    }

    public CursorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        cursorDrawable = ContextCompat.getDrawable(context, R.drawable.arrow);

        // 给光标强制着色（例如变成黄色）
        // if (cursorDrawable != null) cursorDrawable.setTint(android.graphics.Color.YELLOW);
    }

    /**
     * 更新光标位置并重绘
     */
    public void updateCursorPosition(float x, float y) {
        this.cursorX = x;
        this.cursorY = y;
        invalidate();
    }

    public void show() {
        if (!isVisible) {
            isVisible = true;
            setVisibility(View.VISIBLE);
        }
    }

    public void hide() {
        if (isVisible) {
            isVisible = false;
            setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isVisible || cursorDrawable == null) return;

        // 计算光标图片的绘制矩形区域
        // 根据 cursorX/Y 和 热点(PIVOT) 计算左上角位置
        int left = (int) (cursorX - PIVOT_X);
        int top = (int) (cursorY - PIVOT_Y);
        int right = left + CURSOR_WIDTH;
        int bottom = top + CURSOR_HEIGHT;

        // 设置绘制范围
        cursorDrawable.setBounds(left, top, right, bottom);

        // 绘制到画布上
        cursorDrawable.draw(canvas);
    }
}