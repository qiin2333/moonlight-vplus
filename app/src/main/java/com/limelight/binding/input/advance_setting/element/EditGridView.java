package com.limelight.binding.input.advance_setting.element;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

public class EditGridView extends View {

    private final static int minDisplayWidth = 3;
    private Paint paint;
    private int editGridWidth = 1;

    public EditGridView(Context context) {
        super(context);
        paint = new Paint();
        paint.setColor(0xFF00F5FF);
        paint.setStrokeWidth(2); // 设置网格线宽为2像素
        this.setAlpha(0.4f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editGridWidth < minDisplayWidth) return;
        drawGrid(canvas);
    }

    private void drawGrid(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();

        // 绘制垂直线
        for (float x = 0; x <= width; x += editGridWidth) {
            canvas.drawLine(x, 0, x, height, paint);
        }

        // 绘制水平线
        for (float y = 0; y <= height; y += editGridWidth) {
            canvas.drawLine(0, y, width, y, paint);
        }
    }

    public void setEditGridWidth(int editGridWidth) {
        this.editGridWidth = editGridWidth;
        invalidate();
    }

}
