//组按键
package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.PageDeviceController;
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

    private long timerLongClickTimeout = 250;
    private final Runnable longClickRunnable = this::onLongClickCallback;
    private final Paint paintBorder = new Paint();
    private final Paint paintBackground = new Paint();
    private final Paint paintText = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();
    private boolean hidden = false;
    private boolean movableInNormalMode = false;
    private boolean userHasManuallySet = false;
    private boolean isPermanentlyIndependent = false;

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


    public GroupButton(Map<String, Object> attributesMap,
                       ElementController controller,
                       PageDeviceController pageDeviceController,
                       SuperPagesController superPagesController,
                       Context context) {
        super(attributesMap, controller, context);
        this.pageDeviceController = pageDeviceController;
        this.groupButton = this;
        this.elementController = controller;
        this.context = context;
        this.superPagesController = superPagesController;


        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game) context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        initialCentralXMax = displayMetrics.widthPixels;
        initialCentralXMin = 0;
        initialCentralYMax = displayMetrics.heightPixels;
        initialCentralYMin = 0;
        super.centralXMax = initialCentralXMax;
        super.centralXMin = initialCentralXMin;
        super.centralYMax = initialCentralYMax;
        super.centralYMin = initialCentralYMin;
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
        childVisibility = ((Long) attributesMap.get(COLUMN_INT_CHILD_VISIBILITY)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        value = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE); // 仅仅读取 value 字符串，后续的链接操作将由 linkChildElements 方法完成

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
            textSizePercent = 25;
        }

        Object hiddenFlagObj = attributesMap.get(COLUMN_INT_ELEMENT_FLAG1);
        this.hidden = (hiddenFlagObj != null) && ((Long) hiddenFlagObj).intValue() == 1;

        this.movableInNormalMode = false;
        this.userHasManuallySet = false;
        this.isPermanentlyIndependent = false;
        if (attributesMap.containsKey(COLUMN_STRING_EXTRA_ATTRIBUTES)) {
            String extraAttrsJson = (String) attributesMap.get(COLUMN_STRING_EXTRA_ATTRIBUTES);
            if (extraAttrsJson != null && !extraAttrsJson.isEmpty()) {
                try { // 使用 try-catch 增加代码健壮性
                    JsonObject extraAttrs = JsonParser.parseString(extraAttrsJson).getAsJsonObject();
                    if (extraAttrs.has("movableInNormalMode")) {
                        this.movableInNormalMode = extraAttrs.get("movableInNormalMode").getAsBoolean();
                    }
                    // 加载 userHasManuallySet 状态
                    if (extraAttrs.has("userHasManuallySet")) {
                        this.userHasManuallySet = extraAttrs.get("userHasManuallySet").getAsBoolean();
                    }
                    // 加载永久独立状态
                    if (extraAttrs.has("isPermanentlyIndependent")) {
                        this.isPermanentlyIndependent = extraAttrs.get("isPermanentlyIndependent").getAsBoolean();
                    }
                } catch (Exception e) {
                }
            }
        }

        listener = new GroupButtonListener() {
            @Override
            public void onClick() {
            }

            @Override
            public void onLongClick() {
            }

            @Override
            public void onRelease() {
                // 当按钮被释放时，调用公共的触发方法
                triggerAction();
            }
        };
        // 在构造函数的最后，根据当前模式设置初始可见性
        onModeChanged(controller.getMode());
    }

    public void linkChildElements(List<Element> allElements) {
        if (value == null || value.isEmpty()) {
            return;
        }

        // 创建一个查找表 (Map)
        java.util.Map<Long, Element> elementMap = new java.util.HashMap<>();
        for (Element el : allElements) {
            elementMap.put(el.elementId, el);
        }

        String[] childElementIds = value.split(",");
        childElementList.clear();

        for (String childElementIdString : childElementIds) {
            if (childElementIdString.equals("-1")) continue;
            try {
                Long childElementId = Long.parseLong(childElementIdString);
                // 直接从 Map 中获取，效率更高
                Element child = elementMap.get(childElementId);
                if (child != null) {
                    childElementList.add(child);
                }
            } catch (NumberFormatException e) {
                // 忽略无效ID
            }
        }

        // 现在所有子元素都已链接，可以安全地设置它们的初始可见性了
        setElementChildVisibility(childVisibility);
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

        ElementController.Mode currentMode = elementController.getMode();

        if (currentMode == ElementController.Mode.Edit || currentMode == ElementController.Mode.Select) {
            // 准备绘制边框
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;

            if (currentMode == ElementController.Mode.Select && selectMode) {
                // 情况1: 当前是 Select 模式，并且这是正在操作的那个 GroupButton
                // 强制使用绿色边框
                paintEdit.setColor(0xff00f91a);
            } else {
                // 情况2: 是 Edit 模式，或者是在 Select 模式下的其他 GroupButton
                // (作为“可选”或“已选”项)
                // 统一使用 onModeChanged 中设置好的 editColor (红色、橙色或蓝色)
                paintEdit.setColor(editColor);
            }

            // 执行绘制
            canvas.drawRect(rect, paintEdit);
        }
    }

    private void onClickCallback() {
        // notify listenersbuttonListener.onClick();
        listener.onClick();
        elementController.getHandler().removeCallbacks(longClickRunnable);
        elementController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout);
    }

    private void onLongClickCallback() {
        // notify listeners
        listener.onLongClick();
        if (elementController.getMode() == ElementController.Mode.Normal && movableInNormalMode) {
            elementController.buttonVibrator();
            movable = true;
            if (childPositionAttributeFollow) {
                for (Element element : childElementList) {
                    element.setAlpha(0.5f);
                }
            }
            invalidate();
        }
    }

    private void onReleaseCallback() {
        // notify listeners
        listener.onRelease();
        // We may be called for a release without a prior click
        elementController.getHandler().removeCallbacks(longClickRunnable);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionIndex() != 0) return true;

        switch (elementController.getMode()) {
            case Normal:
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        elementController.buttonVibrator();
                        resizeXBorder = true;
                        resizeYBorder = true;
                        lastX = event.getX();
                        lastY = event.getY();
                        setPressed(true);
                        onClickCallback();
                        invalidate();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (movable) {
                            float deltaX = event.getX() - lastX;
                            float deltaY = event.getY() - lastY;
                            //小位移算作点击
                            if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2) return true;

                            if (layoutComplete) {
                                layoutComplete = false;
                                setElementCentralX((int) getX() + getWidth() / 2 + (int) deltaX);
                                setElementCentralY((int) getY() + getHeight() / 2 + (int) deltaY);
                            }
                            updatePage();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        setPressed(false);
                        if (movable) {
                            if (childPositionAttributeFollow) {
                                for (Element element : childElementList) {
                                    element.setAlpha(1);
                                }
                            }
                            movable = false;
                            save();
                        } else {
                            onReleaseCallback();
                        }
                        invalidate();
                        return true;
                }
                break;

            case Edit:
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        resizeXBorder = true;
                        resizeYBorder = true;
                        lastX = event.getX();
                        lastY = event.getY();
                        movable = false; // 重置移动标志
                        longPressDetected = false; // 重置长按标志
                        editColor = 0xffdc143c; // 红色表示初始状态
                        invalidate();

                        // 启动长按检测
                        elementController.getHandler().removeCallbacks(longPressRunnable);
                        elementController.getHandler().postDelayed(longPressRunnable, DRAG_EDIT_LONG_PRESS_TIMEOUT);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getX() - lastX;
                        float deltaY = event.getY() - lastY;

                        // 小位移算作点击
                        if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2) {
                            return true;
                        }

                        // 只有检测到长按或关闭长按移动后才允许拖动
                        if (!elementController.isDragEditEnabled() | longPressDetected) {
                            movable = true;
                            if (layoutComplete) {
                                layoutComplete = false;
                                setElementCentralX((int) getX() + getWidth() / 2 + (int) deltaX);
                                setElementCentralY((int) getY() + getHeight() / 2 + (int) deltaY);
                            }
                            updatePage();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        // 取消长按检测
                        elementController.getHandler().removeCallbacks(longPressRunnable);

                        if (movable) {
                                save();
                            movable = false;
                        } else {
                            elementController.toggleInfoPage(getInfoPage());
                        }
                        editColor = 0xffdc143c;
                        invalidate();
                        return true;
                }
                break;
            default:
                return super.onTouchEvent(event);
        }
        return true;
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public void save() {
        // 默认的 save() 应该是递归的，因为它在大多数情况下（拖动、设置）都需要
        saveHierarchy();
    }

    // 一个只保存自己的方法
    private void saveSelfOnly() {
        // 保存当前 GroupButton 自身的状态 ---
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
        contentValues.put(COLUMN_INT_CHILD_VISIBILITY, childVisibility);
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
        contentValues.put(COLUMN_INT_ELEMENT_FLAG1, hidden ? 1 : 0); // hidden remains in its own column
        // Save new text properties
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, normalTextColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, pressedTextColor);
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, textSizePercent);

        JsonObject extraAttrs = new JsonObject();
        extraAttrs.addProperty("movableInNormalMode", this.movableInNormalMode);
        extraAttrs.addProperty("userHasManuallySet", this.userHasManuallySet);
        extraAttrs.addProperty("isPermanentlyIndependent", this.isPermanentlyIndependent);
        contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, new Gson().toJson(extraAttrs));

        elementController.updateElement(elementId, contentValues);
    }

    // 一个明确的递归保存方法
    private void saveHierarchy() {
        // 步骤1：先保存自己
        saveSelfOnly();

        // 步骤2：然后命令所有子元素也进行保存
        if (childElementList != null) {
            for (Element child : childElementList) {
                // 直接调用子元素的公共 save() 方法。
                // 如果子元素是另一个 GroupButton，它会触发它自己的递归 saveHierarchy。
                // 如果是普通按钮，就执行它自己的保存逻辑。
                child.save();
            }
        }
    }

    @Override
    protected void updatePage() {
        if (groupButtonPage != null) {
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (groupButtonPage == null) {
            groupButtonPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_group_button, null);
            centralXNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_central_x);
            centralYNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_central_y);
        }

        setupFullInfoPage(groupButtonPage);

        return groupButtonPage;
    }

    private void setupFullInfoPage(SuperPageLayout page) {
        // Find views
        NumberSeekbar widthNumberSeekbar = page.findViewById(R.id.page_group_button_width);
        NumberSeekbar heightNumberSeekbar = page.findViewById(R.id.page_group_button_height);
        NumberSeekbar radiusNumberSeekbar = page.findViewById(R.id.page_group_button_radius);
        CheckBox childPositonAttributeFollowCheckBox = page.findViewById(R.id.page_group_button_child_position_attribute_follow);
        CheckBox childOtherAttributeFollowCheckBox = page.findViewById(R.id.page_group_button_child_other_attribute_follow);
        CheckBox childVisibleCheckBox = page.findViewById(R.id.page_group_button_child_visible);
        ElementEditText textElementEditText = page.findViewById(R.id.page_group_button_text);
        NumberSeekbar thickNumberSeekbar = page.findViewById(R.id.page_group_button_thick);
        NumberSeekbar layerNumberSeekbar = page.findViewById(R.id.page_group_button_layer);
        ElementEditText normalColorElementEditText = page.findViewById(R.id.page_group_button_normal_color);
        ElementEditText pressedColorElementEditText = page.findViewById(R.id.page_group_button_pressed_color);
        ElementEditText backgroundColorElementEditText = page.findViewById(R.id.page_group_button_background_color);
        EditText deleteEditText = page.findViewById(R.id.page_group_button_delete_edittext);
        Button deleteButton = page.findViewById(R.id.page_group_button_delete);
        NumberSeekbar textSizeNumberSeekbar = page.findViewById(R.id.page_group_button_text_size);
        ElementEditText normalTextColorElementEditText = page.findViewById(R.id.page_group_button_normal_text_color);
        ElementEditText pressedTextColorElementEditText = page.findViewById(R.id.page_group_button_pressed_text_color);
        Switch hiddenSwitch = page.findViewById(R.id.page_group_button_hidden_switch);
        Switch movableInNormalSwitch = page.findViewById(R.id.page_group_button_movable_in_normal_switch);

        // Setup listeners
        textElementEditText.setTextWithNoTextChangedCallBack(text);
        textElementEditText.setOnTextChangedListener(newText -> {
            setElementText(newText);
            save();
        });

        childPositonAttributeFollowCheckBox.setChecked(childPositionAttributeFollow);
        childPositonAttributeFollowCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            childPositionAttributeFollow = isChecked;
            if (!childPositionAttributeFollow) {
                centralXMax = initialCentralXMax;
                centralXMin = initialCentralXMin;
                centralYMax = initialCentralYMax;
                centralYMin = initialCentralYMin;
            }
        });

        childOtherAttributeFollowCheckBox.setChecked(childOtherAttributeFollow);
        childOtherAttributeFollowCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> childOtherAttributeFollow = isChecked);

        childVisibleCheckBox.setChecked(childVisibility == CHILD_VISIBLE);
        childVisibleCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setElementChildVisibility(isChecked ? CHILD_VISIBLE : CHILD_INVISIBLE);
            save();
        });

        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementCentralX(progress);
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
                resizeXBorder = true;
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                // 只需调用自身的 save()，因为它现在是递归的，会自动处理所有子孙
                save();
            }
        });

        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementCentralY(progress);
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
                resizeYBorder = true;
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                // 只需调用自身的 save()，因为它现在是递归的，会自动处理所有子孙
                save();
            }
        });

        widthNumberSeekbar.setProgressMax(widthMax);
        widthNumberSeekbar.setProgressMin(widthMin);
        widthNumberSeekbar.setValueWithNoCallBack(getElementWidth());
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementWidth(progress);
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);

                save();
            }
        });

        heightNumberSeekbar.setProgressMax(heightMax);
        heightNumberSeekbar.setProgressMin(heightMin);
        heightNumberSeekbar.setValueWithNoCallBack(getElementHeight());
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementHeight(progress);
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);

                save();
            }
        });

        radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
        radiusNumberSeekbar.setValueWithNoCallBack(radius);
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementRadius(progress);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {

                save();
            }
        });

        thickNumberSeekbar.setValueWithNoCallBack(thick);
        thickNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementThick(progress);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {

                save();
            }
        });

        layerNumberSeekbar.setValueWithNoCallBack(layer);
        layerNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                setElementLayer(seekBar.getProgress());

                save();
            }
        });

        textSizeNumberSeekbar.setProgressMin(10);
        textSizeNumberSeekbar.setProgressMax(150);
        textSizeNumberSeekbar.setValueWithNoCallBack(textSizePercent);
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementTextSizePercent(progress);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {

                save();
            }
        });

        setupColorPickerButton(normalColorElementEditText, () -> this.normalColor, this::setElementNormalColor);
        setupColorPickerButton(pressedColorElementEditText, () -> this.pressedColor, this::setElementPressedColor);
        setupColorPickerButton(backgroundColorElementEditText, () -> this.backgroundColor, this::setElementBackgroundColor);
        setupColorPickerButton(normalTextColorElementEditText, () -> this.normalTextColor, this::setElementNormalTextColor);
        setupColorPickerButton(pressedTextColorElementEditText, () -> this.pressedTextColor, this::setElementPressedTextColor);

        hiddenSwitch.setChecked(hidden);
        hiddenSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setHidden(isChecked));

        movableInNormalSwitch.setChecked(movableInNormalMode);
        movableInNormalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            movableInNormalMode = isChecked;
            save();
        });

        page.findViewById(R.id.page_group_button_select_child_button).setOnClickListener(v -> {
            elementController.changeMode(ElementController.Mode.Select);
            selectMode = true;
            ElementSelectedCallBack elementSelectedCallBack = element -> {
                // 如果点击的元素已经是子元素，则将其移除
                if (childElementList.contains(element)) {
                    deleteChildElement(element);
                    element.setEditColor(EDIT_COLOR_SELECT);
                } else {
                    // 准备添加新元素前，检查该元素是否为本组按键自身
                    if (element == groupButton) {
                        // 如果是自身，则提示用户不能添加，并终止操作
                        Toast.makeText(context, "不能将组按键添加到自身", Toast.LENGTH_SHORT).show();
                    } else {
                        // 如果不是自身，则正常添加为子元素
                        addChildElement(element);
                        element.setEditColor(EDIT_COLOR_SELECTED);
                    }
                }
                element.invalidate();
            };
            for (Element element : childElementList) element.setEditColor(EDIT_COLOR_SELECTED);
            for (Element element : elementController.getElements())
                element.setElementSelectedCallBack(elementSelectedCallBack);

            SuperPageLayout pageNull = superPagesController.getPageNull();
            superPagesController.openNewPage(pageNull);
            pageNull.setPageReturnListener(() -> {
                SuperPageLayout lastPage = pageNull.getLastPage();
                elementController.open();
                superPagesController.openNewPage(lastPage);
                elementController.changeMode(ElementController.Mode.Edit);
                selectMode = false;
                save();
            });
        });

        Switch permanentIndependentSwitch = page.findViewById(R.id.page_group_button_override_parent_switch);
        // 根据新的永久状态变量设置 Switch 的初始值
        permanentIndependentSwitch.setChecked(isPermanentlyIndependent);
        permanentIndependentSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPermanentlyIndependent = isChecked; // 控制新的永久状态变量
            save(); // 保存更改
            if (isChecked) {
                Toast.makeText(context, "此组按键将永久保持独立状态", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "此组按键将重新跟随父级", Toast.LENGTH_SHORT).show();
            }
        });

        deleteButton.setOnClickListener(v -> {
            if (deleteEditText.getText().toString().equals("DELETE")) {
                List<Element> allElement = new ArrayList<>(elementController.getElements());
                for (Element element : childElementList) {
                    if (allElement.contains(element)) {
                        elementController.deleteElement(element);
                    }
                }
                elementController.toggleInfoPage(groupButtonPage);
                elementController.deleteElement(groupButton);
                Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void addChildElement(Element newElement) {
        if (!childElementList.contains(newElement)) {
            childElementList.add(newElement);
            updateValueString();
        }
    }

    private void deleteChildElement(Element deleteElement) {
        if (childElementList.remove(deleteElement)) {
            updateValueString();
        }
    }

    private void updateValueString() {
        StringBuilder newValue = new StringBuilder("-1");
        for (Element element : childElementList) {
            newValue.append(",").append(element.elementId);
        }
        value = newValue.toString();
    }

    public void triggerAction() {
        // 1. 标记这个按钮被用户直接点击了
        this.userHasManuallySet = true;

        // 2. 计算出这次点击的目标状态 (显示/隐藏)
        int targetVisibility = (this.childVisibility == CHILD_VISIBLE) ? CHILD_INVISIBLE : CHILD_VISIBLE;

        // 3. 直接对当前按键执行状态切换并保存
        // 命令发起者自身必须无条件执行
        setElementChildVisibility(targetVisibility);
        saveSelfOnly();

        // 4. 创建一个 visited 集合，并将自己加入，防止在复杂的嵌套中产生循环
        java.util.Set<Element> visited = new java.util.HashSet<>();
        visited.add(this);

        // 5. 开始递归广播，让所有子孙 GroupButton 根据它们自己的状态决定是否执行
        if (childElementList != null) {
            for (Element child : childElementList) {
                if (child instanceof GroupButton) {
                    ((GroupButton) child).performTriggerAction(visited, targetVisibility);
                }
            }
        }
    }

    private void performTriggerAction(java.util.Set<Element> visited, int commandVisibility) {
        // 如果此组按键被设置为永久独立，则它会完全忽略来自父级的任何命令。
        if (this.isPermanentlyIndependent) {
            return;
        }

        if (visited.contains(this)) return;
        visited.add(this);

        boolean shouldExecute = false;

        if (commandVisibility == CHILD_INVISIBLE) {
            // 命令是“隐藏”：无条件执行，并重置手动设置标志
            shouldExecute = true;
            this.userHasManuallySet = false; // 重置状态，使其下次可以跟随父级的“显示”命令
        } else {
            // 命令是“显示”：只有在未被手动设置过的情况下才执行
            if (!this.userHasManuallySet) {
                shouldExecute = true;
            }
        }

        if (shouldExecute) {
            setElementChildVisibility(commandVisibility);
            // 调用只保存自己的方法，避免覆盖子孙状态
            saveSelfOnly();

            if (childElementList != null) {
                for (Element child : childElementList) {
                    if (child instanceof GroupButton) {
                        ((GroupButton) child).performTriggerAction(visited, commandVisibility);
                    }
                }
            }
        }
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
        onModeChanged(elementController.getMode());
        save();
    }

    @Override
    public void onModeChanged(ElementController.Mode newMode) {
        super.onModeChanged(newMode);

        // 首先处理可见性逻辑，确保在不同模式下显示正确
        setVisibility(newMode == ElementController.Mode.Normal && hidden ? INVISIBLE : VISIBLE);

        // 然后处理边框颜色的逻辑
        switch (newMode) {
            case Select:
                // 当进入“选择”模式时，将此组按键的边框颜色设置为“可选择”状态（橙色）。
                // G1稍后会把它自己的子元素（可能是G2）的颜色覆盖为“已选中”（蓝色）。
                setEditColor(EDIT_COLOR_SELECT);
                break;
            case Edit:
                // 当返回“编辑”模式时，恢复为默认的编辑颜色（红色）。
                setEditColor(EDIT_COLOR_EDIT);
                break;
            case Normal:
                // 在正常模式下，重置状态。
                setEditColor(EDIT_COLOR_EDIT);
                break;
        }
        // 强制重绘以更新边框
        invalidate();
    }

    public String getText() {
        return this.text;
    }


    /**
     * 获取此组按键包含的所有子元素的ID列表。
     * 这个公共方法是为了让其他组件（如WheelPad）能够查询组的内容以实现预览等功能。
     *
     * @return 一个包含所有子元素ID的列表。
     */
    public List<Long> getChildIds() {
        List<Long> childIds = new ArrayList<>();
        if (childElementList != null) {
            for (Element child : childElementList) {
                if (child != null) childIds.add(child.elementId);
            }
        }
        return childIds;
    }


    protected void setElementText(String text) {
        this.text = text;
        invalidate();
    }

    @Override
    protected void setElementCentralX(int centralX) {
        if (childPositionAttributeFollow) {
            int previousX = getElementCentralX();
            super.setElementCentralX(centralX);
            int deltaX = getElementCentralX() - previousX;
            for (Element element : childElementList) {
                element.setElementCentralX(element.getElementCentralX() + deltaX);
            }
            if (resizeXBorder) {
                int leftMargin = centralXMax;
                int rightMargin = centralXMax;
                List<Element> allElement = elementController.getElements();
                for (Element element : childElementList) {
                    if (!allElement.contains(element)) {
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
                if (centralXNumberSeekbar != null) {
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
        if (childPositionAttributeFollow) {
            int previousY = getElementCentralY();
            super.setElementCentralY(centralY);
            int deltaY = getElementCentralY() - previousY;
            for (Element element : childElementList) {
                element.setElementCentralY(element.getElementCentralY() + deltaY);
            }
            if (resizeYBorder) {
                int bottomMargin = centralYMax;
                int topMargin = centralYMax;
                List<Element> allElement = elementController.getElements();
                for (Element element : childElementList) {
                    if (!allElement.contains(element)) {
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
                if (centralYNumberSeekbar != null) {
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
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementWidth(width);
                } else {
                    switch (element.elementType) {
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
    }

    @Override
    protected void setElementHeight(int height) {
        super.setElementHeight(height);
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementHeight(height);
                } else {
                    switch (element.elementType) {
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
    }

    protected void setElementChildVisibility(int childVisibility) {
        this.childVisibility = childVisibility;
        for (Element element : childElementList) {
            element.setVisibility(childVisibility);
        }
    }

    protected void setElementRadius(int radius) {
        this.radius = radius;
        invalidate();
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementRadius(radius);
                } else {
                    switch (element.elementType) {
                        case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                            ((DigitalCommonButton) element).setElementRadius(radius);
                            break;
                        case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                            ((DigitalSwitchButton) element).setElementRadius(radius);
                            break;
                        case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                            ((DigitalCombineButton) element).setElementRadius(radius);
                            break;
                        case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                            ((DigitalMovableButton) element).setElementRadius(radius);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    protected void setElementThick(int thick) {
        this.thick = thick;
        invalidate();
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementThick(thick);
                } else {
                    switch (element.elementType) {
                        case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                            ((DigitalCommonButton) element).setElementThick(thick);
                            break;
                        case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                            ((DigitalSwitchButton) element).setElementThick(thick);
                            break;
                        case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                            ((DigitalCombineButton) element).setElementThick(thick);
                            break;
                        case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                            ((DigitalMovableButton) element).setElementThick(thick);
                            break;
                        case ELEMENT_TYPE_DIGITAL_PAD:
                            ((DigitalPad) element).setElementThick(thick);
                            break;
                        case ELEMENT_TYPE_ANALOG_STICK:
                            ((AnalogStick) element).setElementThick(thick);
                            break;
                        case ELEMENT_TYPE_DIGITAL_STICK:
                            ((DigitalStick) element).setElementThick(thick);
                            break;
                        case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                            ((InvisibleAnalogStick) element).setElementThick(thick);
                            break;
                        case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                            ((InvisibleDigitalStick) element).setElementThick(thick);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    @Override
    protected void setElementLayer(int layer) {
        super.setElementLayer(layer);
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                // 直接调用子元素的 setElementLayer，因为 GroupButton 自己的这个方法
                // 已经被重写为递归的，所以能自动形成递归链。
                element.setElementLayer(layer);
            }
        }
    }

    protected void setElementNormalColor(int normalColor) {
        this.normalColor = normalColor;
        invalidate();
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementNormalColor(normalColor);
                } else {
                    switch (element.elementType) {
                        case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                            ((DigitalCommonButton) element).setElementNormalColor(normalColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                            ((DigitalSwitchButton) element).setElementNormalColor(normalColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                            ((DigitalCombineButton) element).setElementNormalColor(normalColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                            ((DigitalMovableButton) element).setElementNormalColor(normalColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_PAD:
                            ((DigitalPad) element).setElementNormalColor(normalColor);
                            break;
                        case ELEMENT_TYPE_ANALOG_STICK:
                            ((AnalogStick) element).setElementNormalColor(normalColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_STICK:
                            ((DigitalStick) element).setElementNormalColor(normalColor);
                            break;
                        case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                            ((InvisibleAnalogStick) element).setElementNormalColor(normalColor);
                            break;
                        case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                            ((InvisibleDigitalStick) element).setElementNormalColor(normalColor);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    protected void setElementPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
        invalidate();
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementPressedColor(pressedColor);
                } else {
                    switch (element.elementType) {
                        case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                            ((DigitalCommonButton) element).setElementPressedColor(pressedColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                            ((DigitalSwitchButton) element).setElementPressedColor(pressedColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                            ((DigitalCombineButton) element).setElementPressedColor(pressedColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                            ((DigitalMovableButton) element).setElementPressedColor(pressedColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_PAD:
                            ((DigitalPad) element).setElementPressedColor(pressedColor);
                            break;
                        case ELEMENT_TYPE_ANALOG_STICK:
                            ((AnalogStick) element).setElementPressedColor(pressedColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_STICK:
                            ((DigitalStick) element).setElementPressedColor(pressedColor);
                            break;
                        case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                            ((InvisibleAnalogStick) element).setElementPressedColor(pressedColor);
                            break;
                        case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                            ((InvisibleDigitalStick) element).setElementPressedColor(pressedColor);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    protected void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementBackgroundColor(backgroundColor);
                } else {
                    switch (element.elementType) {
                        case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                            ((DigitalCommonButton) element).setElementBackgroundColor(backgroundColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                            ((DigitalSwitchButton) element).setElementBackgroundColor(backgroundColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                            ((DigitalCombineButton) element).setElementBackgroundColor(backgroundColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                            ((DigitalMovableButton) element).setElementBackgroundColor(backgroundColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_PAD:
                            ((DigitalPad) element).setElementBackgroundColor(backgroundColor);
                            break;
                        case ELEMENT_TYPE_ANALOG_STICK:
                            ((AnalogStick) element).setElementBackgroundColor(backgroundColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_STICK:
                            ((DigitalStick) element).setElementBackgroundColor(backgroundColor);
                            break;
                        case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                            ((InvisibleAnalogStick) element).setElementBackgroundColor(backgroundColor);
                            break;
                        case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                            ((InvisibleDigitalStick) element).setElementBackgroundColor(backgroundColor);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    protected void setElementNormalTextColor(int normalTextColor) {
        this.normalTextColor = normalTextColor;
        invalidate();
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementNormalTextColor(normalTextColor);
                } else {
                    switch (element.elementType) {
                        case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                            ((DigitalCommonButton) element).setElementNormalTextColor(normalTextColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                            ((DigitalSwitchButton) element).setElementNormalTextColor(normalTextColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                            ((DigitalCombineButton) element).setElementNormalTextColor(normalTextColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                            ((DigitalMovableButton) element).setElementNormalTextColor(normalTextColor);
                            break;
                    }
                }
            }
        }
    }

    protected void setElementPressedTextColor(int pressedTextColor) {
        this.pressedTextColor = pressedTextColor;
        invalidate();
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementPressedTextColor(pressedTextColor);
                } else {
                    switch (element.elementType) {
                        case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                            ((DigitalCommonButton) element).setElementPressedTextColor(pressedTextColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                            ((DigitalSwitchButton) element).setElementPressedTextColor(pressedTextColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                            ((DigitalCombineButton) element).setElementPressedTextColor(pressedTextColor);
                            break;
                        case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                            ((DigitalMovableButton) element).setElementPressedTextColor(pressedTextColor);
                            break;
                    }
                }
            }
        }
    }

    protected void setElementTextSizePercent(int textSizePercent) {
        this.textSizePercent = textSizePercent;
        invalidate();
        if (childOtherAttributeFollow) {
            for (Element element : childElementList) {
                if (element instanceof GroupButton) {
                    ((GroupButton) element).setElementTextSizePercent(textSizePercent);
                } else {
                    switch (element.elementType) {
                        case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                            ((DigitalCommonButton) element).setElementTextSizePercent(textSizePercent);
                            break;
                        case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                            ((DigitalSwitchButton) element).setElementTextSizePercent(textSizePercent);
                            break;
                        case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                            ((DigitalCombineButton) element).setElementTextSizePercent(textSizePercent);
                            break;
                        case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                            ((DigitalMovableButton) element).setElementTextSizePercent(textSizePercent);
                            break;
                    }
                }
            }
        }
    }

    public static ContentValues getInitialInfo() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_GROUP_BUTTON);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "GROUP");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "-1");
        contentValues.put(COLUMN_INT_CHILD_VISIBILITY, VISIBLE);
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
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, 0xFFFFFFFF);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, 0xFFCCCCCC);
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 25);
        contentValues.put(COLUMN_INT_ELEMENT_FLAG1, 0); // hidden flag

        JsonObject extraAttrs = new JsonObject();
        extraAttrs.addProperty("movableInNormalMode", false); // Default value is false
        extraAttrs.addProperty("userHasManuallySet", false);
        contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, new Gson().toJson(extraAttrs));

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
                        // 步骤1: 调用递归的 set... 方法，更新内存中所有相关元素的状态
                        colorUpdater.accept(newColor);

                        // 步骤2: 调用一次统一的、递归的 save() 方法
                        // 它会把当前按键以及所有子孙按键的最新状态全部保存到数据库
                        save();

                        // 步骤3: 更新UI显示
                        updateColorDisplay(colorDisplay, newColor);
                    }
            ).show();
        });
    }
}