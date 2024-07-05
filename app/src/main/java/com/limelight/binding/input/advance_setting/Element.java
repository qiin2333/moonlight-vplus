package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

public abstract class Element extends View {
    private int normalColor = 0xF0888888;
    protected int pressedColor = 0xF00000FF;
    private ElementBean elementBean;
    protected final ElementController elementController;



    public Element(ElementController elementController, ElementBean elementBean, Context context){
        super(context);
        this.elementController = elementController;
        this.elementBean = elementBean;
        this.normalColor = elementBean.getNormalColor();
        this.pressedColor = elementBean.getPressedColor();
        setOpacity(elementBean.getOpacity());
    }

    public ElementBean getElementBean() {
        return elementBean;
    }

    public String getElementId() {
        return elementBean.getId();
    }

    public String getElementName(){
        return elementBean.getName();
    }
    boolean inRange(float x, float y) {
        return (this.getX() < x && this.getX() + this.getWidth() > x) &&
                (this.getY() < y && this.getY() + this.getHeight() > y);
    }

    public int getCentralX(){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.leftMargin + layoutParams.width/2;
    }

    public int getCentralY(){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.topMargin + layoutParams.height/2;
    }


    public void setCentralX(int x){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        layoutParams.leftMargin = x - layoutParams.width/2;
        //保存中心点坐标
        elementBean.setPositionX(x);
        requestLayout();


    }

    public void setCentralY(int y){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        layoutParams.topMargin = y - layoutParams.height/2;
        elementBean.setPositionY(y);
        requestLayout();
    }

    public void setParamWidth(int width){
        int centralPosX = getCentralX();
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        layoutParams.width = width;
        elementBean.setWidth(width);
        setLayoutParams(layoutParams);
        setCentralX(centralPosX);
    }

    public void setParamHeight(int height){
        int centralPosY = getCentralY();
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        layoutParams.height = height;
        elementBean.setHeight(height);
        setLayoutParams(layoutParams);
        setCentralY(centralPosY);
    }

    public int getParamWidth(){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.width;
    }

    public int getParamHeight(){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.height;
    }





    @Override
    protected void onDraw(Canvas canvas) {
        onElementDraw(canvas);
        super.onDraw(canvas);
    }


    protected int getNormalColor() {
        return normalColor;
    }


    protected int getDefaultStrokeWidth() {
        DisplayMetrics screen = getResources().getDisplayMetrics();
        return (int)(screen.heightPixels*0.004f);
    }


    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Ignore secondary touches on controls
        //
        // NB: We can get an additional pointer down if the user touches a non-StreamView area
        // while also touching an OSC control, even if that pointer down doesn't correspond to
        // an area of the OSC control.
        if (event.getActionIndex() != 0) {
            return true;

        }

        return onElementTouchEvent(event);
    }

    abstract protected void onElementDraw(Canvas canvas);

    abstract public boolean onElementTouchEvent(MotionEvent event);

    protected static void _DBG(String text) {
        System.out.println(text);
    }


    public void setNormalColor(int normalColor) {
        this.normalColor = normalColor;
        invalidate();
    }

    public void setPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
        invalidate();
    }

    public void setOpacity(int opacity) {
        int hexOpacity = opacity * 255 / 100;
        this.normalColor = (hexOpacity << 24) | (normalColor & 0x00FFFFFF);
        this.pressedColor = (hexOpacity << 24) | (pressedColor & 0x00FFFFFF);

        invalidate();
    }

    protected final float getPercent(float value, float percent) {
        return value / 100 * percent;
    }

    protected final int getCorrectWidth() {
        return getWidth() > getHeight() ? getHeight() : getWidth();
    }

    @Override
    public String toString() {
        return "Name:" + elementBean.getName() + "," +
                "X:" + getCentralX() + "," +
                "Y:" + getCentralY() + "," +
                "W:" + getParamWidth() + "," +
                "H:" + getParamHeight();
    }
}
