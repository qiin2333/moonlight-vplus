package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a digital Switch on screen element. It is used to get click and double click user input.
 */
public class DigitalSwitch extends Element {

    /**
     * Listener interface to update registered observers.
     */
    public interface DigitalSwitchListener {

        /**
         * onClick event will be fired on switch click.
         */
        void onSwitch(boolean wasPressed);
    }

    private List<DigitalSwitchListener> listeners = new ArrayList<>();
    private String text = "";
    private int icon = -1;

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();

    private String shape = "CIRCLE";


    public DigitalSwitch(ElementController controller, ElementBean elementBean, Context context) {
        super(controller, elementBean, context);

        this.text = elementBean.getName();
        this.shape = elementBean.getTypeAttributes().get("shape");
        String keyString = elementBean.getTypeAttributes().get("value");
        ElementController.SendEventHandler sendEventHandler = controller.getSendEventHandler(keyString);
        addDigitalSwitchListener(new DigitalSwitch.DigitalSwitchListener() {
            @Override
            public void onSwitch(boolean wasPressed) {
                sendEventHandler.sendEvent(wasPressed);
            }
        });

    }

    public void addDigitalSwitchListener(DigitalSwitchListener listener) {
        listeners.add(listener);
    }

    public void setText(String text) {
        this.text = text;
        invalidate();
    }

    public void setIcon(int id) {
        this.icon = id;
        invalidate();
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        // set transparent background
        canvas.drawColor(Color.TRANSPARENT);

        paint.setTextSize(getPercent(getWidth(), 25));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStrokeWidth(getDefaultStrokeWidth());

        paint.setColor(isPressed() ? pressedColor : getNormalColor());
        paint.setStyle(Paint.Style.STROKE);

        rect.left = rect.top = paint.getStrokeWidth();
        rect.right = getWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;

        switch (shape){
            case "CIRCLE":
                canvas.drawOval(rect, paint);
                break;
            case "SQUARE":
                canvas.drawRect(rect, paint);
                break;
        }

        if (icon != -1) {
            Drawable d = getResources().getDrawable(icon);
            d.setBounds(5, 5, getWidth() - 5, getHeight() - 5);
            d.draw(canvas);
        } else {
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(getDefaultStrokeWidth()/2);
            canvas.drawText(text, getPercent(getWidth(), 50), getPercent(getHeight(), 63), paint);
        }
    }

    private void onClickCallback() {
        _DBG("switch");
        // notify listeners
        for (DigitalSwitchListener listener : listeners) {
            listener.onSwitch(isPressed());
            if (isPressed()){
                setPressed(false);
            } else {
                setPressed(true);
            }
            invalidate();

        }

    }


    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // get masked (not specific to a pointer) action
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                onClickCallback();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                return true;
            default: {
            }
        }
        return true;
    }
}

