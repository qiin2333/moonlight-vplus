package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;
import com.limelight.utils.ColorPickerUtils;

import java.util.Map;

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
public class DigitalCombineButton extends Element {

    private static final String COLUMN_STRING_ELEMENT_VALUE_1 = COLUMN_STRING_ELEMENT_VALUE;
    private static final String COLUMN_STRING_ELEMENT_VALUE_2 = COLUMN_STRING_ELEMENT_UP_VALUE;
    private static final String COLUMN_STRING_ELEMENT_VALUE_3 = COLUMN_STRING_ELEMENT_DOWN_VALUE;
    private static final String COLUMN_STRING_ELEMENT_VALUE_4 = COLUMN_STRING_ELEMENT_LEFT_VALUE;
    private static final String COLUMN_STRING_ELEMENT_VALUE_5 = COLUMN_STRING_ELEMENT_RIGHT_VALUE;

    /**
     * Listener interface to update registered observers.
     */
    public interface DigitalCombineButtonListener {

        /**
         * onClick event will be fired on button click.
         */
        void onClick();

        /**
         * onLongClick event will be fired on button long click.
         */
        void onLongClick();

        /**
         * onRelease event will be fired on button unpress.
         */
        void onRelease();
    }
    
    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private PageDeviceController pageDeviceController;
    private DigitalCombineButton digitalCombineButton;

    private DigitalCombineButtonListener listener;
    private ElementController.SendEventHandler value1SendHandler;
    private ElementController.SendEventHandler value2SendHandler;
    private ElementController.SendEventHandler value3SendHandler;
    private ElementController.SendEventHandler value4SendHandler;
    private ElementController.SendEventHandler value5SendHandler;
    private String text;
    private String value1;
    private String value2;
    private String value3;
    private String value4;
    private String value5;
    private int radius;
    private int thick;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;

    private SuperPageLayout digitalCombineButtonPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;


    private long timerLongClickTimeout = 3000;
    private final Runnable longClickRunnable = new Runnable() {
        @Override
        public void run() {
            onLongClickCallback();
        }
    };
    private final Paint paintBorder = new Paint();
    private final Paint paintBackground = new Paint();
    private final Paint paintText = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();



    public DigitalCombineButton(Map<String,Object> attributesMap,
                                ElementController controller,
                                PageDeviceController pageDeviceController, Context context) {
        super(attributesMap,controller,context);
        this.pageDeviceController = pageDeviceController;
        this.digitalCombineButton = this;

        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game)context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        super.centralXMax  = displayMetrics.widthPixels;
        super.centralXMin  = 0;
        super.centralYMax  = displayMetrics.heightPixels;
        super.centralYMin  = 0;
        super.widthMax  = displayMetrics.widthPixels / 2;
        super.widthMin  = 50;
        super.heightMax  = displayMetrics.heightPixels / 2;
        super.heightMin  = 50;

        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));
        paintText.setTextAlign(Paint.Align.CENTER);
        paintBorder.setStyle(Paint.Style.STROKE);
        paintBackground.setStyle(Paint.Style.FILL);


        text = (String) attributesMap.get(COLUMN_STRING_ELEMENT_TEXT);
        radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        value1 = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_1);
        value2 = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_2);
        value3 = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_3);
        value4 = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_4);
        value5 = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_5);
        value1SendHandler = controller.getSendEventHandler(value1);
        value2SendHandler = controller.getSendEventHandler(value2);
        value3SendHandler = controller.getSendEventHandler(value3);
        value4SendHandler = controller.getSendEventHandler(value4);
        value5SendHandler = controller.getSendEventHandler(value5);
        listener = new DigitalCombineButtonListener() {
            @Override
            public void onClick() {
                value1SendHandler.sendEvent(true);
                value2SendHandler.sendEvent(true);
                value3SendHandler.sendEvent(true);
                value4SendHandler.sendEvent(true);
                value5SendHandler.sendEvent(true);
            }

            @Override
            public void onLongClick() {

            }

            @Override
            public void onRelease() {
                value1SendHandler.sendEvent(false);
                value2SendHandler.sendEvent(false);
                value3SendHandler.sendEvent(false);
                value4SendHandler.sendEvent(false);
                value5SendHandler.sendEvent(false);
            }
        };
    }

    @Override
    protected void onElementDraw(Canvas canvas) {

        // 文字
        int elementWidth = getElementWidth();
        int elementHeight = getElementHeight();
        float textSize = getPercent(elementWidth, 25);
        textSize = Math.min(textSize,getPercent(elementHeight,80));
        paintText.setTextSize(textSize);
        paintText.setColor(isPressed() ? pressedColor : normalColor);
        // 边框
        paintBorder.setStrokeWidth(thick);
        paintBorder.setColor(isPressed() ? pressedColor : normalColor);
        // 背景颜色
        paintBackground.setColor(backgroundColor);
        // 绘画范围
        rect.left = rect.top = (float) thick / 2;
        rect.right = getElementWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;
        // 绘制背景
        canvas.drawRoundRect(rect, radius, radius, paintBackground);
        // 绘制边框
        canvas.drawRoundRect(rect, radius, radius, paintBorder);
        // 绘制文字
        canvas.drawText(text, getPercent(elementWidth, 50), getPercent(elementHeight, 63), paintText);

        ElementController.Mode mode = elementController.getMode();
        if (mode == ElementController.Mode.Edit || mode == ElementController.Mode.Select){
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(editColor);
            canvas.drawRect(rect,paintEdit);
        }
    }

    private void onClickCallback() {
        // notify listenersbuttonListener.onClick();
        System.out.println("onClickCallback");
        listener.onClick();
        elementController.getHandler().removeCallbacks(longClickRunnable);
        elementController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout);

    }

    private void onLongClickCallback() {
        // notify listeners
        listener.onLongClick();
    }

    private void onReleaseCallback() {
        // notify listeners
        System.out.println("onReleaseCallback");
        listener.onRelease();

        // We may be called for a release without a prior click
        elementController.getHandler().removeCallbacks(longClickRunnable);
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // get masked (not specific to a pointer) action
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                elementController.buttonVibrator();
                setPressed(true);
                onClickCallback();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                onReleaseCallback();
                invalidate();
                return true;
            }
            default: {
            }
        }
        return true;
    }

    @Override
    public void save() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_1, value1);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_2, value2);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_3, value3);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_4, value4);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_5, value5);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
        contentValues.put(COLUMN_INT_ELEMENT_THICK,thick);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,normalColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,pressedColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
        elementController.updateElement(elementId,contentValues);

    }

    @Override
    protected void updatePage() {
        if (digitalCombineButtonPage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }

    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (digitalCombineButtonPage == null){
            digitalCombineButtonPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_digital_combine_button,null);
            centralXNumberSeekbar = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_central_x);
            centralYNumberSeekbar = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_central_y);

        }

        NumberSeekbar widthNumberSeekbar = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_width);
        NumberSeekbar heightNumberSeekbar = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_height);
        NumberSeekbar radiusNumberSeekbar = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_radius);
        ElementEditText textElementEditText = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_text);
        TextView value1TextView = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_value_1);
        TextView value2TextView = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_value_2);
        TextView value3TextView = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_value_3);
        TextView value4TextView = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_value_4);
        TextView value5TextView = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_value_5);
        NumberSeekbar thickNumberSeekbar = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_thick);
        NumberSeekbar layerNumberSeekbar = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_layer);
        ElementEditText normalColorElementEditText = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_normal_color);
        ElementEditText pressedColorElementEditText = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_pressed_color);
        ElementEditText backgroundColorElementEditText = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_background_color);
        Button copyButton = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_copy);
        Button deleteButton = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_delete);

        textElementEditText.setTextWithNoTextChangedCallBack(text);
        textElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                setElementText(text);
                save();
            }
        });




        value1TextView.setText(pageDeviceController.getKeyNameByValue(value1));
        value1TextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementValue1(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });

        value2TextView.setText(pageDeviceController.getKeyNameByValue(value2));
        value2TextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementValue2(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });

        value3TextView.setText(pageDeviceController.getKeyNameByValue(value3));
        value3TextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementValue3(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });


        value4TextView.setText(pageDeviceController.getKeyNameByValue(value4));
        value4TextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementValue4(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });


        value5TextView.setText(pageDeviceController.getKeyNameByValue(value5));
        value5TextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementValue5(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });
        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementCentralX(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });
        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementCentralY(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });


        widthNumberSeekbar.setProgressMax(widthMax);
        widthNumberSeekbar.setProgressMin(widthMin);
        widthNumberSeekbar.setValueWithNoCallBack(getElementWidth());
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementWidth(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
                save();
            }
        });

        heightNumberSeekbar.setProgressMax(heightMax);
        heightNumberSeekbar.setProgressMin(heightMin);
        heightNumberSeekbar.setValueWithNoCallBack(getElementHeight());
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementHeight(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
                save();
            }
        });



        radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
        radiusNumberSeekbar.setValueWithNoCallBack(radius);
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementRadius(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });


        thickNumberSeekbar.setValueWithNoCallBack(thick);
        thickNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementThick(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });


        layerNumberSeekbar.setValueWithNoCallBack(layer);
        layerNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setElementLayer(seekBar.getProgress());
                save();
            }
        });

        setupColorPickerButton(normalColorElementEditText, () -> this.normalColor, this::setElementNormalColor);
        setupColorPickerButton(pressedColorElementEditText, () -> this.pressedColor, this::setElementPressedColor);
        setupColorPickerButton(backgroundColorElementEditText, () -> this.backgroundColor, this::setElementBackgroundColor);

        copyButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON);
                contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text);
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE_1, value1);
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE_2, value2);
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE_3, value3);
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE_4, value4);
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE_5, value5);
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
                contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,Math.max(Math.min(getElementCentralX() + getElementWidth(),centralXMax),centralXMin));
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
                contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
                contentValues.put(COLUMN_INT_ELEMENT_THICK,thick);
                contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,normalColor);
                contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,pressedColor);
                contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
                elementController.addElement(contentValues);
            }
        });

        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                elementController.toggleInfoPage(digitalCombineButtonPage);
                elementController.deleteElement(digitalCombineButton);
            }
        });



        return digitalCombineButtonPage;
    }

    protected void setElementText(String text) {
        this.text = text;
        invalidate();
    }

    protected void setElementValue1(String value1) {
        this.value1 = value1;
        value1SendHandler = elementController.getSendEventHandler(value1);
    }
    protected void setElementValue2(String value2) {
        this.value2 = value2;
        value2SendHandler = elementController.getSendEventHandler(value2);
    }
    protected void setElementValue3(String value3) {
        this.value3 = value3;
        value3SendHandler = elementController.getSendEventHandler(value3);
    }
    protected void setElementValue4(String value4) {
        this.value4 = value4;
        value4SendHandler = elementController.getSendEventHandler(value4);
    }
    protected void setElementValue5(String value5) {
        this.value5 = value5;
        value5SendHandler = elementController.getSendEventHandler(value5);
    }

    protected void setElementRadius(int radius) {
        this.radius = radius;
        invalidate();
    }

    protected void setElementThick(int thick) {
        this.thick = thick;
        invalidate();
    }

    protected void setElementNormalColor(int normalColor) {
        this.normalColor = normalColor;
        invalidate();
    }

    protected void setElementPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
        invalidate();
    }

    protected void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
    }

    public static ContentValues getInitialInfo(){
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT,"组合键");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_1,"k29");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_2,"null");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_3,"null");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_4,"null");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_5,"null");
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH,100);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,100);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,100);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,100);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,0);
        contentValues.put(COLUMN_INT_ELEMENT_THICK,5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,0x00FFFFFF);
        return contentValues;


    }

    private interface IntSupplier {
        int get();
    }

    private interface IntConsumer {
        void accept(int value);
    }
    /**
     * 更新颜色显示按钮的外观（文本、背景色、文本颜色）。
     */
    private void updateColorDisplay(ElementEditText colorDisplay, int color) {
        // 显示十六进制颜色码
        colorDisplay.setTextWithNoTextChangedCallBack(String.format("%08X", color));
        // 将背景设置为当前颜色
        colorDisplay.setBackgroundColor(color);

        // 根据背景色的亮度自动设置文本颜色为黑色或白色，以确保可读性
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        colorDisplay.setTextColor(luminance > 0.5 ? Color.BLACK : Color.WHITE);
        colorDisplay.setGravity(Gravity.CENTER);
    }

    /**
     * 配置一个 ElementEditText 控件，使其作为颜色选择器按钮使用。
     *
     * @param colorDisplay 用于作为按钮的 ElementEditText 视图。
     * @param initialColorFetcher 一个用于获取当前颜色值的 Lambda 表达式。
     * @param colorUpdater      一个用于设置新颜色值的 Lambda 表达式。
     */
    private void setupColorPickerButton(ElementEditText colorDisplay, IntSupplier initialColorFetcher, IntConsumer colorUpdater) {
        // 禁输入，让 EditText 表现得像一个按钮
        colorDisplay.setFocusable(false);
        colorDisplay.setCursorVisible(false);
        colorDisplay.setKeyListener(null);

        // 使用传入的 Lambda 获取初始颜色并设置外观
        updateColorDisplay(colorDisplay, initialColorFetcher.get());

        // 设置点击监听器，打开颜色选择器
        colorDisplay.setOnClickListener(v -> {
            // 再次获取当前颜色，确保打开时颜色是最新的
            ColorPickerUtils.show(getContext(), initialColorFetcher.get(), newColor -> {
                colorUpdater.accept(newColor); // 使用传入的 Lambda 更新颜色属性
                save();                      // 保存更改
                updateColorDisplay(colorDisplay, newColor); // 更新UI显示
            });
        });
    }
}
