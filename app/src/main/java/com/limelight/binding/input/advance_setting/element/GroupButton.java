//组按键
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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController;
import com.limelight.utils.ColorPickerDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
public class GroupButton extends Element {

    private static final String COLUMN_INT_CHILD_VISIBILITY = COLUMN_INT_ELEMENT_SENSE;

    private static final int CHILD_VISIBLE = VISIBLE;
    private static final int CHILD_INVISIBLE = INVISIBLE;

    /**
     * Listener interface to update registered observers.
     */
    public interface GroupButtonListener {

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
    private GroupButton groupButton;
    private List<Element> childElementList = new ArrayList<>();
    private ElementController elementController;
    private Context context;
    private SuperPagesController superPagesController;

    private GroupButtonListener listener;
    private String text;
    private String value;
    private int radius;
    private int thick;
    private int childVisibility;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;
    // New member variables for text properties
    private int normalTextColor;
    private int pressedTextColor;
    private int textSizePercent;

    private float lastX;
    private float lastY;
    private boolean childPositionAttributeFollow = true;
    private boolean childOtherAttributeFollow = false;
    private final int initialCentralXMax;
    private final int initialCentralXMin;
    private final int initialCentralYMax;
    private final int initialCentralYMin;

    private SuperPageLayout groupButtonPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;
    private boolean selectMode = false;

    private boolean movable = false;
    private boolean layoutComplete = true;
    private boolean resizeXBorder = true;
    private boolean resizeYBorder = true;

    private long timerLongClickTimeout = 800;
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
    private boolean hidden = false;



    public GroupButton(Map<String,Object> attributesMap,
                       ElementController controller,
                       PageDeviceController pageDeviceController,
                       SuperPagesController superPagesController,
                       Context context) {
        super(attributesMap,controller,context);
        this.pageDeviceController = pageDeviceController;
        this.groupButton = this;
        this.elementController = controller;
        this.context = context;
        this.superPagesController = superPagesController;


        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game)context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        initialCentralXMax = displayMetrics.widthPixels;
        initialCentralXMin = 0;
        initialCentralYMax = displayMetrics.heightPixels;
        initialCentralYMin = 0;
        super.centralXMax  = initialCentralXMax;
        super.centralXMin  = initialCentralXMin;
        super.centralYMax  = initialCentralYMax;
        super.centralYMin  = initialCentralYMin;
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
        childVisibility = ((Long) attributesMap.get(COLUMN_INT_CHILD_VISIBILITY)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        value = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);

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
            // Default based on a reasonable value, old logic was complex
            textSizePercent = 63;
        }

        Object hiddenFlagObj = attributesMap.get(COLUMN_INT_ELEMENT_FLAG1);
        this.hidden = (hiddenFlagObj != null) && ((Long) hiddenFlagObj).intValue() == 1;

        String[] childElementIds = value.split(",");
        List<Element> allElements = elementController.getElements();
        StringBuilder newValue = new StringBuilder("-1");
        for (String childElementIdString : childElementIds){
            Long childElementId = Long.parseLong(childElementIdString);
            for (Element element : allElements){
                if (element.elementId.equals(childElementId)){
                    childElementList.add(element);
                    newValue.append(",").append(childElementId);
                }
            }
        }
        value = newValue.toString();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE,value);
        elementController.updateElement(elementId,contentValues);
        setElementChildVisibility(childVisibility);

        listener = new GroupButtonListener() {
            @Override
            public void onClick() {

            }

            @Override
            public void onLongClick() {


            }

            @Override
            public void onRelease() {
                if (childVisibility == CHILD_VISIBLE){
                    setElementChildVisibility(CHILD_INVISIBLE);
                } else {
                    setElementChildVisibility(CHILD_VISIBLE);
                }
                save();
            }
        };
        // 在构造函数的最后，根据当前模式设置初始可见性
        onModeChanged(controller.getMode());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutComplete = true;
        super.onLayout(changed, left, top, right, bottom);
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

        if (elementController.getMode() == ElementController.Mode.Edit){
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(editColor);
            canvas.drawRect(rect,paintEdit);

        } else if (elementController.getMode() == ElementController.Mode.Select && selectMode){
            // 选中的group button做标记
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(0xff00f91a);
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
        System.out.println("onLongClickCallback");
        listener.onLongClick();
        elementController.buttonVibrator();
        movable = true;
        if (childPositionAttributeFollow){
            for (Element element : childElementList){
                element.setAlpha(0.5f);
            }
        }
        invalidate();
    }

    private void onReleaseCallback() {
        // notify listeners
        System.out.println("onReleaseCallback");
        listener.onRelease();

        // We may be called for a release without a prior click
        elementController.getHandler().removeCallbacks(longClickRunnable);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {


        if (event.getActionIndex() != 0) {
            return true;
        }

        switch (elementController.getMode()){
            case Normal:
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        elementController.buttonVibrator();
                        resizeXBorder = true;
                        resizeYBorder = true;
                        lastX = event.getX();
                        lastY = event.getY();

                        setPressed(true);
                        onClickCallback();

                        invalidate();
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (movable) {
                            float x = event.getX();
                            float y = event.getY();
                            float deltaX = x - lastX;
                            float deltaY = y - lastY;
                            //小位移算作点击
                            if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2){
                                return true;
                            }
                            if (layoutComplete){
                                layoutComplete = false;
                                setElementCentralX((int) getX() + getWidth() / 2 + (int) deltaX);
                                setElementCentralY((int) getY() + getHeight() / 2 + (int) deltaY);
                            }
                            updatePage();
                            return true;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP: {
                        setPressed(false);
                        if (movable){
                            if (childPositionAttributeFollow){
                                for (Element element : childElementList){
                                    element.setAlpha(1);
                                    element.save();
                                }
                            }
                            movable = false;
                            invalidate();
                            save();

                        } else {
                            onReleaseCallback();
                        }
                        invalidate();

                        return true;
                    }
                    default: {
                    }
                }
                return true;
            case Edit:
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        resizeXBorder = true;
                        resizeYBorder = true;
                        lastX = event.getX();
                        lastY = event.getY();
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
                        if (layoutComplete){
                            layoutComplete = false;
                            setElementCentralX((int) getX() + getWidth() / 2 + (int) deltaX);
                            setElementCentralY((int) getY() + getHeight() / 2 + (int) deltaY);
                        }
                        updatePage();
                        movable = true;
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP: {
                        if (movable){
                            if (childPositionAttributeFollow){
                                for (Element element : childElementList){
                                    element.save();
                                }
                            }
                            save();
                            movable = false;
                        } else {
                            elementController.toggleInfoPage(getInfoPage());
                        }

                        editColor = 0xffdc143c;
                        invalidate();

                        return true;
                    }
                    default: {
                    }
                }
                return true;
            case Select:
                return true;
        }
        return true;
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public void save() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
        contentValues.put(COLUMN_INT_CHILD_VISIBILITY, childVisibility);
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
        contentValues.put(COLUMN_INT_ELEMENT_FLAG1, hidden ? 1 : 0);
        // Save new text properties
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, normalTextColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, pressedTextColor);
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, textSizePercent);
        elementController.updateElement(elementId,contentValues);

    }

    @Override
    protected void updatePage() {
        if (groupButtonPage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }

    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (groupButtonPage == null){
            groupButtonPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_group_button,null);
            centralXNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_central_x);
            centralYNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_central_y);

        }

        NumberSeekbar widthNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_width);
        NumberSeekbar heightNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_height);
        NumberSeekbar radiusNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_radius);
        CheckBox childPositonAttributeFollowCheckBox = groupButtonPage.findViewById(R.id.page_group_button_child_position_attribute_follow);
        CheckBox childOtherAttributeFollowCheckBox = groupButtonPage.findViewById(R.id.page_group_button_child_other_attribute_follow);
        CheckBox childVisibleCheckBox = groupButtonPage.findViewById(R.id.page_group_button_child_visible);
        ElementEditText textElementEditText = groupButtonPage.findViewById(R.id.page_group_button_text);
        NumberSeekbar thickNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_thick);
        NumberSeekbar layerNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_layer);
        ElementEditText normalColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_normal_color);
        ElementEditText pressedColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_pressed_color);
        ElementEditText backgroundColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_background_color);
        EditText deleteEditText = groupButtonPage.findViewById(R.id.page_group_button_delete_edittext);
        Button deleteButton = groupButtonPage.findViewById(R.id.page_group_button_delete);

        // Find new views for text properties (assuming these IDs exist in the XML layout)
        NumberSeekbar textSizeNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_text_size);
        ElementEditText normalTextColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_normal_text_color);
        ElementEditText pressedTextColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_pressed_text_color);

        textElementEditText.setTextWithNoTextChangedCallBack(text);
        textElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                setElementText(text);
                save();
            }
        });

        childPositonAttributeFollowCheckBox.setChecked(childPositionAttributeFollow);
        childPositonAttributeFollowCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                childPositionAttributeFollow = isChecked;
                if (!childPositionAttributeFollow){
                    centralXMax = initialCentralXMax;
                    centralXMin = initialCentralXMin;
                    centralYMax = initialCentralYMax;
                    centralYMin = initialCentralYMin;
                }

            }
        });
        childOtherAttributeFollowCheckBox.setChecked(childOtherAttributeFollow);
        childOtherAttributeFollowCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                childOtherAttributeFollow = isChecked;
            }
        });

        childVisibleCheckBox.setChecked(childVisibility == CHILD_VISIBLE);
        childVisibleCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setElementChildVisibility(isChecked ? CHILD_VISIBLE : CHILD_INVISIBLE);
                save();
            }
        });


        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementCentralX(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                resizeXBorder = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (childPositionAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });
        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete){
                    layoutComplete = false;
                    setElementCentralY(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                resizeYBorder = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (childPositionAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });


        widthNumberSeekbar.setProgressMax(widthMax);
        widthNumberSeekbar.setProgressMin(widthMin);
        widthNumberSeekbar.setValueWithNoCallBack(getElementWidth());
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementWidth(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });

        heightNumberSeekbar.setProgressMax(heightMax);
        heightNumberSeekbar.setProgressMin(heightMin);
        heightNumberSeekbar.setValueWithNoCallBack(getElementHeight());
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementHeight(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
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
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
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
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
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
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });

        // Setup for new text size seekbar
        textSizeNumberSeekbar.setProgressMin(10); // 10%
        textSizeNumberSeekbar.setProgressMax(150); // 150%
        textSizeNumberSeekbar.setValueWithNoCallBack(textSizePercent);
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { setElementTextSizePercent(progress); }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });

        setupColorPickerButton(normalColorElementEditText, () -> this.normalColor, this::setElementNormalColor);
        setupColorPickerButton(pressedColorElementEditText, () -> this.pressedColor, this::setElementPressedColor);
        setupColorPickerButton(backgroundColorElementEditText, () -> this.backgroundColor, this::setElementBackgroundColor);
        setupColorPickerButton(normalTextColorElementEditText, () -> this.normalTextColor, this::setElementNormalTextColor);
        setupColorPickerButton(pressedTextColorElementEditText, () -> this.pressedTextColor, this::setElementPressedTextColor);


        Switch hiddenSwitch = groupButtonPage.findViewById(R.id.page_group_button_hidden_switch);
        hiddenSwitch.setChecked(hidden);
        hiddenSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setHidden(isChecked);
        });

        groupButtonPage.findViewById(R.id.page_group_button_select_child_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 改为选择模式
                elementController.changeMode(ElementController.Mode.Select);
                selectMode = true;
                // 设置按钮选择的回调函数
                ElementSelectedCallBack elementSelectedCallBack = new ElementSelectedCallBack() {
                    @Override
                    public void elementSelected(Element element) {
                        if (childElementList.contains(element)){
                            deleteChildElement(element);
                            element.setEditColor(EDIT_COLOR_SELECT);
                            element.invalidate();
                        } else {
                            addChildElement(element);
                            element.setEditColor(EDIT_COLOR_SELECTED);
                            element.invalidate();
                        }
                    }
                };
                for (Element element : childElementList){
                    element.setEditColor(EDIT_COLOR_SELECTED);
                }
                // 将子按键的编辑颜色设置为选中颜色
                for (Element element : elementController.getElements()){
                    element.setElementSelectedCallBack(elementSelectedCallBack);
                }
                // 打开空白页
                SuperPageLayout pageNull = superPagesController.getPageNull();
                superPagesController.openNewPage(pageNull);
                // 设置空白页返回的动作
                pageNull.setPageReturnListener(new SuperPageLayout.ReturnListener() {
                    @Override
                    public void returnCallBack() {
                        SuperPageLayout lastPage = pageNull.getLastPage();
                        // 用这个方法将pageNull的返回设置为editPage
                        elementController.open();
                        superPagesController.openNewPage(lastPage);
                        elementController.changeMode(ElementController.Mode.Edit);
                        selectMode = false;
                        save();
                    }
                });
            }
        });

        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (deleteEditText.getText().toString().equals("DELETE")){
                    List<Element> allElement = elementController.getElements();
                    for (Element element : childElementList){
                        if (!allElement.contains(element)){
                            continue;
                        }
                        elementController.deleteElement(element);
                    }
                    elementController.toggleInfoPage(groupButtonPage);
                    elementController.deleteElement(groupButton);
                    Toast.makeText(context,"删除成功",Toast.LENGTH_SHORT).show();
                }

            }
        });



        return groupButtonPage;
    }

    private void addChildElement(Element newElement){
        childElementList.add(newElement);
        value = "-1";
        for (Element element : childElementList){
            value = value + "," + element.elementId.toString();
        }
    }

    public void triggerAction() {
        //正常模式下的默认操作是切换子可见性，这由onRelease处理。
        if (listener != null) {
            listener.onRelease();
        }
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
        // 调用 onModeChanged 来重新评估可见性，而不是直接 invalidate
        onModeChanged(elementController.getMode());
        save();
    }

    @Override
    public void onModeChanged(ElementController.Mode newMode) {
        super.onModeChanged(newMode);

        if (newMode == ElementController.Mode.Normal && hidden) {
            setVisibility(INVISIBLE);
        } else {
            setVisibility(VISIBLE);
        }
    }

    private void deleteChildElement(Element deleteElement){
        if (childElementList.remove(deleteElement)){
            value = "-1";
            for (Element element : childElementList){
                value = value + "," + element.elementId.toString();
            }
        }

    }

    /**
     *按钮显示文本的公共getter。
     * @return GroupButton的当前文本。
     */
    public String getText() {
        return this.text;
    }


    protected void setElementText(String text) {
        this.text = text;
        invalidate();
    }

    @Override
    protected void setElementCentralX(int centralX) {
        if (childPositionAttributeFollow){
            int previousX = getElementCentralX();
            super.setElementCentralX(centralX);
            int deltaX = getElementCentralX() - previousX;
            for (Element element : childElementList){
                element.setElementCentralX(element.getElementCentralX() + deltaX);
            }
            if (resizeXBorder){
                int leftMargin = centralXMax;
                int rightMargin = centralXMax;
                List<Element> allElement = elementController.getElements();
                for (Element element : childElementList){
                    if (!allElement.contains(element)){
                        continue;
                    }
                    int elementCentralX = element.getElementCentralX();
                    leftMargin = Math.min(elementCentralX - element.centralXMin, leftMargin);
                    rightMargin = Math.min(element.centralXMax - elementCentralX, rightMargin);
                }
                int elementCentralX = getElementCentralX();
                leftMargin = Math.min(elementCentralX - centralXMin, leftMargin);
                rightMargin = Math.min(centralXMax - elementCentralX, rightMargin);

                centralXMin = elementCentralX - leftMargin;
                centralXMax = elementCentralX + rightMargin;
                if (centralXNumberSeekbar != null){
                    centralXNumberSeekbar.setProgressMin(centralXMin);
                    centralXNumberSeekbar.setProgressMax(centralXMax);
                }
                resizeXBorder = false;
            }
        } else {
            super.setElementCentralX(centralX);
        }

    }

    @Override
    protected void setElementCentralY(int centralY) {
        if (childPositionAttributeFollow){
            int previousY = getElementCentralY();
            super.setElementCentralY(centralY);
            int deltaY = getElementCentralY() - previousY;
            for (Element element : childElementList){
                element.setElementCentralY(element.getElementCentralY() + deltaY);
            }
            if (resizeYBorder){
                int bottomMargin = centralYMax;
                int topMargin = centralYMax;
                List<Element> allElement = elementController.getElements();
                for (Element element : childElementList){
                    if (!allElement.contains(element)){
                        continue;
                    }
                    int elementCentralY = element.getElementCentralY();
                    topMargin = Math.min(elementCentralY - element.centralYMin, topMargin);
                    bottomMargin = Math.min(element.centralYMax - elementCentralY, bottomMargin);
                }
                int elementCentralY = getElementCentralY();
                topMargin = Math.min(elementCentralY - centralYMin, topMargin);
                bottomMargin = Math.min(centralYMax - elementCentralY, bottomMargin);

                centralYMin = elementCentralY - topMargin;
                centralYMax = elementCentralY + bottomMargin;
                if (centralYNumberSeekbar != null){
                    centralYNumberSeekbar.setProgressMin(centralYMin);
                    centralYNumberSeekbar.setProgressMax(centralYMax);
                }
                resizeYBorder = false;
            }
        } else {
            super.setElementCentralY(centralY);
        }

    }

    @Override
    protected void setElementWidth(int width) {
        super.setElementWidth(width);
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        element.setElementWidth(width);
                        break;
                    default:
                        break;
                }
            }
        }

    }

    @Override
    protected void setElementHeight(int height) {
        super.setElementHeight(height);
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        element.setElementHeight(height);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementChildVisibility(int childVisibility){
        this.childVisibility = childVisibility;
        for (Element element : childElementList){
            element.setVisibility(childVisibility);
        }
    }

    protected void setElementRadius(int radius) {
        this.radius = radius;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementRadius(radius);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementRadius(radius);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementRadius(radius);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementRadius(radius);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementThick(int thick) {
        this.thick = thick;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_PAD:
                        ((DigitalPad)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_ANALOG_STICK:
                        ((AnalogStick)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_STICK:
                        ((DigitalStick)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                        ((InvisibleAnalogStick)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                        ((InvisibleDigitalStick)element).setElementThick(thick);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    @Override
    protected void setElementLayer(int layer) {
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                element.setElementLayer(layer);
            }
        }
        super.setElementLayer(layer);
    }

    protected void setElementNormalColor(int normalColor) {
        this.normalColor = normalColor;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_PAD:
                        ((DigitalPad)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_ANALOG_STICK:
                        ((AnalogStick)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_STICK:
                        ((DigitalStick)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                        ((InvisibleAnalogStick)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                        ((InvisibleDigitalStick)element).setElementNormalColor(normalColor);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_PAD:
                        ((DigitalPad)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_ANALOG_STICK:
                        ((AnalogStick)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_STICK:
                        ((DigitalStick)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                        ((InvisibleAnalogStick)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                        ((InvisibleDigitalStick)element).setElementPressedColor(pressedColor);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_PAD:
                        ((DigitalPad)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_ANALOG_STICK:
                        ((AnalogStick)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_STICK:
                        ((DigitalStick)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                        ((InvisibleAnalogStick)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                        ((InvisibleDigitalStick)element).setElementBackgroundColor(backgroundColor);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementNormalTextColor(int normalTextColor) {
        this.normalTextColor = normalTextColor;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementNormalTextColor(normalTextColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementNormalTextColor(normalTextColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementNormalTextColor(normalTextColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementNormalTextColor(normalTextColor);
                        break;
                }
            }
        }
    }

    protected void setElementPressedTextColor(int pressedTextColor) {
        this.pressedTextColor = pressedTextColor;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementPressedTextColor(pressedTextColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementPressedTextColor(pressedTextColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementPressedTextColor(pressedTextColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementPressedTextColor(pressedTextColor);
                        break;
                }
            }
        }
    }

    protected void setElementTextSizePercent(int textSizePercent) {
        this.textSizePercent = textSizePercent;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementTextSizePercent(textSizePercent);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementTextSizePercent(textSizePercent);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementTextSizePercent(textSizePercent);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementTextSizePercent(textSizePercent);
                        break;
                }
            }
        }
    }

    public static ContentValues getInitialInfo(){
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_GROUP_BUTTON);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT,"GROUP");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE,"-1");
        contentValues.put(COLUMN_INT_CHILD_VISIBILITY, VISIBLE);
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
        // Add new properties with good defaults
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, 0xFFFFFFFF); // White
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, 0xFFCCCCCC); // Light Grey
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 63);
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
            ).show();
        });
    }

}