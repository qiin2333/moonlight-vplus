package com.limelight.binding.input.advance_setting.element;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.Spanned;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.Map;

public abstract class Element extends View {


    public static final String COLUMN_LONG_CONFIG_ID = "config_id";
    public static final String COLUMN_LONG_ELEMENT_ID = "element_id";
    public static final String COLUMN_INT_ELEMENT_TYPE = "element_type";
    public static final String COLUMN_STRING_ELEMENT_VALUE = "element_value";
    public static final String COLUMN_STRING_ELEMENT_MIDDLE_VALUE = "element_middle_value";
    public static final String COLUMN_STRING_ELEMENT_UP_VALUE = "element_up_value";
    public static final String COLUMN_STRING_ELEMENT_DOWN_VALUE = "element_down_value";
    public static final String COLUMN_STRING_ELEMENT_LEFT_VALUE = "element_left_value";
    public static final String COLUMN_STRING_ELEMENT_RIGHT_VALUE = "element_right_value";
    public static final String COLUMN_STRING_ELEMENT_TEXT = "element_text";
    public static final String COLUMN_INT_ELEMENT_WIDTH = "element_width";
    public static final String COLUMN_INT_ELEMENT_HEIGHT = "element_height";
    public static final String COLUMN_INT_ELEMENT_LAYER = "element_layer";
    public static final String COLUMN_INT_ELEMENT_MODE = "element_mode";
    public static final String COLUMN_INT_ELEMENT_SENSE = "element_sense";
    public static final String COLUMN_INT_ELEMENT_CENTRAL_X = "element_central_x";
    public static final String COLUMN_INT_ELEMENT_CENTRAL_Y = "element_central_y";
    public static final String COLUMN_INT_ELEMENT_RADIUS = "element_radius";
    public static final String COLUMN_INT_ELEMENT_OPACITY = "element_opacity";
    public static final String COLUMN_INT_ELEMENT_THICK = "element_thick";
    public static final String COLUMN_INT_ELEMENT_NORMAL_COLOR = "element_color";
    public static final String COLUMN_INT_ELEMENT_PRESSED_COLOR = "element_pressed_color";
    public static final String COLUMN_INT_ELEMENT_BACKGROUND_COLOR = "element_background_color";

    public static final int ELEMENT_TYPE_DIGITAL_COMMON_BUTTON = 0;
    public static final int ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON = 1;
    public static final int ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON = 2;
    public static final int ELEMENT_TYPE_GROUP_BUTTON = 3;
    public static final int ELEMENT_TYPE_DIGITAL_PAD = 20;
    public static final int ELEMENT_TYPE_ANALOG_STICK = 30;
    public static final int ELEMENT_TYPE_DIGITAL_STICK = 31;
    public static final int ELEMENT_TYPE_INVISIBLE_ANALOG_STICK = 32;
    public static final int ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK = 33;
    public static final int ELEMENT_TYPE_SIMPLIFY_PERFORMANCE = 50;
    public static final int ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON = 51;




    protected class HexInputFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            for (int i = start; i < end; i++) {
                if (!Character.isDigit(source.charAt(i)) && (source.charAt(i) < 'A' || source.charAt(i) > 'F')) {
                    return "";
                }
            }
            return null;
        }
    }


    protected final Long elementId;
    protected final Long configId;
    protected final int elementType;
    protected final int layer;
    protected final ElementController elementController;
    private Context context;
    private final Paint paint = new Paint();
    private final RectF rect = new RectF();
    protected int centralXMax;
    protected int centralXMin;
    protected int centralYMax;
    protected int centralYMin;
    protected int widthMax;
    protected int widthMin;
    protected int heightMax;
    protected int heightMin;
    private float lastX;
    private float lastY;
    private boolean isClick = true;
    protected int editColor = 0xf0dc143c;


    public Element(Map<String,Object> attributesMap, ElementController elementController, Context context) {
        super(context);
        this.context = context;
        this.elementId = (Long) attributesMap.get(Element.COLUMN_LONG_ELEMENT_ID);
        this.configId = (Long)attributesMap.get(Element.COLUMN_LONG_CONFIG_ID);
        this.elementType = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_TYPE)).intValue();
        this.layer = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_LAYER)).intValue();
        this.elementController = elementController;

    }

    protected int getCentralX(){
        return (int) getX() + getWidth() / 2;
    }

    protected int getCentralY(){
        return (int) getY() + getHeight() / 2;
    }

    protected int getParamCentralX(){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.leftMargin + layoutParams.width / 2;
    }

    protected int getParamCentralY(){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.topMargin + layoutParams.height / 2;
    }


    protected void setParamCentralX(int centralX){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        if (centralX > centralXMax){
            layoutParams.leftMargin = centralXMax - layoutParams.width/2;
        } else if (centralX < centralXMin){
            layoutParams.leftMargin = centralXMin - layoutParams.width/2;
        } else {
            layoutParams.leftMargin = centralX - layoutParams.width/2;
        }
        //保存中心点坐标
        requestLayout();


    }

    protected void setParamCentralY(int centralY){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        if (centralY > centralYMax){
            layoutParams.topMargin = centralYMax - layoutParams.height/2;
        } else if (centralY < centralYMin){
            layoutParams.topMargin = centralYMin - layoutParams.height/2;
        } else {
            layoutParams.topMargin = centralY - layoutParams.height/2;
        }
        requestLayout();
    }

    protected void setParamWidth(int width){
        int centralPosX = getParamCentralX();
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        if (width > widthMax){
            layoutParams.width = widthMax;
        } else if (width < widthMin){
            layoutParams.width = widthMin;
        } else {
            layoutParams.width = width;
        }
        setParamCentralX(centralPosX);
    }

    protected void setParamHeight(int height){
        int centralPosY = getParamCentralY();
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        if (height > heightMax){
            layoutParams.height = heightMax;
        } else if (height < heightMin){
            layoutParams.height = heightMin;
        } else {
            layoutParams.height = height;
        }
        setParamCentralY(centralPosY);
    }

    protected int getParamWidth(){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.width;
    }

    protected int getParamHeight(){
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.height;
    }



    @Override
    protected void onDraw(Canvas canvas) {
        onElementDraw(canvas);
        super.onDraw(canvas);
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

        if (elementController.getMode() == ElementController.Mode.Normal){
            return onElementTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastX = event.getX();
                lastY = event.getY();
                isClick = true;
                editColor = 0xff00f91a;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float x = event.getX();
                float y = event.getY();
                float deltaX = x - lastX;
                float deltaY = y - lastY;
                 //小位移算作点击
                if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2){
                    return true;
                }
                isClick = false;
                setParamCentralX(getCentralX() + (int) deltaX);
                setParamCentralY(getCentralY() + (int) deltaY);
                updatePageInfo();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                editColor = 0xffdc143c;
                invalidate();
                if (isClick){
                    elementController.toggleInfoPage(getInfoPage());
                } else {
                    updateDataBase();
                }
                return true;
            }
            default: {
            }
        }
        return true;
    }
    abstract protected SuperPageLayout getInfoPage();

    abstract protected void updatePageInfo();

    abstract protected void updateDataBase();
    abstract protected void onElementDraw(Canvas canvas);

    abstract public boolean onElementTouchEvent(MotionEvent event);


    protected final float getPercent(float value, float percent) {
        return value / 100 * percent;
    }

}
