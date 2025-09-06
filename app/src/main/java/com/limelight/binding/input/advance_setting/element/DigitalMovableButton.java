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
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.TouchController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;
import com.limelight.utils.ColorPickerDialog;

import java.util.Map;

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
public class DigitalMovableButton extends Element {

    /**
     * Listener interface to update registered observers.
     */
    public interface DigitalMovableButtonListener {

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

    private TouchController touchController;
    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private PageDeviceController pageDeviceController;
    private DigitalMovableButton digitalMovableButton;

    private DigitalMovableButtonListener listener;
    private ElementController.SendEventHandler valueSendHandler;
    private final Game game;
    private String text;
    private String value;
    private int enableTouch = 0;
    private int radius;
    private int sense;
    private int thick;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;

    private SuperPageLayout digitalMovableButtonPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;


    private float lastX;
    private float lastY;

    private boolean isFirstTouch = true;
    private float FirstTouchX = 0;
    private float FirstTouchY = 0;


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



    public DigitalMovableButton(Map<String,Object> attributesMap,
                                ElementController controller,
                                TouchController touchController,
                                PageDeviceController pageDeviceController, Context context) {
        super(attributesMap,controller,context);
        this.touchController = touchController;
        this.pageDeviceController = pageDeviceController;
        this.digitalMovableButton = this;

        this.game = (Game) context;

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

        try {
            text = (String) attributesMap.get(COLUMN_STRING_ELEMENT_TEXT);
            radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
            sense = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_SENSE)).intValue();
            thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
            normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
            pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
            backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
            value = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);
            enableTouch = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_MODE)).intValue();
        } catch (Exception e) {
            // 处理旧版布局兼容性问题，设置默认值
            if (text == null) text = "A";
            if (radius == 0) radius = 0;
            if (sense == 0) sense = 100;
            if (thick == 0) thick = 5;
            if (normalColor == 0) normalColor = 0xF0888888;
            if (pressedColor == 0) pressedColor = 0xF00000FF;
            if (backgroundColor == 0) backgroundColor = 0x00FFFFFF;
            if (value == null) value = "k29";
            if (enableTouch == 0) enableTouch = 0;
            System.out.println("加载按键时发生错误，已应用默认值: " + e.getMessage());
        }
        
        valueSendHandler = controller.getSendEventHandler(value);
        listener = new DigitalMovableButtonListener() {
            @Override
            public void onClick() {
                valueSendHandler.sendEvent(true);
            }

            @Override
            public void onLongClick() {

            }

            @Override
            public void onRelease() {
                valueSendHandler.sendEvent(false);
            }
        };
    }

    @Override
    protected void onElementDraw(Canvas canvas) {

        // 文字
        int elementWidth = getElementWidth();
        int elementHeight = getElementHeight();
        float textSize = getPercent(elementWidth, 25);
        textSize = Math.min(textSize,getPercent(elementHeight,63));
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
        if(enableTouch==1) {
            if (isFirstTouch) {
                isFirstTouch = false;
                FirstTouchX = event.getX();
                FirstTouchY = event.getY();
            }
            float touchXTemp, touchYTemp;

            touchXTemp = (float) (game.getStreamView().getWidth() / 2 + (event.getX() - FirstTouchX) * sense * 0.01);
            touchYTemp = (float) (game.getStreamView().getHeight() / 2 + (event.getY() - FirstTouchY) * sense * 0.01);

            MotionEvent EventTemp = MotionEvent.obtain(
                    event.getDownTime(),    // 按下时间
                    event.getEventTime(),   // 事件时间
                    action,      // 动作类型
                    touchXTemp,      // X坐标
                    touchYTemp,      // Y坐标
                    event.getPressure(),    // 压力值
                    event.getSize(),        // 触摸大小
                    event.getMetaState(),   // 元状态
                    event.getXPrecision(),  // X精度
                    event.getYPrecision(),  // Y精度
                    event.getDeviceId(),    // 设备ID
                    event.getEdgeFlags()    // 边缘标志
            );

            if (touchXTemp < 0 || touchXTemp > game.getStreamView().getWidth() || touchYTemp < 0 || touchYTemp > game.getStreamView().getHeight()) {
                FirstTouchX = event.getX();
                FirstTouchY = event.getY();
                EventTemp.setAction(MotionEvent.ACTION_CANCEL);
            }
            try {
                game.getHandleMotionEvent(game.getStreamView(), EventTemp);
            } catch (NullPointerException e) {
                System.out.println("NullPointerException");
            }
        }
        else {
            FirstTouchX = event.getX();
            FirstTouchY = event.getY();
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                elementController.buttonVibrator();
                lastX = event.getX();
                lastY = event.getY();
                setPressed(true);
                onClickCallback();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float deltaX = event.getX() - lastX;
                float deltaY = event.getY() - lastY;
                if(enableTouch!=1) {
                    touchController.mouseMove(deltaX, deltaY, 0.01 * sense);
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                FirstTouchX = 0;
                FirstTouchY = 0;
                isFirstTouch = true;
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
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
        contentValues.put(COLUMN_INT_ELEMENT_MODE,enableTouch);
        contentValues.put(COLUMN_INT_ELEMENT_SENSE,sense);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
        contentValues.put(COLUMN_INT_ELEMENT_THICK,thick);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,normalColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,pressedColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
        elementController.updateElement(elementId,contentValues);
    }

    @Override
    protected void updatePage() {
        if (digitalMovableButtonPage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (digitalMovableButtonPage == null){
            digitalMovableButtonPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_digital_movable_button,null);
            centralXNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_central_x);
            centralYNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_central_y);
        }

        NumberSeekbar widthNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_width);
        NumberSeekbar heightNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_height);
        NumberSeekbar radiusNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_radius);
        ElementEditText textElementEditText = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_text);
        TextView valueTextView = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_value);
        Switch enableTouchSwitch = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_enable_touch);
        NumberSeekbar senseNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_sense);
        NumberSeekbar thickNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_thick);
        NumberSeekbar layerNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_layer);
        ElementEditText normalColorElementEditText = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_normal_color);
        ElementEditText pressedColorElementEditText = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_pressed_color);
        ElementEditText backgroundColorElementEditText = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_background_color);
        Button copyButton = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_copy);
        Button deleteButton = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_delete);

        textElementEditText.setTextWithNoTextChangedCallBack(text);
        textElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                setElementText(text);
                save();
            }
        });

        valueTextView.setText(pageDeviceController.getKeyNameByValue(value));
        valueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        CharSequence text = key.getText();
                        // page页设置值文本
                        ((TextView) v).setText(text);
                        // element text 设置文本
                        textElementEditText.setText(text);
                        setElementValue(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });

        enableTouchSwitch.setChecked(enableTouch==1);
        enableTouchSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                enableTouch = isChecked ? 1 : 0;
                save();
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

        senseNumberSeekbar.setValueWithNoCallBack(sense);
        senseNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementSense(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
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
                contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON);
                contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text);
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
                contentValues.put(COLUMN_INT_ELEMENT_MODE,enableTouch);
                contentValues.put(COLUMN_INT_ELEMENT_SENSE,sense);
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
                elementController.toggleInfoPage(digitalMovableButtonPage);
                elementController.deleteElement(digitalMovableButton);
            }
        });

        return digitalMovableButtonPage;
    }

    protected void setElementText(String text) {
        this.text = text;
        invalidate();
    }

    protected void setElementValue(String value) {
        this.value = value;
        valueSendHandler = elementController.getSendEventHandler(value);
    }

    protected void setElementSense(int sense){
        this.sense = sense;
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
    }

    protected void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
    }

    public static ContentValues getInitialInfo(){
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT,"A");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE,"k29");
        contentValues.put(COLUMN_INT_ELEMENT_MODE,0);
        contentValues.put(COLUMN_INT_ELEMENT_SENSE,100);
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
            new ColorPickerDialog(
                    getContext(),
                    initialColorFetcher.get(),
                    true, // true 表示显示 Alpha 透明度滑块
                    newColor -> {
                        colorUpdater.accept(newColor); // 使用传入的 Lambda 更新颜色属性
                        save();                      // 保存更改
                        updateColorDisplay(colorDisplay, newColor); // 更新UI显示
                    }
            ).show(); // <-- 主要变化：在最后调用 .show()
        });
    }
}
