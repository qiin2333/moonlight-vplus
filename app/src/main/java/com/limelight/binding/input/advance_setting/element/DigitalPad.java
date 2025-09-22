//十字键
package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
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

public class DigitalPad extends Element {


    public final static int DIGITAL_PAD_DIRECTION_NO_DIRECTION = 0;
    int direction = DIGITAL_PAD_DIRECTION_NO_DIRECTION;
    public final static int DIGITAL_PAD_DIRECTION_LEFT = 1;
    public final static int DIGITAL_PAD_DIRECTION_UP = 2;
    public final static int DIGITAL_PAD_DIRECTION_RIGHT = 4;
    public final static int DIGITAL_PAD_DIRECTION_DOWN = 8;
    private DigitalPadListener listener;
    private static final int DPAD_MARGIN = 5;

    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private PageDeviceController pageDeviceController;
    private DigitalPad digitalPad;

    private ElementController.SendEventHandler upValueSenderHandler;
    private ElementController.SendEventHandler downValueSenderHandler;
    private ElementController.SendEventHandler leftValueSenderHandler;
    private ElementController.SendEventHandler rightValueSenderHandler;
    private String upValue;
    private String downValue;
    private String leftValue;
    private String rightValue;
    private int thick;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;

    private SuperPageLayout digitalPadPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;

    //加上这个防止一些无用的按键多次触发，发送大量数据，导致卡顿
    private int lastDirection = 0;
    private final Paint paintBorder = new Paint();
    private final Paint paintBackground = new Paint();
    private final Path pathBackground = new Path();
    private final Paint paintText = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();

    public DigitalPad(Map<String, Object> attributesMap,
                      ElementController controller,
                      PageDeviceController pageDeviceController, Context context) {
        super(attributesMap, controller, context);
        this.pageDeviceController = pageDeviceController;
        this.digitalPad = this;

        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game) context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        super.centralXMax = displayMetrics.widthPixels;
        super.centralXMin = 0;
        super.centralYMax = displayMetrics.heightPixels;
        super.centralYMin = 0;
        super.widthMax = displayMetrics.widthPixels / 2;
        super.widthMin = 150;
        super.heightMax = displayMetrics.heightPixels / 2;
        super.heightMin = 150;

        paintText.setTextAlign(Paint.Align.CENTER);
        paintBorder.setStyle(Paint.Style.STROKE);
        paintBackground.setStyle(Paint.Style.FILL);
        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));

        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        upValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_UP_VALUE);
        downValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_DOWN_VALUE);
        leftValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_LEFT_VALUE);
        rightValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_RIGHT_VALUE);

        upValueSenderHandler = controller.getSendEventHandler(upValue);
        downValueSenderHandler = controller.getSendEventHandler(downValue);
        leftValueSenderHandler = controller.getSendEventHandler(leftValue);
        rightValueSenderHandler = controller.getSendEventHandler(rightValue);
        listener = new DigitalPad.DigitalPadListener() {
            @Override
            public void onDirectionChange(int direction) {
                int directionChange = lastDirection ^ direction;
                if ((directionChange & DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    leftValueSenderHandler.sendEvent((direction & DIGITAL_PAD_DIRECTION_LEFT) != 0);
                }
                if ((directionChange & DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    rightValueSenderHandler.sendEvent((direction & DIGITAL_PAD_DIRECTION_RIGHT) != 0);
                }
                if ((directionChange & DIGITAL_PAD_DIRECTION_UP) != 0) {
                    upValueSenderHandler.sendEvent((direction & DIGITAL_PAD_DIRECTION_UP) != 0);
                }
                if ((directionChange & DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    downValueSenderHandler.sendEvent((direction & DIGITAL_PAD_DIRECTION_DOWN) != 0);
                }
                lastDirection = direction;
            }
        };

    }


    @Override
    protected void onElementDraw(Canvas canvas) {


        paintBorder.setStrokeWidth(thick);
        int correctedBorderPosition = thick + DPAD_MARGIN;

        paintBackground.setStyle(Paint.Style.FILL);
        paintBackground.setStrokeWidth(10);
        paintBackground.setColor(backgroundColor);
        pathBackground.reset();
        pathBackground.moveTo(getPercent(getWidth(), 33), correctedBorderPosition);
        pathBackground.lineTo(getPercent(getWidth(), 66), correctedBorderPosition);
        pathBackground.lineTo(getWidth() - correctedBorderPosition, getPercent(getHeight(), 33));
        pathBackground.lineTo(getWidth() - correctedBorderPosition, getPercent(getHeight(), 66));
        pathBackground.lineTo(getPercent(getWidth(), 66), getHeight() - correctedBorderPosition);
        pathBackground.lineTo(getPercent(getWidth(), 33), getHeight() - correctedBorderPosition);
        pathBackground.lineTo(correctedBorderPosition, getPercent(getHeight(), 66));
        pathBackground.lineTo(correctedBorderPosition, getPercent(getHeight(), 33));
        pathBackground.lineTo(getPercent(getWidth(), 33), correctedBorderPosition);
        canvas.drawPath(pathBackground, paintBackground);


        if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
            // draw no direction rect
            paintBorder.setStyle(Paint.Style.STROKE);
            paintBorder.setColor(normalColor);
            canvas.drawRect(
                    getPercent(getWidth(), 36), getPercent(getHeight(), 36),
                    getPercent(getWidth(), 63), getPercent(getHeight(), 63),
                    paintBorder
            );
        }

        // draw left rect
        paintBorder.setColor(
                (direction & DIGITAL_PAD_DIRECTION_LEFT) > 0 ? pressedColor : normalColor);
        paintBorder.setStyle(Paint.Style.STROKE);
        canvas.drawRect(
                correctedBorderPosition, getPercent(getHeight(), 33),
                getPercent(getWidth(), 33), getPercent(getHeight(), 66),
                paintBorder
        );


        // draw up rect
        paintBorder.setColor(
                (direction & DIGITAL_PAD_DIRECTION_UP) > 0 ? pressedColor : normalColor);
        paintBorder.setStyle(Paint.Style.STROKE);
        canvas.drawRect(
                getPercent(getWidth(), 33), correctedBorderPosition,
                getPercent(getWidth(), 66), getPercent(getHeight(), 33),
                paintBorder
        );

        // draw right rect
        paintBorder.setColor(
                (direction & DIGITAL_PAD_DIRECTION_RIGHT) > 0 ? pressedColor : normalColor);
        paintBorder.setStyle(Paint.Style.STROKE);
        canvas.drawRect(
                getPercent(getWidth(), 66), getPercent(getHeight(), 33),
                getWidth() - correctedBorderPosition, getPercent(getHeight(), 66),
                paintBorder
        );

        // draw down rect
        paintBorder.setColor(
                (direction & DIGITAL_PAD_DIRECTION_DOWN) > 0 ? pressedColor : normalColor);
        paintBorder.setStyle(Paint.Style.STROKE);
        canvas.drawRect(
                getPercent(getWidth(), 33), getPercent(getHeight(), 66),
                getPercent(getWidth(), 66), getHeight() - correctedBorderPosition,
                paintBorder
        );

        // draw left up line
        paintBorder.setColor((
                        (direction & DIGITAL_PAD_DIRECTION_LEFT) > 0 &&
                                (direction & DIGITAL_PAD_DIRECTION_UP) > 0
                ) ? pressedColor : normalColor
        );
        paintBorder.setStyle(Paint.Style.STROKE);
        canvas.drawLine(
                correctedBorderPosition, getPercent(getHeight(), 33),
                getPercent(getWidth(), 33), correctedBorderPosition,
                paintBorder
        );

        // draw up right line
        paintBorder.setColor((
                        (direction & DIGITAL_PAD_DIRECTION_UP) > 0 &&
                                (direction & DIGITAL_PAD_DIRECTION_RIGHT) > 0
                ) ? pressedColor : normalColor
        );
        paintBorder.setStyle(Paint.Style.STROKE);
        canvas.drawLine(
                getPercent(getWidth(), 66), correctedBorderPosition,
                getWidth() - correctedBorderPosition, getPercent(getHeight(), 33),
                paintBorder
        );

        // draw right down line
        paintBorder.setColor((
                        (direction & DIGITAL_PAD_DIRECTION_RIGHT) > 0 &&
                                (direction & DIGITAL_PAD_DIRECTION_DOWN) > 0
                ) ? pressedColor : normalColor
        );
        paintBorder.setStyle(Paint.Style.STROKE);
        canvas.drawLine(
                getWidth() - paintBorder.getStrokeWidth(), getPercent(getHeight(), 66),
                getPercent(getWidth(), 66), getHeight() - correctedBorderPosition,
                paintBorder
        );

        // draw down left line
        paintBorder.setColor((
                        (direction & DIGITAL_PAD_DIRECTION_DOWN) > 0 &&
                                (direction & DIGITAL_PAD_DIRECTION_LEFT) > 0
                ) ? pressedColor : normalColor
        );
        paintBorder.setStyle(Paint.Style.STROKE);
        canvas.drawLine(
                getPercent(getWidth(), 33), getHeight() - correctedBorderPosition,
                correctedBorderPosition, getPercent(getHeight(), 66),
                paintBorder
        );

        ElementController.Mode mode = elementController.getMode();
        if (mode == ElementController.Mode.Edit || mode == ElementController.Mode.Select) {
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(editColor);
            canvas.drawRect(rect, paintEdit);

        }
    }

    private void newDirectionCallback(int direction) {

        // notify listeners
        listener.onDirectionChange(direction);
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // get masked (not specific to a pointer) action
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                elementController.buttonVibrator();
            case MotionEvent.ACTION_MOVE: {
                direction = 0;

                if (event.getX() < getPercent(getWidth(), 33)) {
                    direction |= DIGITAL_PAD_DIRECTION_LEFT;
                }
                if (event.getX() > getPercent(getWidth(), 66)) {
                    direction |= DIGITAL_PAD_DIRECTION_RIGHT;
                }
                if (event.getY() > getPercent(getHeight(), 66)) {
                    direction |= DIGITAL_PAD_DIRECTION_DOWN;
                }
                if (event.getY() < getPercent(getHeight(), 33)) {
                    direction |= DIGITAL_PAD_DIRECTION_UP;
                }
                newDirectionCallback(direction);
                invalidate();

                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                direction = 0;
                newDirectionCallback(direction);
                invalidate();

                return true;
            }
            default: {
            }
        }

        return true;
    }

    public interface DigitalPadListener {
        void onDirectionChange(int direction);
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (digitalPadPage == null) {
            digitalPadPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_digital_pad, null);
            centralXNumberSeekbar = digitalPadPage.findViewById(R.id.page_digital_pad_central_x);
            centralYNumberSeekbar = digitalPadPage.findViewById(R.id.page_digital_pad_central_y);
        }

        NumberSeekbar widthNumberSeekbar = digitalPadPage.findViewById(R.id.page_digital_pad_width);
        NumberSeekbar heightNumberSeekbar = digitalPadPage.findViewById(R.id.page_digital_pad_height);
        TextView upValueTextView = digitalPadPage.findViewById(R.id.page_digital_pad_up_value);
        TextView downValueTextView = digitalPadPage.findViewById(R.id.page_digital_pad_down_value);
        TextView leftValueTextView = digitalPadPage.findViewById(R.id.page_digital_pad_left_value);
        TextView rightValueTextView = digitalPadPage.findViewById(R.id.page_digital_pad_right_value);
        NumberSeekbar thickNumberSeekbar = digitalPadPage.findViewById(R.id.page_digital_pad_thick);
        NumberSeekbar layerNumberSeekbar = digitalPadPage.findViewById(R.id.page_digital_pad_layer);
        ElementEditText normalColorElementEditText = digitalPadPage.findViewById(R.id.page_digital_pad_normal_color);
        ElementEditText pressedColorElementEditText = digitalPadPage.findViewById(R.id.page_digital_pad_pressed_color);
        ElementEditText backgroundColorElementEditText = digitalPadPage.findViewById(R.id.page_digital_pad_background_color);
        Button copyButton = digitalPadPage.findViewById(R.id.page_digital_pad_copy);
        Button deleteButton = digitalPadPage.findViewById(R.id.page_digital_pad_delete);


        upValueTextView.setText(pageDeviceController.getKeyNameByValue(upValue));
        upValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementUpValue(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
            }
        });

        downValueTextView.setText(pageDeviceController.getKeyNameByValue(downValue));
        downValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        setElementDownValue(key.getTag().toString());
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
            }
        });

        leftValueTextView.setText(pageDeviceController.getKeyNameByValue(leftValue));
        leftValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        setElementLeftValue(key.getTag().toString());
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
            }
        });

        rightValueTextView.setText(pageDeviceController.getKeyNameByValue(rightValue));
        rightValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        setElementRightValue(key.getTag().toString());
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
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
                contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_PAD);
                contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, upValue);
                contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, downValue);
                contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, leftValue);
                contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, rightValue);
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
                contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer);
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + getElementWidth(), centralXMax), centralXMin));
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
                contentValues.put(COLUMN_INT_ELEMENT_THICK, thick);
                contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor);
                contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor);
                contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor);
                elementController.addElement(contentValues);
            }
        });

        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                elementController.toggleInfoPage(digitalPadPage);
                elementController.deleteElement(digitalPad);
            }
        });

        return digitalPadPage;
    }

    @Override
    public void save() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, upValue);
        contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, downValue);
        contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, leftValue);
        contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, rightValue);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor);
        elementController.updateElement(elementId, contentValues);

    }

    @Override
    protected void updatePage() {
        if (digitalPadPage != null) {
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }

    }

    public void setElementUpValue(String upValue) {
        this.upValue = upValue;
        upValueSenderHandler = elementController.getSendEventHandler(upValue);
    }

    public void setElementDownValue(String downValue) {
        this.downValue = downValue;
        downValueSenderHandler = elementController.getSendEventHandler(downValue);
    }

    public void setElementLeftValue(String leftValue) {
        this.leftValue = leftValue;
        leftValueSenderHandler = elementController.getSendEventHandler(leftValue);
    }

    public void setElementRightValue(String rightValue) {
        this.rightValue = rightValue;
        rightValueSenderHandler = elementController.getSendEventHandler(rightValue);
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

    public static ContentValues getInitialInfo() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_PAD);
        contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, "k51");
        contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, "k47");
        contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, "k29");
        contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, "k32");
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 300);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 300);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 100);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 100);
        contentValues.put(COLUMN_INT_ELEMENT_THICK, 5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0x00FFFFFF);
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
