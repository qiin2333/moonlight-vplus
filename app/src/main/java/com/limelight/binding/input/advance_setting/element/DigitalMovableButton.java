//可移动按键
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
    // New member variables
    private int normalTextColor;
    private int pressedTextColor;
    private int textSizePercent;

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


    public DigitalMovableButton(Map<String, Object> attributesMap,
                                ElementController controller,
                                TouchController touchController,
                                PageDeviceController pageDeviceController, Context context) {
        super(attributesMap, controller, context);
        this.touchController = touchController;
        this.pageDeviceController = pageDeviceController;
        this.digitalMovableButton = this;

        this.game = (Game) context;

        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game) context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        super.centralXMax = displayMetrics.widthPixels;
        super.centralXMin = 0;
        super.centralYMax = displayMetrics.heightPixels;
        super.centralYMin = 0;
        super.widthMax = displayMetrics.widthPixels / 2;
        super.widthMin = 50;
        super.heightMax = displayMetrics.heightPixels / 2;
        super.heightMin = 50;

        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));
        paintText.setTextAlign(Paint.Align.CENTER);
        paintBorder.setStyle(Paint.Style.STROKE);
        paintBackground.setStyle(Paint.Style.FILL);

        // Standard properties
        text = (String) attributesMap.get(COLUMN_STRING_ELEMENT_TEXT);
        radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
        sense = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_SENSE)).intValue();
        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        value = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);
        Object modeObj = attributesMap.get(COLUMN_INT_ELEMENT_MODE);
        this.enableTouch = (modeObj != null) ? ((Long) modeObj).intValue() : 0;

        // Load new text properties with backward compatibility
        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR)) {
            normalTextColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR)).intValue();
        } else {
            // Default to old behavior: use border color
            normalTextColor = normalColor;
        }

        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR)) {
            pressedTextColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR)).intValue();
        } else {
            // Default to old behavior: use border color
            pressedTextColor = pressedColor;
        }

        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT)) {
            textSizePercent = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT)).intValue();
        } else {
            // Default based on original hardcoded logic
            textSizePercent = 63;
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

        // Get element dimensions
        int elementWidth = getElementWidth();
        int elementHeight = getElementHeight();

        // Set text size based on percentage of height
        float textSize = getPercent(elementHeight, textSizePercent);
        paintText.setTextSize(textSize);
        // Set text color based on press state using new properties
        paintText.setColor(isPressed() ? pressedTextColor : normalTextColor);
        // Border
        paintBorder.setStrokeWidth(thick);
        paintBorder.setColor(isPressed() ? pressedColor : normalColor);
        // Background color
        paintBackground.setColor(backgroundColor);

        float centerX = elementWidth / 2f;
        // Calculate the baseline Y for vertical centering
        Paint.FontMetrics fontMetrics = paintText.getFontMetrics();
        float baselineY = elementHeight / 2f - (fontMetrics.top + fontMetrics.bottom) / 2f;

        // 3. Start drawing
        // Drawing bounds
        rect.left = rect.top = (float) thick / 2;
        rect.right = getElementWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;
        // Draw background
        canvas.drawRoundRect(rect, radius, radius, paintBackground);
        // Draw border
        canvas.drawRoundRect(rect, radius, radius, paintBorder);
        // Draw text using the calculated precise coordinates
        canvas.drawText(text, centerX, baselineY, paintText);

        ElementController.Mode mode = elementController.getMode();
        if (mode == ElementController.Mode.Edit || mode == ElementController.Mode.Select) {
            // Drawing bounds
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // Border
            paintEdit.setColor(editColor);
            canvas.drawRect(rect, paintEdit);

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
        if (enableTouch == 1) {
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
        } else {
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
                if (enableTouch != 1) {
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
        contentValues.put(COLUMN_INT_ELEMENT_MODE, enableTouch);
        contentValues.put(COLUMN_INT_ELEMENT_SENSE, sense);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius);
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor);
        // Save new text properties
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, normalTextColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, pressedTextColor);
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, textSizePercent);
        elementController.updateElement(elementId, contentValues);
    }

    @Override
    protected void updatePage() {
        if (digitalMovableButtonPage != null) {
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (digitalMovableButtonPage == null) {
            digitalMovableButtonPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_digital_movable_button, null);
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

        // Find new views for text properties (assuming these IDs exist in the XML layout)
        NumberSeekbar textSizeNumberSeekbar = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_text_size);
        ElementEditText normalTextColorElementEditText = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_normal_text_color);
        ElementEditText pressedTextColorElementEditText = digitalMovableButtonPage.findViewById(R.id.page_digital_movable_button_pressed_text_color);

        textElementEditText.setTextWithNoTextChangedCallBack(text);
        textElementEditText.setOnTextChangedListener(text -> {
            setElementText(text);
            save();
        });

        valueTextView.setText(pageDeviceController.getKeyNameByValue(value));
        valueTextView.setOnClickListener(v -> {
            PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                @Override
                public void OnKeyClick(TextView key) {
                    CharSequence text = key.getText();
                    ((TextView) v).setText(text);
                    textElementEditText.setText(text);
                    setElementValue(key.getTag().toString());
                    save();
                }
            };
            pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
        });

        enableTouchSwitch.setChecked(enableTouch == 1);
        enableTouchSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            enableTouch = isChecked ? 1 : 0;
            save();
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

        // Setup for new text size seekbar
        textSizeNumberSeekbar.setProgressMin(10); // 10%
        textSizeNumberSeekbar.setProgressMax(150); // 150%
        textSizeNumberSeekbar.setValueWithNoCallBack(textSizePercent);
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementTextSizePercent(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        // Setup for all color pickers
        setupColorPickerButton(normalColorElementEditText, () -> this.normalColor, this::setElementNormalColor);
        setupColorPickerButton(pressedColorElementEditText, () -> this.pressedColor, this::setElementPressedColor);
        setupColorPickerButton(backgroundColorElementEditText, () -> this.backgroundColor, this::setElementBackgroundColor);
        setupColorPickerButton(normalTextColorElementEditText, () -> this.normalTextColor, this::setElementNormalTextColor);
        setupColorPickerButton(pressedTextColorElementEditText, () -> this.pressedTextColor, this::setElementPressedTextColor);


        copyButton.setOnClickListener(v -> {
            ContentValues contentValues = new ContentValues();
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON);
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text);
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
            contentValues.put(COLUMN_INT_ELEMENT_MODE, enableTouch);
            contentValues.put(COLUMN_INT_ELEMENT_SENSE, sense);
            contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
            contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
            contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer);
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + getElementWidth(), centralXMax), centralXMin));
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
            contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius);
            contentValues.put(COLUMN_INT_ELEMENT_THICK, thick);
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor);
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor);
            contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor);
            // Add new properties for copy
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, normalTextColor);
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, pressedTextColor);
            contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, textSizePercent);
            elementController.addElement(contentValues);
        });

        deleteButton.setOnClickListener(v -> {
            elementController.toggleInfoPage(digitalMovableButtonPage);
            elementController.deleteElement(digitalMovableButton);
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

    protected void setElementSense(int sense) {
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
        invalidate(); // Added invalidate() for immediate visual feedback
    }

    protected void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
    }

    // New setters for text properties
    protected void setElementNormalTextColor(int normalTextColor) {
        this.normalTextColor = normalTextColor;
        invalidate();
    }

    protected void setElementPressedTextColor(int pressedTextColor) {
        this.pressedTextColor = pressedTextColor;
        invalidate();
    }

    protected void setElementTextSizePercent(int textSizePercent) {
        this.textSizePercent = textSizePercent;
        invalidate();
    }

    public static ContentValues getInitialInfo() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "A");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "k29");
        contentValues.put(COLUMN_INT_ELEMENT_MODE, 0);
        contentValues.put(COLUMN_INT_ELEMENT_SENSE, 100);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 100);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 100);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 100);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 100);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, 0);
        contentValues.put(COLUMN_INT_ELEMENT_THICK, 5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0x00FFFFFF);
        // Add new properties with good defaults
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, 0xFFFFFFFF); // White
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, 0xFFCCCCCC); // Light Grey for pressed state
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 63);
        return contentValues;
    }

    private interface IntSupplier {
        int get();
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private void updateColorDisplay(ElementEditText colorDisplay, int color) {
        colorDisplay.setTextWithNoTextChangedCallBack(String.format("%08X", color));
        colorDisplay.setBackgroundColor(color);
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        colorDisplay.setTextColor(luminance > 0.5 ? Color.BLACK : Color.WHITE);
        colorDisplay.setGravity(Gravity.CENTER);
    }

    private void setupColorPickerButton(ElementEditText colorDisplay, IntSupplier initialColorFetcher, IntConsumer colorUpdater) {
        colorDisplay.setFocusable(false);
        colorDisplay.setCursorVisible(false);
        colorDisplay.setKeyListener(null);
        updateColorDisplay(colorDisplay, initialColorFetcher.get());
        colorDisplay.setOnClickListener(v -> new ColorPickerDialog(
                getContext(),
                initialColorFetcher.get(),
                true,
                newColor -> {
                    colorUpdater.accept(newColor);
                    save();
                    updateColorDisplay(colorDisplay, newColor);
                }
        ).show());
    }
}