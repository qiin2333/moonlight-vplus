package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.InputFilter;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WheelPad extends Element {

    private final PageDeviceController pageDeviceController;
    private final WheelPad wheelPad;

    private int diameter;
    private int segmentCount;
    private int innerRadiusPercent;
    private boolean isPopupMode;
    private String centerText;
    private List<String> segmentValues;
    private List<String> segmentNames;
    private List<ElementController.SendEventHandler> segmentSendHandlers;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;
    private int thick;

    private final Paint paintBorder = new Paint();
    private final Paint paintSegmentFill = new Paint();
    private final Paint paintSegmentFillPressed = new Paint();
    private final Paint paintBackground = new Paint();
    private final Paint paintText = new Paint();
    private final Paint paintEdit = new Paint();
    private final Paint paintCenterText = new Paint();
    private final RectF rect = new RectF();
    private final Path textPath = new Path();

    private int activeIndex = -1;
    private int lastActiveIndex = -1;
    private boolean isWheelActive = false;

    private SuperPageLayout wheelPadPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;
    private LinearLayout valuesContainer;

    public WheelPad(Map<String, Object> attributesMap,
                    ElementController controller,
                    PageDeviceController pageDeviceController, Context context) {
        super(attributesMap, controller, context);
        this.pageDeviceController = pageDeviceController;
        this.wheelPad = this;

        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game) context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        super.centralXMax = displayMetrics.widthPixels;
        super.centralXMin = 0;
        super.centralYMax = displayMetrics.heightPixels;
        super.centralYMin = 0;
        super.widthMax = displayMetrics.widthPixels;
        super.widthMin = 150;
        super.heightMax = displayMetrics.heightPixels;
        super.heightMin = 150;

        paintBorder.setStyle(Paint.Style.STROKE);
        paintBorder.setAntiAlias(true);
        paintSegmentFill.setStyle(Paint.Style.FILL);
        paintSegmentFill.setAntiAlias(true);
        paintSegmentFillPressed.setStyle(Paint.Style.FILL);
        paintSegmentFillPressed.setAntiAlias(true);
        paintBackground.setStyle(Paint.Style.FILL);
        paintBackground.setAntiAlias(true);
        paintText.setColor(0xFFFFFFFF);
        paintText.setTextSize(30);
        paintText.setTextAlign(Paint.Align.CENTER);
        paintText.setAntiAlias(true);
        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));
        paintCenterText.setColor(0xFFFFFFFF);
        paintCenterText.setTextSize(60);
        paintCenterText.setTextAlign(Paint.Align.CENTER);
        paintCenterText.setAntiAlias(true);
        paintCenterText.setFakeBoldText(true);

        this.diameter = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_WIDTH)).intValue();

        Object textObj = attributesMap.get(COLUMN_STRING_ELEMENT_TEXT);
        this.centerText = (textObj != null) ? (String) textObj : "";
        this.isPopupMode = !this.centerText.isEmpty();

        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        segmentCount = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_MODE)).intValue();
        innerRadiusPercent = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_SENSE)).intValue();

        String valuesString = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);
        segmentValues = new ArrayList<>();
        segmentNames = new ArrayList<>();
        String[] segments = valuesString.split(",");
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            String[] parts = segment.split("\\|", 2);
            segmentValues.add(parts[0]);
            if (parts.length > 1) {
                segmentNames.add(parts[1]);
            } else {
                segmentNames.add("");
            }
        }

        segmentSendHandlers = new ArrayList<>();
        for (String value : segmentValues) {
            segmentSendHandlers.add(controller.getSendEventHandler(value));
        }
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        ElementController.Mode currentMode = elementController.getMode();

        float centerX = getWidth() / 2.0f;
        float centerY = getHeight() / 2.0f;

        float fillOuterRadius = (getWidth() / 2.0f) - thick;
        if (fillOuterRadius < 0) fillOuterRadius = 0;
        float fillInnerRadius = fillOuterRadius * (innerRadiusPercent / 100.0f);
        float borderOuterDrawRadius = (getWidth() / 2.0f) - (thick / 2.0f);
        if (borderOuterDrawRadius < 0) borderOuterDrawRadius = 0;
        float borderInnerDrawRadius = fillInnerRadius + (thick / 2.0f);

        paintBorder.setColor(normalColor);
        paintBorder.setStrokeWidth(thick);
        paintBackground.setColor(0xFF000000);

        boolean shouldHideSegments = isPopupMode && !isWheelActive && currentMode == ElementController.Mode.Normal;

        if (shouldHideSegments) {
            paintSegmentFill.setColor(0x80000000);
            canvas.drawCircle(centerX, centerY, fillInnerRadius, paintSegmentFill);

            rect.set(centerX - borderInnerDrawRadius, centerY - borderInnerDrawRadius, centerX + borderInnerDrawRadius, centerY + borderInnerDrawRadius);
            canvas.drawOval(rect, paintBorder);

            float textY = centerY - ((paintCenterText.descent() + paintCenterText.ascent()) / 2);
            canvas.drawText(centerText, centerX, textY, paintCenterText);
        } else {
            paintSegmentFill.setColor(backgroundColor);
            paintSegmentFillPressed.setColor(pressedColor);
            float sweepAngle = 360.0f / segmentCount;

            rect.set(centerX - fillOuterRadius, centerY - fillOuterRadius, centerX + fillOuterRadius, centerY + fillOuterRadius);
            for (int i = 0; i < segmentCount; i++) {
                float startAngle = (i * sweepAngle) - (sweepAngle / 2) - 90;
                Paint currentFillPaint = (i == activeIndex) ? paintSegmentFillPressed : paintSegmentFill;
                canvas.drawArc(rect, startAngle, sweepAngle, true, currentFillPaint);
            }

            canvas.drawCircle(centerX, centerY, fillInnerRadius, paintBackground);

            float textRadius = fillInnerRadius + (fillOuterRadius - fillInnerRadius) / 2.0f;
            for (int i = 0; i < segmentCount; i++) {
                float startAngle = (i * sweepAngle) - (sweepAngle / 2) - 90;
                float textAngle = (startAngle + sweepAngle / 2.0f);
                textPath.reset();
                RectF textRect = new RectF(centerX - textRadius, centerY - textRadius, centerX + textRadius, centerY + textRadius);
                textPath.addArc(textRect, textAngle - 45, 90);
                if (i < segmentValues.size()) {
                    String displayName;
                    if (i < segmentNames.size() && segmentNames.get(i) != null && !segmentNames.get(i).isEmpty()) {
                        displayName = segmentNames.get(i);
                    } else {
                        displayName = pageDeviceController.getKeyNameByValue(segmentValues.get(i));
                    }
                    canvas.drawTextOnPath(displayName, textPath, 0, 10, paintText);
                }
            }

            rect.set(centerX - borderOuterDrawRadius, centerY - borderOuterDrawRadius, centerX + borderOuterDrawRadius, centerY + borderOuterDrawRadius);
            canvas.drawOval(rect, paintBorder);
            rect.set(centerX - borderInnerDrawRadius, centerY - borderInnerDrawRadius, centerX + borderInnerDrawRadius, centerY + borderInnerDrawRadius);
            canvas.drawOval(rect, paintBorder);

            for (int i = 0; i < segmentCount; i++) {
                float angle = (i * sweepAngle) - (sweepAngle / 2) - 90;
                double angleRad = Math.toRadians(angle);
                float startX = centerX + (float) (fillInnerRadius * Math.cos(angleRad));
                float startY = centerY + (float) (fillInnerRadius * Math.sin(angleRad));
                float endX = centerX + (float) (fillOuterRadius * Math.cos(angleRad));
                float endY = centerY + (float) (fillOuterRadius * Math.sin(angleRad));
                canvas.drawLine(startX, startY, endX, endY, paintBorder);
            }

            if (isPopupMode && activeIndex != -1 && currentMode == ElementController.Mode.Normal) {
                if (activeIndex < segmentValues.size()) {
                    String displayName;
                    if (activeIndex < segmentNames.size() && segmentNames.get(activeIndex) != null && !segmentNames.get(activeIndex).isEmpty()) {
                        displayName = segmentNames.get(activeIndex);
                    } else {
                        displayName = pageDeviceController.getKeyNameByValue(segmentValues.get(activeIndex));
                    }
                    float textY = centerY - ((paintCenterText.descent() + paintCenterText.ascent()) / 2);
                    canvas.drawText(displayName, centerX, textY, paintCenterText);
                }
            }
        }

        if (currentMode == ElementController.Mode.Edit || currentMode == ElementController.Mode.Select) {
            rect.left = rect.top = 2; rect.right = getWidth() - 2; rect.bottom = getHeight() - 2;
            paintEdit.setColor(editColor);
            canvas.drawRect(rect, paintEdit);
        }
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // 在编辑或选择模式下，控件应该总是响应触摸以便移动或选中
        if (elementController.getMode() != ElementController.Mode.Normal) {
            return true;
        }

        // 如果不是弹出模式（即直接模式），则行为保持不变，总是处理触摸
        if (!isPopupMode) {
            handleDirectModeTouchEvent(event);
            return true;
        }

        // 1. 如果轮盘已经被激活 (isWheelActive == true)，
        //    那么必须继续处理所有的后续事件（MOVE, UP），所以直接调用处理方法并返回 true。
        if (isWheelActive) {
            handlePopupModeTouchEvent(event);
            return true;
        }

        // 2. 如果轮盘还未被激活，我们只关心初始的 ACTION_DOWN 事件。
        //    并且，这个 ACTION_DOWN 事件必须发生在中心圆内部。
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // 计算触摸点到中心的距离
            float x = event.getX();
            float y = event.getY();
            float centerX = getWidth() / 2.0f;
            float centerY = getHeight() / 2.0f;
            float outerRadius = (getWidth() / 2.0f) - thick;
            if (outerRadius < 0) outerRadius = 0;
            float innerRadius = outerRadius * (innerRadiusPercent / 100.0f);
            float dx = x - centerX;
            float dy = y - centerY;
            double distance = Math.sqrt(dx * dx + dy * dy);

            // 如果触摸点在中心激活区域内
            if (distance <= innerRadius) {
                // 调用处理方法来激活轮盘
                handlePopupModeTouchEvent(event);
                // 返回 true，表示我们开始处理这个触摸序列（后续的MOVE和UP事件会发给我们）
                return true;
            } else {
                // 如果触摸点在中心圆之外，返回 false，让触摸事件可以“穿透”过去
                return false;
            }
        }

        // 3. 对于轮盘未激活时的其他事件（如用户在别处按下手指然后滑入本视图），我们不处理。
        return false;
    }

    private void handleDirectModeTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (action == MotionEvent.ACTION_DOWN) {
                elementController.buttonVibrator();
            }
            float x = event.getX();
            float y = event.getY();
            float centerX = getWidth() / 2.0f;
            float centerY = getHeight() / 2.0f;
            float outerRadius = (getWidth() / 2.0f) - thick;
            if (outerRadius < 0) outerRadius = 0;
            float innerRadius = outerRadius * (innerRadiusPercent / 100.0f);
            float dx = x - centerX;
            float dy = y - centerY;
            double distance = Math.sqrt(dx * dx + dy * dy);

            if (distance > innerRadius && distance < outerRadius) {
                double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90;
                if (angle < 0) angle += 360;
                float sweepAngle = 360.0f / segmentCount;
                activeIndex = (int) ((angle + sweepAngle / 2) % 360 / sweepAngle);
            } else {
                activeIndex = -1;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeIndex = -1;
        }
        updateSendingState();
        invalidate();
    }

    private void handlePopupModeTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float centerX = getWidth() / 2.0f;
        float centerY = getHeight() / 2.0f;
        float outerRadius = (getWidth() / 2.0f) - thick;
        if (outerRadius < 0) outerRadius = 0;
        float innerRadius = outerRadius * (innerRadiusPercent / 100.0f);
        float dx = x - centerX;
        float dy = y - centerY;
        double distance = Math.sqrt(dx * dx + dy * dy);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (distance <= innerRadius) {
                    isWheelActive = true;
                    elementController.buttonVibrator();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (isWheelActive) {
                    if (distance > innerRadius) {
                        double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90;
                        if (angle < 0) angle += 360;
                        float sweepAngle = 360.0f / segmentCount;
                        activeIndex = (int) ((angle + sweepAngle / 2) % 360 / sweepAngle);
                    } else {
                        activeIndex = -1;
                    }
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (isWheelActive) {
                    if (activeIndex != -1 && activeIndex < segmentSendHandlers.size()) {
                        ElementController.SendEventHandler handler = segmentSendHandlers.get(activeIndex);
                        handler.sendEvent(true);
                        handler.sendEvent(false);
                    }
                    isWheelActive = false;
                    activeIndex = -1;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (isWheelActive) {
                    isWheelActive = false;
                    activeIndex = -1;
                    invalidate();
                }
                break;
        }
    }

    private void updateSendingState() {
        if (activeIndex != lastActiveIndex) {
            if (lastActiveIndex != -1 && lastActiveIndex < segmentSendHandlers.size()) {
                segmentSendHandlers.get(lastActiveIndex).sendEvent(false);
            }
            if (activeIndex != -1 && activeIndex < segmentSendHandlers.size()) {
                segmentSendHandlers.get(activeIndex).sendEvent(true);
            }
            lastActiveIndex = activeIndex;
        }
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (wheelPadPage == null) {
            wheelPadPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_wheel_pad, null);
            centralXNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_central_x);
            centralYNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_central_y);
            valuesContainer = wheelPadPage.findViewById(R.id.page_wheel_pad_values_container);
        }

        ElementEditText centerTextInput = wheelPadPage.findViewById(R.id.page_wheel_pad_center_text);
        NumberSeekbar sizeNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_size);
        NumberSeekbar thickNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_thick);
        NumberSeekbar layerNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_layer);
        ElementEditText normalColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_normal_color);
        ElementEditText pressedColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_pressed_color);
        ElementEditText backgroundColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_background_color);
        Button copyButton = wheelPadPage.findViewById(R.id.page_wheel_pad_copy);
        Button deleteButton = wheelPadPage.findViewById(R.id.page_wheel_pad_delete);
        NumberSeekbar segmentCountNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_segment_count);
        NumberSeekbar innerRadiusNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_inner_radius);

        setupCommonControls(centerTextInput, sizeNumberSeekbar, thickNumberSeekbar, layerNumberSeekbar,
                normalColorElementEditText, pressedColorElementEditText, backgroundColorElementEditText,
                copyButton, deleteButton);

        segmentCountNumberSeekbar.setValueWithNoCallBack(segmentCount);
        segmentCountNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setSegmentCount(progress);
                updateValuesContainerUI();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });
        innerRadiusNumberSeekbar.setValueWithNoCallBack(innerRadiusPercent);
        innerRadiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setInnerRadiusPercent(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });
        updateValuesContainerUI();
        return wheelPadPage;
    }

    @Override
    public void save() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, this.diameter);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, this.diameter);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, this.centerText);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor);
        contentValues.put(COLUMN_INT_ELEMENT_MODE, segmentCount);
        contentValues.put(COLUMN_INT_ELEMENT_SENSE, innerRadiusPercent);

        List<String> combinedSegments = new ArrayList<>();
        for (int i = 0; i < segmentValues.size(); i++) {
            String value = segmentValues.get(i);
            String name = (i < segmentNames.size()) ? segmentNames.get(i) : "";
            if (name != null && !name.isEmpty()) {
                combinedSegments.add(value + "|" + name);
            } else {
                combinedSegments.add(value);
            }
        }
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, String.join(",", combinedSegments));
        elementController.updateElement(elementId, contentValues);
    }

    public static ContentValues getInitialInfo() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_WHEEL_PAD);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "k51,k32,k47,k29,k45,k33,k46,k31");
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 400);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 400);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "");
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 250);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 250);
        contentValues.put(COLUMN_INT_ELEMENT_THICK, 5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0xAA444444);
        contentValues.put(COLUMN_INT_ELEMENT_MODE, 8);
        contentValues.put(COLUMN_INT_ELEMENT_SENSE, 30);
        return contentValues;
    }

    private void setupCommonControls(ElementEditText centerTextInput, NumberSeekbar size, NumberSeekbar thick, NumberSeekbar layer,
                                     ElementEditText normalColor, ElementEditText pressedColor, ElementEditText backgroundColor,
                                     Button copy, Button delete) {

        centerTextInput.setTextWithNoTextChangedCallBack(this.centerText);
        centerTextInput.setOnTextChangedListener(text -> {
            setCenterText(text);
            save();
        });

        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                setElementCentralX(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save();
            }
        });
        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                setElementCentralY(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save();
            }
        });
        size.setProgressMax(Math.min(widthMax, heightMax));
        size.setProgressMin(widthMin);
        size.setValueWithNoCallBack(this.diameter);
        size.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                setDiameter(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save();
            }
        });
        thick.setValueWithNoCallBack(this.thick);
        thick.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                setElementThick(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save();
            }
        });
        layer.setValueWithNoCallBack(this.layer);
        layer.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                setElementLayer(s.getProgress());
                save();
            }
        });
        InputFilter[] filters = new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()};
        normalColor.setTextWithNoTextChangedCallBack(String.format("%08X", this.normalColor));
        normalColor.setFilters(filters);
        normalColor.setOnTextChangedListener(text -> {
            if (text.matches("^[A-F0-9]{8}$")) {
                setElementNormalColor((int) Long.parseLong(text, 16));
                save();
            }
        });
        pressedColor.setTextWithNoTextChangedCallBack(String.format("%08X", this.pressedColor));
        pressedColor.setFilters(filters);
        pressedColor.setOnTextChangedListener(text -> {
            if (text.matches("^[A-F0-9]{8}$")) {
                setElementPressedColor((int) Long.parseLong(text, 16));
                save();
            }
        });
        backgroundColor.setTextWithNoTextChangedCallBack(String.format("%08X", this.backgroundColor));
        backgroundColor.setFilters(filters);
        backgroundColor.setOnTextChangedListener(text -> {
            if (text.matches("^[A-F0-9]{8}$")) {
                setElementBackgroundColor((int) Long.parseLong(text, 16));
                save();
            }
        });
        copy.setOnClickListener(v -> {
            ContentValues cv = new ContentValues();
            cv.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_WHEEL_PAD);
            cv.put(COLUMN_INT_ELEMENT_WIDTH, this.diameter);
            cv.put(COLUMN_INT_ELEMENT_HEIGHT, this.diameter);
            cv.put(COLUMN_STRING_ELEMENT_TEXT, this.centerText);
            cv.put(COLUMN_INT_ELEMENT_LAYER, this.layer);
            cv.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + getElementWidth() / 2, centralXMax), centralXMin));
            cv.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
            cv.put(COLUMN_INT_ELEMENT_THICK, this.thick);
            cv.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, this.normalColor);
            cv.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, this.pressedColor);
            cv.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, this.backgroundColor);
            cv.put(COLUMN_INT_ELEMENT_MODE, this.segmentCount);
            cv.put(COLUMN_INT_ELEMENT_SENSE, this.innerRadiusPercent);

            List<String> combinedSegments = new ArrayList<>();
            for (int i = 0; i < segmentValues.size(); i++) {
                String value = segmentValues.get(i);
                String name = (i < segmentNames.size()) ? segmentNames.get(i) : "";
                if (name != null && !name.isEmpty()) {
                    combinedSegments.add(value + "|" + name);
                } else {
                    combinedSegments.add(value);
                }
            }
            cv.put(COLUMN_STRING_ELEMENT_VALUE, String.join(",", combinedSegments));
            elementController.addElement(cv);
        });
        delete.setOnClickListener(v -> {
            elementController.toggleInfoPage(wheelPadPage);
            elementController.deleteElement(wheelPad);
        });
    }

    protected void setDiameter(int newDiameter) {
        this.diameter = newDiameter;
        setElementWidth(newDiameter);
        setElementHeight(newDiameter);
        invalidate();
    }

    protected void setSegmentCount(int count) {
        if (count < 2) count = 2;
        this.segmentCount = count;
        while (segmentValues.size() < count) {
            segmentValues.add("null");
            segmentNames.add("");
        }
        while (segmentValues.size() > count) {
            segmentValues.remove(segmentValues.size() - 1);
            segmentNames.remove(segmentNames.size() - 1);
        }
        segmentSendHandlers.clear();
        for (String value : segmentValues) {
            segmentSendHandlers.add(elementController.getSendEventHandler(value));
        }
        invalidate();
    }

    protected void setSegmentValue(int index, String value) {
        if (index >= 0 && index < segmentValues.size()) {
            segmentValues.set(index, value);
            segmentSendHandlers.set(index, elementController.getSendEventHandler(value));
            invalidate();
        }
    }

    protected void setInnerRadiusPercent(int percent) {
        this.innerRadiusPercent = percent;
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

    protected void setCenterText(String text) {
        this.centerText = text;
        this.isPopupMode = !text.isEmpty();
        invalidate();
    }

    @Override
    protected void updatePage() {
        if (wheelPadPage != null) {
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }
    }

    private void updateValuesContainerUI() {
        valuesContainer.removeAllViews();
        for (int i = 0; i < segmentCount; i++) {
            if (i >= segmentValues.size()) continue;
            View valueView = LayoutInflater.from(getContext()).inflate(R.layout.item_key_value, valuesContainer, false);
            TextView title = valueView.findViewById(R.id.item_key_value_title);
            ElementEditText nameInput = valueView.findViewById(R.id.item_key_value_name);
            TextView valueText = valueView.findViewById(R.id.item_key_value_value);

            title.setText("分区 " + (i + 1));
            final int index = i;

            if (index < segmentNames.size()) {
                nameInput.setTextWithNoTextChangedCallBack(segmentNames.get(index));
            }
            nameInput.setOnTextChangedListener(text -> {
                if (index < segmentNames.size()) {
                    segmentNames.set(index, text);
                    invalidate();
                    save();
                }
            });

            valueText.setText(pageDeviceController.getKeyNameByValue(segmentValues.get(i)));
            valueText.setOnClickListener(v -> {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        setSegmentValue(index, key.getTag().toString());
                        ((TextView) v).setText(key.getText());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
            });
            valuesContainer.addView(valueView);
        }
    }
}