//手柄摇杆
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class AnalogStick extends Element {

    private static final String COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS = COLUMN_INT_ELEMENT_SENSE;

    /**
     * extra_attributes 中存放触发功能配置的键名
     */
    private static final String EXTRA_ATTR_TRIGGER_KEY = "trigger_key";
    private static final String EXTRA_ATTR_TRIGGER_RANGE = "trigger_range_adjust";

    /**
     * 触发圈的颜色（橙色），按下触发时高亮
     */
    private static final int TRIGGER_RING_COLOR = 0xF0FF8800;
    private static final int TRIGGER_RING_ACTIVE_COLOR = 0xFFFF5500;

    /**
     * outer radius size in percent of the ui element
     */
    public static final int SIZE_RADIUS_COMPLETE = 90;
    /**
     * analog stick size in percent of the ui element
     */
    public static final int SIZE_RADIUS_ANALOG_STICK = 90;
    /**
     * dead zone size in percent of the ui element
     */
    public static final int SIZE_RADIUS_DEADZONE = 90;
    /**
     * time frame for a double click
     */
    public final static long timeoutDoubleClick = 350;

    /**
     * touch down time until the deadzone is lifted to allow precise movements with the analog sticks
     */
    public final static long timeoutDeadzone = 150;

    private int moveMode = 0; // 0: 绝对位置模式, 1: 相对移动模式

    /**
     * Listener interface to update registered observers.
     */
    public interface AnalogStickListener {

        /**
         * onMovement event will be fired on real analog stick movement (outside of the deadzone).
         *
         * @param x horizontal position, value from -1.0 ... 0 .. 1.0
         * @param y vertical position, value from -1.0 ... 0 .. 1.0
         */
        void onMovement(float x, float y);

        /**
         * onClick event will be fired on click on the analog stick
         */
        void onClick();

        /**
         * onDoubleClick event will be fired on a double click in a short time frame on the analog
         * stick.
         */
        void onDoubleClick();

        /**
         * onRevoke event will be fired on unpress of the analog stick.
         */
        void onRevoke();
    }

    /**
     * Movement states of the analog sick.
     */
    private enum STICK_STATE {
        NO_MOVEMENT,
        MOVED_IN_DEAD_ZONE,
        MOVED_ACTIVE
    }

    /**
     * Click type states.
     */
    private enum CLICK_STATE {
        SINGLE,
        DOUBLE
    }

    /**
     * configuration if the analog stick should be displayed as circle or square
     */
    private boolean circle_stick = true; // TODO: implement square sick for simulations

    /**
     * outer radius, this size will be automatically updated on resize
     */
    private float radius_complete = 0;
    /**
     * analog stick radius, this size will be automatically updated on resize
     */
    private float radius_analog_stick = 0;
    /**
     * dead zone radius, this size will be automatically updated on resize
     */
    private float radius_dead_zone = 0;

    /**
     * horizontal position in relation to the center of the element
     */
    private float relative_x = 0;
    /**
     * vertical position in relation to the center of the element
     */
    private float relative_y = 0;


    private double movement_radius = 0;
    private double movement_angle = 0;

    private float position_stick_x = 0;
    private float position_stick_y = 0;

    private boolean isFirstTouch = true;
    private float FirstTouchX = 0;
    private float FirstTouchY = 0;


    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private PageDeviceController pageDeviceController;
    private AnalogStick analogStick;

    private ElementController.SendEventHandler middleValueSendHandler;
    private ElementController.SendEventHandler valueSendHandler;
    private String middleValue;
    private String value;
    private int radius;
    private int deadZoneRadius; //dead zone radius
    private int thick;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;

    private SuperPageLayout analogStickPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;

    // 推杆触发功能：当摇杆推出到触发圈外时，触发一个自定义按键
    private String triggerKey = ElementController.SPECIAL_KEY_NULL; // 自定义触发按键，"null" 表示关闭
    private int triggerRangeAdjust = 0; // 触发范围调节值（0-100），0 表示关闭/紧贴摇杆头
    private ElementController.SendEventHandler triggerSendHandler;
    private float radius_trigger = 0; // 触发圈半径（像素），由 radius + triggerRangeAdjust 计算
    private boolean triggerPressed = false; // 触发键当前是否处于按下状态（用于边沿检测）

    private final Paint paintStick = new Paint();
    private final Paint paintBackground = new Paint();
    private final Paint paintTrigger = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();
    private AnalogStick.STICK_STATE stick_state = AnalogStick.STICK_STATE.NO_MOVEMENT;
    private AnalogStick.CLICK_STATE click_state = AnalogStick.CLICK_STATE.SINGLE;

    private AnalogStickListener listener;
    private long timeLastClick = 0;

    private static double getMovementRadius(float x, float y) {
        return Math.sqrt(x * x + y * y);
    }

    private static double getAngle(float way_x, float way_y) {
        // prevent divisions by zero for corner cases
        if (way_x == 0) {
            return way_y < 0 ? 0 : Math.PI;
        } else if (way_y == 0) {
            if (way_x > 0) {
                return Math.PI * 3 / 2;
            } else if (way_x < 0) {
                return Math.PI * 1 / 2;
            }
        }
        // return correct calculated angle for each quadrant
        if (way_x > 0) {
            if (way_y < 0) {
                // first quadrant
                return 3 * Math.PI / 2 + Math.atan((double) (-way_y / way_x));
            } else {
                // second quadrant
                return Math.PI + Math.atan((double) (way_x / way_y));
            }
        } else {
            if (way_y > 0) {
                // third quadrant
                return Math.PI / 2 + Math.atan((double) (way_y / -way_x));
            } else {
                // fourth quadrant
                return 0 + Math.atan((double) (-way_x / -way_y));
            }
        }
    }

    public AnalogStick(Map<String, Object> attributesMap,
                       ElementController controller,
                       PageDeviceController pageDeviceController, Context context) {
        super(attributesMap, controller, context);
        // reset stick position
        position_stick_x = getWidth() / 2;
        position_stick_y = getHeight() / 2;

        this.pageDeviceController = pageDeviceController;
        this.analogStick = this;


        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game) context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        super.centralXMax = displayMetrics.widthPixels;
        super.centralXMin = 0;
        super.centralYMax = displayMetrics.heightPixels;
        super.centralYMin = 0;
        super.widthMax = displayMetrics.widthPixels;
        super.widthMin = 100;
        super.heightMax = displayMetrics.heightPixels;
        super.heightMin = 100;

        paintBackground.setStyle(Paint.Style.FILL);
        paintStick.setStyle(Paint.Style.STROKE);
        paintTrigger.setStyle(Paint.Style.STROKE);
        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));
        try {
            radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
            deadZoneRadius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS)).intValue();
            thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
            normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
            pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
            backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
            middleValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_MIDDLE_VALUE);
            value = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);
            moveMode = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_MODE)).intValue();
        } catch (Exception e) {
            if (radius == 0) radius = 100;
            if (deadZoneRadius == 0) deadZoneRadius = 30;
            if (thick == 0) thick = 5;
            if (normalColor == 0) normalColor = 0xF0888888;
            if (pressedColor == 0) pressedColor = 0xF00000FF;
            if (backgroundColor == 0) backgroundColor = 0x00FFFFFF;
            if (middleValue == null) middleValue = "g64";
            if (value == null) value = "LS";
            if (moveMode == 0) moveMode = 0;
            System.out.println("加载按摇杆时发生错误，已应用默认值: " + e.getMessage());
        }
        middleValueSendHandler = controller.getSendEventHandler(middleValue);
        radius_complete = getPercent(radius, 100) - 2 * thick;
        radius_dead_zone = getPercent(radius, deadZoneRadius);
        radius_analog_stick = getPercent(radius, 20);

        // 读取推杆触发功能配置（存放于通用的 extra_attributes JSON 列）
        Object extraAttrObj = attributesMap.get(COLUMN_STRING_EXTRA_ATTRIBUTES);
        if (extraAttrObj instanceof String) {
            try {
                Gson gson = new Gson();
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> extraAttrs = gson.fromJson((String) extraAttrObj, type);
                if (extraAttrs != null) {
                    Object keyObj = extraAttrs.get(EXTRA_ATTR_TRIGGER_KEY);
                    if (keyObj instanceof String && !((String) keyObj).isEmpty()) {
                        triggerKey = (String) keyObj;
                    }
                    Object rangeObj = extraAttrs.get(EXTRA_ATTR_TRIGGER_RANGE);
                    if (rangeObj instanceof Number) {
                        triggerRangeAdjust = ((Number) rangeObj).intValue();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        triggerSendHandler = controller.getSendEventHandler(triggerKey);
        updateTriggerRadius();

        valueSendHandler = controller.getSendEventHandler(value);

        listener = new AnalogStickListener() {
            @Override
            public void onMovement(float x, float y) {
                valueSendHandler.sendEvent((int) (x * 0x7FFE), (int) (y * 0x7FFE));
            }

            @Override
            public void onClick() {
                elementController.buttonVibrator();
            }

            @Override
            public void onDoubleClick() {
                middleValueSendHandler.sendEvent(true);
            }

            @Override
            public void onRevoke() {
                middleValueSendHandler.sendEvent(false);
            }
        };

    }


    private void notifyOnMovement(float x, float y) {
        // notify listeners
        listener.onMovement(x, y);
    }

    private void notifyOnClick() {
        // notify listeners
        listener.onClick();
    }

    private void notifyOnDoubleClick() {
        // notify listeners
        listener.onDoubleClick();
    }

    private void notifyOnRevoke() {
        // notify listeners
        listener.onRevoke();
    }

    /**
     * 计算触发圈半径。
     * 规则：摇杆中心 + 摇杆头半径(radius_analog_stick) + 自定义调节范围值。
     * 由于摇杆的实际位移会被裁剪到 (radius_complete - radius_analog_stick)，
     * 且绘制不能超出可触摸区域(View 尺寸 = 2*radius)，
     * 因此将触发半径夹在 [radius_dead_zone, radius_complete - radius_analog_stick] 之间，
     * 保证既能被推到、橙色圈又始终完整可见。
     */
    private void updateTriggerRadius() {
        float maxReach = radius_complete - radius_analog_stick;
        radius_trigger = radius_analog_stick + getPercent(radius, triggerRangeAdjust);
        if (radius_trigger > maxReach) {
            radius_trigger = maxReach;
        }
        if (radius_trigger < radius_dead_zone) {
            radius_trigger = radius_dead_zone;
        }
    }

    /**
     * 触发功能是否启用：必须配置了非空按键，且调节范围大于 0。
     */
    private boolean isTriggerEnabled() {
        return triggerKey != null
                && !triggerKey.equals(ElementController.SPECIAL_KEY_NULL)
                && triggerRangeAdjust > 0;
    }

    /**
     * 根据当前位移半径处理触发按键的按下/松开（边沿检测）。
     * @param rawMovementRadius 未被裁剪的原始位移半径
     */
    private void handleTrigger(double rawMovementRadius) {
        if (!isTriggerEnabled()) {
            return;
        }
        boolean shouldPress = rawMovementRadius >= radius_trigger;
        if (shouldPress && !triggerPressed) {
            triggerPressed = true;
            triggerSendHandler.sendEvent(true);
        } else if (!shouldPress && triggerPressed) {
            // 手指退回触发圈内：松开按键
            triggerPressed = false;
            triggerSendHandler.sendEvent(false);
        }
    }

    /**
     * 释放触发按键（用于抬手/撤销时）。
     */
    private void releaseTrigger() {
        if (triggerPressed) {
            triggerPressed = false;
            triggerSendHandler.sendEvent(false);
        }
    }


    @Override
    protected void onElementDraw(Canvas canvas) {
        // set transparent background
        paintBackground.setColor(backgroundColor);
        canvas.drawCircle(radius, radius, radius_complete, paintBackground);

        paintStick.setStrokeWidth(thick);
        // draw outer circle
        if (!isPressed() || click_state == AnalogStick.CLICK_STATE.SINGLE) {
            paintStick.setColor(normalColor);
        } else {
            paintStick.setColor(pressedColor);
        }
        canvas.drawCircle(radius, radius, radius_complete, paintStick);

        paintStick.setColor(normalColor);
        // draw dead zone
        canvas.drawCircle(radius, radius, radius_dead_zone, paintStick);

        // 绘制橙色触发圈：启用触发功能时，或处于编辑/选择模式时（便于调节时实时预览）
        ElementController.Mode drawMode = elementController.getMode();
        boolean editingNow = drawMode == ElementController.Mode.Edit || drawMode == ElementController.Mode.Select;
        if (isTriggerEnabled() || (editingNow && triggerRangeAdjust > 0)) {
            paintTrigger.setStrokeWidth(thick);
            paintTrigger.setColor(triggerPressed ? TRIGGER_RING_ACTIVE_COLOR : TRIGGER_RING_COLOR);
            canvas.drawCircle(radius, radius, radius_trigger, paintTrigger);
        }

        // draw stick depending on state
        switch (stick_state) {
            case NO_MOVEMENT: {
                paintStick.setColor(normalColor);
                canvas.drawCircle(radius, radius, radius_analog_stick, paintStick);
                break;
            }
            case MOVED_IN_DEAD_ZONE:
            case MOVED_ACTIVE: {
                paintStick.setColor(pressedColor);
                canvas.drawCircle(position_stick_x, position_stick_y, radius_analog_stick, paintStick);
                break;
            }
        }

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

    private void updatePosition(long eventTime) {
        // get 100% way
        float complete = radius_complete - radius_analog_stick;

        // calculate relative way
        float correlated_y = (float) (Math.sin(Math.PI / 2 - movement_angle) * (movement_radius));
        float correlated_x = (float) (Math.cos(Math.PI / 2 - movement_angle) * (movement_radius));

        // update positions
        position_stick_x = radius - correlated_x;
        position_stick_y = radius - correlated_y;

        // Stay active even if we're back in the deadzone because we know the user is actively
        // giving analog stick input and we don't want to snap back into the deadzone.
        // We also release the deadzone if the user keeps the stick pressed for a bit to allow
        // them to make precise movements.
        stick_state = (stick_state == AnalogStick.STICK_STATE.MOVED_ACTIVE ||
                movement_radius > radius_dead_zone) ?
                AnalogStick.STICK_STATE.MOVED_ACTIVE : AnalogStick.STICK_STATE.MOVED_IN_DEAD_ZONE;

        //  trigger move event if state active
        if (stick_state == AnalogStick.STICK_STATE.MOVED_ACTIVE) {
            notifyOnMovement(-correlated_x / complete, correlated_y / complete);
        }
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // save last click state
        AnalogStick.CLICK_STATE lastClickState = click_state;

        if (isFirstTouch) {
            isFirstTouch = false;
            FirstTouchX = event.getX();
            FirstTouchY = event.getY();
        }


        // get absolute way for each axis
        if (moveMode == 1) {
            relative_x = event.getX() - FirstTouchX;
            relative_y = event.getY() - FirstTouchY;
        } else {
            relative_x = -(radius - event.getX());
            relative_y = -(radius - event.getY());
        }

        // get radius and angel of movement from center
        movement_radius = getMovementRadius(relative_x, relative_y);
        movement_angle = getAngle(relative_x, relative_y);

        // 记录未被裁剪的原始位移半径，用于触发圈判定（触发圈可能位于裁剪范围边缘）
        double rawMovementRadius = movement_radius;

        // pass touch event to parent if out of outer circle
        if (movement_radius > radius_complete && !isPressed())
            return false;

        // chop radius if out of outer circle or near the edge
        if (movement_radius > (radius_complete - radius_analog_stick)) {
            movement_radius = radius_complete - radius_analog_stick;
        }

        // handle event depending on action
        switch (event.getActionMasked()) {
            // down event (touch event)
            case MotionEvent.ACTION_DOWN: {
                // set to dead zoned, will be corrected in update position if necessary
                stick_state = AnalogStick.STICK_STATE.MOVED_IN_DEAD_ZONE;
                // check for double click
                if (lastClickState == AnalogStick.CLICK_STATE.SINGLE &&
                        event.getEventTime() - timeLastClick <= timeoutDoubleClick) {
                    click_state = AnalogStick.CLICK_STATE.DOUBLE;
                    notifyOnDoubleClick();
                } else {
                    click_state = AnalogStick.CLICK_STATE.SINGLE;
                    notifyOnClick();
                }
                // reset last click timestamp
                timeLastClick = event.getEventTime();
                // set item pressed and update
                setPressed(true);
                break;
            }
            // up event (revoke touch)
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                FirstTouchX = 0;
                FirstTouchY = 0;
                isFirstTouch = true;
                break;
            }
        }

        if (isPressed()) {
            // when is pressed calculate new positions (will trigger movement if necessary)
            updatePosition(event.getEventTime());
            // 处理推杆触发：用未裁剪的原始位移半径判定是否越过触发圈
            handleTrigger(rawMovementRadius);
        } else {
            stick_state = AnalogStick.STICK_STATE.NO_MOVEMENT;
            notifyOnRevoke();

            // 抬手时释放触发按键
            releaseTrigger();

            // not longer pressed reset analog stick
            notifyOnMovement(0, 0);
        }
        // refresh view
        invalidate();
        // accept the touch event
        return true;
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (analogStickPage == null) {
            analogStickPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_analog_stick_clean, null);
            centralXNumberSeekbar = analogStickPage.findViewById(R.id.page_analog_stick_central_x);
            centralYNumberSeekbar = analogStickPage.findViewById(R.id.page_analog_stick_central_y);

        }

        NumberSeekbar radiusNumberSeekbar = analogStickPage.findViewById(R.id.page_analog_stick_radius);
        TextView middleValueTextView = analogStickPage.findViewById(R.id.page_analog_stick_middle_value);
        RadioGroup modeRadioGroup = analogStickPage.findViewById(R.id.page_analog_stick_value);
        Switch moveModeSwitch = analogStickPage.findViewById(R.id.page_analog_stick_move_mode);
        NumberSeekbar deadZoneRadiusNumberSeekbar = analogStickPage.findViewById(R.id.page_analog_stick_sense);
        NumberSeekbar thickNumberSeekbar = analogStickPage.findViewById(R.id.page_analog_stick_thick);
        NumberSeekbar layerNumberSeekbar = analogStickPage.findViewById(R.id.page_analog_stick_layer);
        TextView triggerValueTextView = analogStickPage.findViewById(R.id.page_analog_stick_trigger_value);
        NumberSeekbar triggerRangeNumberSeekbar = analogStickPage.findViewById(R.id.page_analog_stick_trigger_range);
        ElementEditText normalColorEditText = analogStickPage.findViewById(R.id.page_analog_stick_normal_color);
        ElementEditText pressedColorEditText = analogStickPage.findViewById(R.id.page_analog_stick_pressed_color);
        ElementEditText backgroundColorEditText = analogStickPage.findViewById(R.id.page_analog_stick_background_color);
        Button copyButton = analogStickPage.findViewById(R.id.page_analog_stick_copy);
        Button deleteButton = analogStickPage.findViewById(R.id.page_analog_stick_delete);

        RadioButton radioButton = modeRadioGroup.findViewWithTag(value);
        radioButton.setChecked(true);
        modeRadioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                setElementValue(group.findViewById(checkedId).getTag().toString());
                save();
            }
        });

        middleValueTextView.setText(pageDeviceController.getKeyNameByValue(middleValue));
        middleValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementMiddleValue(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
            }
        });
        moveModeSwitch.setChecked(moveMode == 1);
        moveModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                moveMode = isChecked ? 1 : 0;
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


        deadZoneRadiusNumberSeekbar.setValueWithNoCallBack(deadZoneRadius);
        deadZoneRadiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementDeadZoneRadius(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });


        radiusNumberSeekbar.setProgressMax(widthMax < heightMax ? widthMax / 2 : heightMax / 2);
        radiusNumberSeekbar.setProgressMin(widthMin / 2);
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

        // 触发按键选择器：点击弹出设备选择，支持键盘/鼠标/手柄任意键
        triggerValueTextView.setText(pageDeviceController.getKeyNameByValue(triggerKey));
        triggerValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        ((TextView) v).setText(key.getText());
                        setElementTriggerValue(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
            }
        });

        // 触发范围调节：拖动时实时重算并重绘橙色触发圈
        triggerRangeNumberSeekbar.setValueWithNoCallBack(triggerRangeAdjust);
        triggerRangeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementTriggerRangeAdjust(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        CrownColorPickerBinder.bind(this, normalColorEditText, () -> this.normalColor, this::setElementNormalColor);
        CrownColorPickerBinder.bind(this, pressedColorEditText, () -> this.pressedColor, this::setElementPressedColor);
        CrownColorPickerBinder.bind(this, backgroundColorEditText, () -> this.backgroundColor, this::setElementBackgroundColor);


        copyButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_ANALOG_STICK);
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
                contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, middleValue);
                contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, deadZoneRadius);
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
                contentValues.put(COLUMN_INT_ELEMENT_MODE, moveMode);
                contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, buildExtraAttributesJson());
                elementController.addElement(contentValues);
            }
        });

        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                elementController.toggleInfoPage(analogStickPage);
                elementController.deleteElement(analogStick);
            }
        });


        return analogStickPage;
    }

    @Override
    public void save() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
        contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, middleValue);
        contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, deadZoneRadius);
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
        contentValues.put(COLUMN_INT_ELEMENT_MODE, moveMode);
        contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, buildExtraAttributesJson());
        elementController.updateElement(elementId, contentValues);

    }

    /**
     * 将推杆触发配置序列化为 extra_attributes JSON 字符串。
     */
    private String buildExtraAttributesJson() {
        Map<String, Object> extraAttrs = new HashMap<>();
        extraAttrs.put(EXTRA_ATTR_TRIGGER_KEY, triggerKey);
        extraAttrs.put(EXTRA_ATTR_TRIGGER_RANGE, triggerRangeAdjust);
        return new Gson().toJson(extraAttrs);
    }

    @Override
    protected void updatePage() {
        if (analogStickPage != null) {
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }

    }

    public void setElementValue(String value) {
        this.value = value;
        valueSendHandler = elementController.getSendEventHandler(value);
    }

    public void setElementMiddleValue(String middleValue) {
        this.middleValue = middleValue;
        middleValueSendHandler = elementController.getSendEventHandler(middleValue);
    }

    public void setElementRadius(int radius) {
        this.radius = radius;
        radius_complete = getPercent(radius, 100) - 2 * thick;
        radius_dead_zone = getPercent(radius, deadZoneRadius);
        radius_analog_stick = getPercent(radius, 20);
        updateTriggerRadius();
        setElementWidth(radius * 2);
        setElementHeight(radius * 2);
        invalidate();
    }

    public void setElementDeadZoneRadius(int deadZoneRadius) {
        this.deadZoneRadius = deadZoneRadius;
        radius_dead_zone = getPercent(radius, deadZoneRadius);
        updateTriggerRadius();
        invalidate();
    }

    public void setElementThick(int thick) {
        this.thick = thick;
        radius_complete = getPercent(radius, 100) - 2 * thick;
        updateTriggerRadius();
        invalidate();
    }

    public void setElementNormalColor(int normalColor) {
        this.normalColor = normalColor;
        invalidate();
    }

    public void setElementPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
        invalidate();
    }

    public void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
    }

    public void setElementTriggerValue(String triggerKey) {
        this.triggerKey = triggerKey;
        this.triggerSendHandler = elementController.getSendEventHandler(triggerKey);
        invalidate();
    }

    public void setElementTriggerRangeAdjust(int triggerRangeAdjust) {
        this.triggerRangeAdjust = triggerRangeAdjust;
        updateTriggerRadius();
        invalidate();
    }

    public static ContentValues getInitialInfo() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_ANALOG_STICK);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "LS");
        contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, "g64");
        contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, 30);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 200);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 200);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 400);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 400);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, 100);
        contentValues.put(COLUMN_INT_ELEMENT_THICK, 5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0x00FFFFFF);
        contentValues.put(COLUMN_INT_ELEMENT_MODE, 0);
        Map<String, Object> extraAttrs = new HashMap<>();
        extraAttrs.put(EXTRA_ATTR_TRIGGER_KEY, ElementController.SPECIAL_KEY_NULL);
        extraAttrs.put(EXTRA_ATTR_TRIGGER_RANGE, 0);
        contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, new Gson().toJson(extraAttrs));
        return contentValues;


    }

}