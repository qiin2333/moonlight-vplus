package com.limelight.binding.input.advance_setting.element;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.Spanned;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
    public static final String COLUMN_INT_ELEMENT_FLAG1 = "element_flag1";

    public static final String COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR = "normalTextColor";
    public static final String COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR = "pressedTextColor";
    public static final String COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT = "textSizePercent";
    public static final String COLUMN_STRING_EXTRA_ATTRIBUTES = "extra_attributes";

    public static final int ELEMENT_TYPE_DIGITAL_COMMON_BUTTON = 0;
    public static final int ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON = 1;
    public static final int ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON = 2;
    public static final int ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON = 3;
    public static final int ELEMENT_TYPE_GROUP_BUTTON = 4;
    public static final int ELEMENT_TYPE_DIGITAL_PAD = 20;
    public static final int ELEMENT_TYPE_ANALOG_STICK = 30;
    public static final int ELEMENT_TYPE_DIGITAL_STICK = 31;
    public static final int ELEMENT_TYPE_INVISIBLE_ANALOG_STICK = 32;
    public static final int ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK = 33;
    public static final int ELEMENT_TYPE_SIMPLIFY_PERFORMANCE = 50;

    public static final int EDIT_COLOR_EDIT = 0xf0dc143c;
    public static final int EDIT_COLOR_SELECT = 0xfffe9900;
    public static final int EDIT_COLOR_SELECTED = 0xff0112ff;
    public final static int ELEMENT_TYPE_WHEEL_PAD = 54;


    // 在编辑模式下，如果元素被按住超过系统定义的长按时间，就允许拖动，
    // 以避免用户想打开按键设置而不是移动按键
    private static final long DRAG_EDIT_LONG_PRESS_TIMEOUT = 250;
    private boolean longPressDetected = false;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            longPressDetected = true;
            // 可以添加视觉反馈，比如改变边框颜色
            editColor = 0xff00f91a; // 绿色表示可以拖动
            invalidate();
        }
    };



    public interface ElementSelectedCallBack {
        void elementSelected(Element element);
    }


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
    protected int layer;
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
    protected int editColor = EDIT_COLOR_EDIT;
    private ElementSelectedCallBack elementSelectedCallBack;


    public Element(Map<String, Object> attributesMap, ElementController elementController, Context context) {
        super(context);
        this.context = context;
        this.elementId = (Long) attributesMap.get(Element.COLUMN_LONG_ELEMENT_ID);
        this.configId = (Long) attributesMap.get(Element.COLUMN_LONG_CONFIG_ID);
        this.elementType = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_TYPE)).intValue();
        this.layer = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_LAYER)).intValue();
        this.elementController = elementController;

    }

    protected int getElementCentralX() {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.leftMargin + layoutParams.width / 2;
    }

    protected int getElementCentralY() {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.topMargin + layoutParams.height / 2;
    }

    protected int getElementWidth() {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.width;
    }

    protected int getElementHeight() {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        return layoutParams.height;
    }

    protected void setElementCentralX(int centralX) {
        centralX = elementController.editGridHandle(centralX);
        if (centralX > centralXMax) {
            centralX = elementController.editGridHandle(centralXMax);
        }
        innerSetElementCentralX(centralX);
    }

    protected void setElementCentralY(int centralY) {

        centralY = elementController.editGridHandle(centralY);
        if (centralY > centralYMax) {
            centralY = elementController.editGridHandle(centralYMax);
        }

        innerSetElementCentralY(centralY);
    }

    //inner 方法防止setWidth、Height方法会调用子类重写的setElementCentralX、Y
    private void innerSetElementCentralX(int centralX) {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        if (centralX > centralXMax) {
            layoutParams.leftMargin = centralXMax - layoutParams.width / 2;
        } else if (centralX < centralXMin) {
            layoutParams.leftMargin = centralXMin - layoutParams.width / 2;
        } else {
            layoutParams.leftMargin = centralX - layoutParams.width / 2;
        }
        //保存中心点坐标
        requestLayout();
    }

    private void innerSetElementCentralY(int centralY) {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        if (centralY > centralYMax) {
            layoutParams.topMargin = centralYMax - layoutParams.height / 2;
        } else if (centralY < centralYMin) {
            layoutParams.topMargin = centralYMin - layoutParams.height / 2;
        } else {
            layoutParams.topMargin = centralY - layoutParams.height / 2;
        }
        requestLayout();
    }

    protected void setElementWidth(int width) {
        int centralPosX = getElementCentralX();
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        if (width > widthMax) {
            layoutParams.width = widthMax;
        } else if (width < widthMin) {
            layoutParams.width = widthMin;
        } else {
            layoutParams.width = width;
        }
        innerSetElementCentralX(centralPosX);
    }

    protected void setElementHeight(int height) {
        int centralPosY = getElementCentralY();
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        if (height > heightMax) {
            layoutParams.height = heightMax;
        } else if (height < heightMin) {
            layoutParams.height = heightMin;
        } else {
            layoutParams.height = height;
        }
        innerSetElementCentralY(centralPosY);
    }

    protected void setElementLayer(int layer) {
        this.layer = layer;
        elementController.adjustLayer(this);
    }

    public void setEditColor(int editColor) {
        this.editColor = editColor;
    }

    public void setElementSelectedCallBack(ElementSelectedCallBack elementSelectedCallBack) {
        this.elementSelectedCallBack = elementSelectedCallBack;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        onElementDraw(canvas);
        super.onDraw(canvas);
    }

    /**
     * 当全局模式（正常、编辑、选择）更改时，由ElementController调用。
     * 子类可以重写此项以更新其状态。
     */
    public void onModeChanged(ElementController.Mode newMode) {
        // Default implementation does nothing.
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 忽略多点触控中的非主手指
        if (event.getActionIndex() != 0) {
            return true;
        }

        // 确保控制器存在
        if (elementController == null) {
            return true;
        }

        switch (elementController.getMode()) {
            case Normal:
                // Normal 模式逻辑
                return onElementTouchEvent(event);

            case Edit:
                // Edit 模式逻辑
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        lastX = event.getX();
                        lastY = event.getY();
                        isClick = true;
                        longPressDetected = false;
                        editColor = 0xffdc143c; // 红色表示初始状态
                        invalidate();

                        // 启动长按检测
                        elementController.getHandler().removeCallbacks(longPressRunnable);
                        elementController.getHandler().postDelayed(longPressRunnable, DRAG_EDIT_LONG_PRESS_TIMEOUT);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float x = event.getX();
                        float y = event.getY();
                        float deltaX = x - lastX;
                        float deltaY = y - lastY;

                        // 小位移算作点击
                        if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2) {
                            return true;
                        }

                        // 只有检测到长按或没有开启长按移动后才允许拖动
                        if (!elementController.isDragEditEnabled() | longPressDetected) {
                            isClick = false;
                            setElementCentralX((int) getX() + getWidth() / 2 + (int) deltaX);
                            setElementCentralY((int) getY() + getHeight() / 2 + (int) deltaY);
                            updatePage();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP: {
                        // 取消长按检测
                        elementController.getHandler().removeCallbacks(longPressRunnable);

                        editColor = 0xffdc143c;
                        invalidate();

                        if (isClick || !longPressDetected) {
                            elementController.toggleInfoPage(getInfoPage());
                        } else {
                                save();
                        }
                        return true;
                    }
                }
                return true;
            //组按键选择子按键时使用，可更改为ACTION_DOWN来快速（滑动）选择按键
            case Select:
                // 1. 触发时机ACTION_UP (手指抬起时)
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {

                    // 2. 增加对回调的 null 检查，防止应用崩溃
                    if (elementSelectedCallBack != null) {
                        // 3. 安全地调用回调方法
                        // 假设回调接口的方法名是 onElementSelected 或者 elementSelected
                        elementSelectedCallBack.elementSelected (this);
                    }
                }
                // 4. 必须返回 true，表示事件已被处理
                return true;
        }
        return true;
    }

    abstract protected SuperPageLayout getInfoPage();

    abstract protected void updatePage();

    abstract protected void save();

    abstract protected void onElementDraw(Canvas canvas);

    abstract public boolean onElementTouchEvent(MotionEvent event);

    protected final float getPercent(float value, float percent) {
        return value / 100 * percent;
    }

}
