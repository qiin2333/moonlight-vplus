package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import com.limelight.ui.CursorView;

public class LocalCursorRenderer {

    private CursorView cursorView;
    private int viewWidth = 1;
    private int viewHeight = 1;

    // 本地光标位置
    private float cursorX = 0;
    private float cursorY = 0;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public LocalCursorRenderer(CursorView cursorView, int viewWidth, int viewHeight) {
        this.cursorView = cursorView;
        this.viewWidth = Math.max(1, viewWidth);
        this.viewHeight = Math.max(1, viewHeight);

        // 初始化位置在中心
        this.cursorX = this.viewWidth / 2.0f;
        this.cursorY = this.viewHeight / 2.0f;

        // 立即同步初始位置给 View，否则 View 会绘制在屏幕外
        uiHandler.post(() -> {
            if (this.cursorView != null) {
                this.cursorView.updateCursorPosition(cursorX, cursorY);
            }
        });
    }

    public void updateCursorPosition(float deltaX, float deltaY) {
        // 更新逻辑坐标
        this.cursorX = Math.max(0, Math.min(cursorX + deltaX, viewWidth - 1));
        this.cursorY = Math.max(0, Math.min(cursorY + deltaY, viewHeight - 1));

        // 在 UI 线程更新 View
        uiHandler.post(() -> {
            if (cursorView != null) {
                cursorView.updateCursorPosition(cursorX, cursorY);
            }
        });
    }

    public void setViewDimensions(int width, int height) {
        this.viewWidth = Math.max(1, width);
        this.viewHeight = Math.max(1, height);
        // 确保坐标不越界
        this.cursorX = Math.min(cursorX, viewWidth - 1);
        this.cursorY = Math.min(cursorY, viewHeight - 1);
    }

    public void show() {
        uiHandler.post(() -> {
            if (cursorView != null) {
                cursorView.show();
                // 显示时强制更新一次位置，确保立刻可见
                cursorView.updateCursorPosition(cursorX, cursorY);
            }
        });
    }

    public void hide() {
        uiHandler.post(() -> {
            if (cursorView != null) cursorView.hide();
        });
    }

    public void destroy() {
        hide();
        cursorView = null;
    }

    // Getter methods required by context
    public float[] getCursorAbsolutePosition() {
        return new float[]{cursorX, cursorY};
    }
}