//组合键
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
import com.limelight.utils.ColorPickerDialog;

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
    // New member variables
    private int normalTextColor;
    private int pressedTextColor;
    private int textSizePercent;

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


    public DigitalCombineButton(Map<String, Object> attributesMap,
                                ElementController controller,
                                PageDeviceController pageDeviceController, Context context) {
        super(attributesMap, controller, context);
        this.pageDeviceController = pageDeviceController;
        this.digitalCombineButton = this;

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
            textSizePercent = 25;
        }

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
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius);
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick);
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
        if (digitalCombineButtonPage != null) {
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }

    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (digitalCombineButtonPage == null) {
            digitalCombineButtonPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_digital_combine_button, null);
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

        // Find new views for text properties (assuming these IDs exist in the XML layout)
        NumberSeekbar textSizeNumberSeekbar = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_text_size);
        ElementEditText normalTextColorElementEditText = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_normal_text_color);
        ElementEditText pressedTextColorElementEditText = digitalCombineButtonPage.findViewById(R.id.page_digital_combine_button_pressed_text_color);

        textElementEditText.setTextWithNoTextChangedCallBack(text);
        textElementEditText.setOnTextChangedListener(text -> {
            setElementText(text);
            save();
        });

        setupValueTextView(value1TextView, value1, this::setElementValue1);
        setupValueTextView(value2TextView, value2, this::setElementValue2);
        setupValueTextView(value3TextView, value3, this::setElementValue3);
        setupValueTextView(value4TextView, value4, this::setElementValue4);
        setupValueTextView(value5TextView, value5, this::setElementValue5);

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

        // Refactored setup for all color pickers
        setupColorPickerButton(normalColorElementEditText, () -> this.normalColor, this::setElementNormalColor);
        setupColorPickerButton(pressedColorElementEditText, () -> this.pressedColor, this::setElementPressedColor);
        setupColorPickerButton(backgroundColorElementEditText, () -> this.backgroundColor, this::setElementBackgroundColor);
        setupColorPickerButton(normalTextColorElementEditText, () -> this.normalTextColor, this::setElementNormalTextColor);
        setupColorPickerButton(pressedTextColorElementEditText, () -> this.pressedTextColor, this::setElementPressedTextColor);


        copyButton.setOnClickListener(v -> {
            ContentValues contentValues = new ContentValues();
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON);
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text);
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_1, value1);
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_2, value2);
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_3, value3);
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_4, value4);
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_5, value5);
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
            elementController.toggleInfoPage(digitalCombineButtonPage);
            elementController.deleteElement(digitalCombineButton);
        });

        return digitalCombineButtonPage;
    }

    private interface StringConsumer {
        void accept(String value);
    }

    private void setupValueTextView(TextView textView, String initialValue, StringConsumer valueSetter) {
        textView.setText(pageDeviceController.getKeyNameByValue(initialValue));
        textView.setOnClickListener(v -> {
            PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                @Override
                public void OnKeyClick(TextView key) {
                    ((TextView) v).setText(key.getText());
                    valueSetter.accept(key.getTag().toString());
                    save();
                }
            };
            pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
        });
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
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "组合键");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_1, "k29");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_2, "null");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_3, "null");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_4, "null");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_5, "null");
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
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 25);
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
     * @param colorDisplay        用于作为按钮的 ElementEditText 视图。
     * @param initialColorFetcher 一个用于获取当前颜色值的 Lambda 表达式。
     * @param colorUpdater        一个用于设置新颜色值的 Lambda 表达式。
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