package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.DialogInterface;
import android.preference.PreferenceManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CompoundButton;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.app.AlertDialog;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;
import com.limelight.utils.AppSwitchStyle;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.advance_setting.CrownConfigChoiceFactory;
import com.limelight.binding.input.advance_setting.CrownConfigChoice;
import com.limelight.binding.input.advance_setting.DirectConfigAction;
import com.limelight.binding.input.advance_setting.DirectConfigSwitchState;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.config.PageConfigController;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.utils.AppDialogStyler;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElementController {

    // 空按键
    private static final String SPECIAL_KEY_NULL = "null";
    // 手柄左摇杆
    private static final String SPECIAL_KEY_GAMEPAD_LEFT_STICK = "LS";
    // 手柄右摇杆
    private static final String SPECIAL_KEY_GAMEPAD_RIGHT_STICK = "RS";
    // 手柄左触发器
    private static final String SPECIAL_KEY_GAMEPAD_LEFT_TRIGGER = "lt";
    // 手柄右触发器
    private static final String SPECIAL_KEY_GAMEPAD_RIGHT_TRIGGER = "rt";
    // 滚轮上滚
    private static final String SPECIAL_KEY_MOUSE_SCROLL_UP = "SU";
    // 滚轮下滚
    private static final String SPECIAL_KEY_MOUSE_SCROLL_DOWN = "SD";
    private static final String SPECIAL_KEY_MOUSE_MODE_SWITCH = "MMS";
    private static final String SPECIAL_KEY_CLASSIC_MOUSE_SWITCH = "CMS"; // 经典鼠标
    private static final String SPECIAL_KEY_TRACKPAD_MODE = "TPM";         // 触控板
    private static final String SPECIAL_KEY_MULTI_TOUCH_MODE = "MTM";      // 多点触控
    private static final String SPECIAL_KEY_MOUSE_ENABLE_SWITCH = "MES";
    private static final String SPECIAL_KEY_PC_KEYBOARD_SWITCH = "PKS";
    private static final String SPECIAL_KEY_ANDROID_KEYBOARD_SWITCH = "AKS";
    // 切换配置
    private static final String SPECIAL_KEY_CONFIG_SWITCH = "CSW";
    private static final String SPECIAL_KEY_PAN_ZOOM_MODE = "PZM";
    private static final String SPECIAL_KEY_OPEN_GAME_MENU = "OGM";
    private static final String SPECIAL_KEY_EDIT_MODE_SWITCH = "EMS"; // 编辑模式
    private static final String SPECIAL_KEY_MOUSE_MOVE_ONLY = "MMO"; // 仅鼠标移动
    private static final String SPECIAL_KEY_DS5_TOUCHPAD_CYCLE = "DSC"; // DS5 触摸板 开/关



    public interface SendEventHandler {
        void sendEvent(boolean down);

        /**
         * Delivers a DigitalSwitchButton transition and identifies cleanup releases.
         * Existing handlers retain their normal press/release behavior by default.
         */
        default void sendDigitalSwitchEvent(boolean down, boolean cleanup) {
            sendEvent(down);
        }

        void sendEvent(int analog1, int analog2);
    }


    public enum Mode {
        Normal,
        Edit,
        Select
    }

    public static class GamepadInputContext {
        public short inputMap = 0x0000;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }


    private final Context context;
    private final Game game;
    private final Handler handler;
    private Toast currentToast;
    private final DirectConfigSwitchState directConfigSwitch = new DirectConfigSwitchState();
    private Element dispatchingElement;

    private final ControllerManager controllerManager;
    private final PageDeviceController pageDeviceController;

    private GamepadInputContext gamepadInputContext = new GamepadInputContext();


    private final List<Element> elements = new ArrayList<>();
    private List<Long> elementIds;
    private Map<Short, Runnable> keyEventRunnableMap = new HashMap<>();
    private Map<Integer, Runnable> mouseEventRunnableMap = new HashMap<>();
    private FrameLayout elementsLayout;
    private Mode mode = Mode.Normal;
    private SuperPageLayout pageEdit;
    private SuperPageLayout lastElementSettingPage;
    private final int bottomViewAmount;
    private EditGridView editGridView;
    private int editGridWidth = 1;
    private long currentConfigId;
    private Element selectedElement;
    private boolean gameVibrator = false;
    private boolean buttonVibrator = false;

    // 滚轮按住事件管理
    private Map<Integer, Runnable> mouseScrollRunnableMap = new HashMap<>();
    private static final int MOUSE_SCROLL_INITIAL_DELAY = 150; // 初始延迟（毫秒）
    private static int MOUSE_SCROLL_REPEAT_INTERVAL = 100; // 重复间隔（毫秒）

    private static final String CROWN_DETAIL_ACTION_BAR_TAG = "crown_detail_action_bar";
    private static final String CROWN_INSPECTOR_COMPACT_TAG = "crown_inspector_compact";
    private static final String PREF_CROWN_ALIGNMENT_SNAP_ENABLED = "crown_alignment_snap_enabled";
    private static final int ALIGNMENT_SNAP_THRESHOLD_DP = 8;

    static class SnapResult {
        public final int centralX;
        public final int centralY;

        private SnapResult(int centralX, int centralY) {
            this.centralX = centralX;
            this.centralY = centralY;
        }
    }

    private static class AxisSnap {
        private int center;
        private int guideOffset;
        private int distance = Integer.MAX_VALUE;
        private boolean matched = false;

        AxisSnap(int center) {
            this.center = center;
        }

        void consider(int candidateCenter, int movingOffset, int targetGuide, int threshold) {
            int candidateGuide = candidateCenter + movingOffset;
            int candidateDistance = Math.abs(candidateGuide - targetGuide);
            if (candidateDistance > threshold || candidateDistance >= distance) {
                return;
            }

            center = targetGuide - movingOffset;
            guideOffset = movingOffset;
            distance = candidateDistance;
            matched = true;
        }

        int getCenter() {
            return center;
        }

        float getGuide(int finalCenter) {
            return matched ? finalCenter + guideOffset : EditGridView.NO_GUIDE;
        }
    }

    public static void setMouseScrollRepeatInterval(int interval) {
        MOUSE_SCROLL_REPEAT_INTERVAL = interval;
    }

    public SuperPagesController getSuperPagesController() {
        return controllerManager.getSuperPagesController();
    }


    /**
     * 隐藏所有虚拟按键的容器。
     */
    public void hideAllElementsForTest() {
        if (elementsLayout != null) {
            elementsLayout.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * 显示所有虚拟按键的容器。
     */
    public void showAllElementsForTest() {
        if (elementsLayout != null) {
            elementsLayout.setVisibility(View.VISIBLE);
        }
    }

    // 拖动编辑开关变量
    private boolean dragEditEnabled = true;

    // 设置拖动编辑开关的方法
    public void setDragEditEnabled(boolean enabled) {
        this.dragEditEnabled = enabled;
    }

    void setSelectedElement(Element element) {
        if (selectedElement == element) return;
        Element previous = selectedElement;
        selectedElement = element;
        if (previous != null) previous.invalidate();
        if (element != null) element.invalidate();
    }

    boolean isSelectedElement(Element element) {
        return selectedElement == element;
    }

    // 获取拖动编辑开关状态的方法
    public boolean isDragEditEnabled() {
        return dragEditEnabled;
    }

    private boolean alignmentSnapEnabled = true;

    private void setAlignmentSnapEnabled(boolean enabled) {
        alignmentSnapEnabled = enabled;
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(PREF_CROWN_ALIGNMENT_SNAP_ENABLED, enabled)
                .apply();
        if (!enabled) {
            clearAlignmentGuides();
        }
    }


    public void showToast(String message) {
        if (currentToast != null) {
            currentToast.cancel();
        }
        currentToast = Toast.makeText(context, message, Toast.LENGTH_SHORT);
        currentToast.show();
    }


    public ElementController(ControllerManager controllerManager, FrameLayout layout, final Context context) {
        this.elementsLayout = layout;
        this.context = context;
        this.game = (Game) context;
        this.controllerManager = controllerManager;
        this.pageDeviceController = controllerManager.getPageDeviceController();
        this.handler = new Handler(Looper.getMainLooper());
        this.pageEdit = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_edit, null);
        ((TextView) pageEdit.findViewById(R.id.crown_page_title_text))
                .setText(R.string.game_menu_edit_mode);
        ((TextView) pageEdit.findViewById(R.id.crown_page_subtitle))
                .setText(R.string.crown_edit_toolbar_hint);
        pageEdit.findViewById(R.id.crown_page_subtitle).setVisibility(View.VISIBLE);
        this.editGridView = new EditGridView(context);
        this.bottomViewAmount = elementsLayout.getChildCount();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.alignmentSnapEnabled = preferences.getBoolean(PREF_CROWN_ALIGNMENT_SNAP_ENABLED, true);
        initEditPage();
    }

    private void initEditPage() {
        pageEdit.findViewById(R.id.page_edit_exit_edit_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitCrownEditMode();
            }
        });
        pageEdit.findViewById(R.id.page_edit_auto_color_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyCrownAutoColorsToAll();
            }
        });
        ((NumberSeekbar) pageEdit.findViewById(R.id.page_edit_edit_grid_width)).setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                editGridWidth = progress;
                editGridView.setEditGridWidth(editGridWidth);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        bindAddElementButton(R.id.page_edit_add_digital_common_button,
                DigitalCommonButton.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_digital_switch_button,
                DigitalSwitchButton.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_digital_movable_button,
                DigitalMovableButton.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_pad,
                DigitalPad.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_analog_stick,
                AnalogStick.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_digital_stick,
                DigitalStick.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_invisible_analog_stick,
                InvisibleAnalogStick.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_invisible_digital_stick,
                InvisibleDigitalStick.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_simplify_performance,
                SimplifyPerformance.getInitialInfo(context));
        bindAddElementButton(R.id.page_edit_add_digital_combine_button,
                DigitalCombineButton.getInitialInfo(context));
        bindAddElementButton(R.id.page_edit_add_wheel_pad,
                WheelPad.getInitialInfo());
        bindAddElementButton(R.id.page_edit_add_group_button,
                GroupButton.getInitialInfo());
        SwitchCompat dragEditSwitch = pageEdit.findViewById(R.id.page_edit_drag_edit_switch);
        if (dragEditSwitch != null) {
            AppSwitchStyle.apply(dragEditSwitch);
            // 设置初始状态
            dragEditSwitch.setChecked(true); // 默认启用长按移动按键

            // 添加开关监听器
            dragEditSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setDragEditEnabled(isChecked);
                    String message = isChecked ? context.getString(R.string.crown_drag_hold) : context.getString(R.string.crown_drag_direct);
                    showToast(message);
                }
            });
        }
        SwitchCompat alignmentSnapSwitch = pageEdit.findViewById(R.id.page_edit_alignment_snap_switch);
        if (alignmentSnapSwitch != null) {
            AppSwitchStyle.apply(alignmentSnapSwitch);
            alignmentSnapSwitch.setChecked(alignmentSnapEnabled);
            alignmentSnapSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setAlignmentSnapEnabled(isChecked);
                    int messageResId = isChecked
                            ? R.string.crown_alignment_snap_enabled
                            : R.string.crown_alignment_snap_disabled;
                    showToast(context.getString(messageResId));
                }
            });
        }
    }

    private void bindAddElementButton(int viewId, ContentValues template) {
        View view = pageEdit.findViewById(viewId);
        if (view == null) return;
        view.setOnClickListener(v -> addElement(new ContentValues(template)));
    }

    private void exitCrownEditMode() {
        changeMode(Mode.Normal);
        controllerManager.pageSuperMenuController.open();
        SuperPagesController pages = controllerManager.getSuperPagesController();
        if (pages.getPageNow() == pages.getPageNull()) {
            handler.post(pages::returnOperation);
        }
        if (((Game) context).getCurrentBackKeyMenuMode() == Game.BackKeyMenuMode.NO_MENU) {
            ((Game) context).setcurrentBackKeyMenu(Game.BackKeyMenuMode.GAME_MENU);
        } else {
            ((Game) context).setcurrentBackKeyMenu(Game.BackKeyMenuMode.CROWN_MODE);
        }
    }


    protected Handler getHandler() {
        return handler;
    }

    void beginElementEvent(Element element, int action) {
        dispatchingElement = element;
        if (action == MotionEvent.ACTION_DOWN) directConfigSwitch.begin(element);
    }

    void endElementEvent(Element element, int action) {
        dispatchingElement = null;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            directConfigSwitch.finish(element, action == MotionEvent.ACTION_CANCEL);
        }
    }

    public void cancelDirectConfigSwitch() {
        directConfigSwitch.reset();
    }

    public void finishStreamTouch(int action) {
        if (action == MotionEvent.ACTION_CANCEL) {
            directConfigSwitch.reset();
        } else if (action == MotionEvent.ACTION_UP) {
            Long target = directConfigSwitch.takeCompletedTarget();
            if (target != null && !game.isFinishing() && !game.isDestroyed()
                    && mode == Mode.Normal && elementsLayout.isAttachedToWindow()) {
                controllerManager.getPageConfigController().switchDirectlyToConfig(target);
            }
        }
    }


    public void loadAllElement(Long configId) {
        directConfigSwitch.reset();
        currentConfigId = configId;
        removeAllElementsOnScreen();
        elementIds = controllerManager.getSuperConfigDatabaseHelper().queryAllElementIds(configId);

        // 用于在第二阶段链接关系的 GroupButton 列表
        List<GroupButton> groupButtonsToLink = new ArrayList<>();

        // --- 阶段一：创建所有 Element 对象 ---
        // 遍历所有 elementId，不区分类型，统一调用 loadElement 创建对象
        for (Long elementId : elementIds) {
            Element newElement = loadElement(elementId);

            // 如果创建的是一个 GroupButton，将其添加到待链接列表
            if (newElement instanceof GroupButton) {
                groupButtonsToLink.add((GroupButton) newElement);
            }
        }

        // --- 阶段二：链接 GroupButton 的子元素 ---
        // 此时，`elements` 列表已经包含了当前配置下的所有 Element 对象
        for (GroupButton gb : groupButtonsToLink) {
            // 调用我们将在 GroupButton 类中添加的新方法
            gb.linkChildElements(elements);
        }
    }

    protected Element addElement(ContentValues contentValues) {
        Long configId = controllerManager.getPageConfigController().getCurrentConfigId();
        Long elementId = System.currentTimeMillis();
        contentValues.put(Element.COLUMN_LONG_CONFIG_ID, configId);
        contentValues.put(Element.COLUMN_LONG_ELEMENT_ID, elementId);
        controllerManager.getSuperConfigDatabaseHelper().insertElement(contentValues);

        return loadElement(elementId);
    }

    protected void updateElement(long elementId, ContentValues contentValues) {
        controllerManager.getSuperConfigDatabaseHelper().updateElement(currentConfigId, elementId, contentValues);
    }

    protected void deleteElement(Element element) {
        controllerManager.getSuperConfigDatabaseHelper().deleteElement(currentConfigId, element.elementId);
        if (elements.contains(element)) {
            element.releaseLatchedInput();
            elementsLayout.removeView(element);
            elements.remove(element);
        }
    }

    private void removeAllElementsOnScreen() {
        for (Element element : elements) {
            element.releaseLatchedInput();
            elementsLayout.removeView(element);
        }
        elements.clear();
    }

    private Element loadElement(Long elementId) {
        Map<String, Object> attributesMap = controllerManager.getSuperConfigDatabaseHelper().queryAllElementAttributes(currentConfigId, elementId);
        int type = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_TYPE)).intValue();
        Element element = null;
        switch (type) {
            case Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                element = new DigitalCommonButton(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                element = new DigitalSwitchButton(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                element = new DigitalMovableButton(attributesMap,
                        this,
                        controllerManager.getTouchController(),
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_GROUP_BUTTON:
                element = new GroupButton(attributesMap,
                        this,
                        pageDeviceController,
                        controllerManager.getSuperPagesController(),
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_PAD:
                element = new DigitalPad(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_ANALOG_STICK:
                element = new AnalogStick(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_STICK:
                element = new DigitalStick(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                element = new InvisibleAnalogStick(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                element = new InvisibleDigitalStick(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_SIMPLIFY_PERFORMANCE:
                element = new SimplifyPerformance(attributesMap,
                        this,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                element = new DigitalCombineButton(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_WHEEL_PAD:
                element = new WheelPad(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            default:
                element = new DigitalCommonButton(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
        }
        int elementWidth = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_WIDTH)).intValue();
        int elementHeight = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_HEIGHT)).intValue();
        int elementCentralX = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_CENTRAL_X)).intValue();
        int elementCentralY = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_CENTRAL_Y)).intValue();
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(elementWidth, elementHeight);
        layoutParams.leftMargin = elementCentralX - elementWidth / 2;
        layoutParams.topMargin = elementCentralY - elementHeight / 2;

        //对element的层级进行排序
        for (int i = 0; i <= elements.size(); i++) {
            if (i == elements.size()) {
                elements.add(i, element);
                elementsLayout.addView(element, i + bottomViewAmount, layoutParams);
                break;
            }
            Element elementExist = elements.get(i);
            if (elementExist.elementId + ((long) elementExist.layer << 48) > element.elementId + ((long) element.layer << 48)) {
                elements.add(i, element);
                elementsLayout.addView(element, i + bottomViewAmount, layoutParams);
                break;
            }
        }

        //限制element的位置范围
        element.setElementHeight(element.getElementHeight());
        element.setElementWidth(element.getElementWidth());

        return element;
    }

    protected void adjustLayer(Element element) {

        int elementWidth = element.getElementWidth();
        int elementHeight = element.getElementHeight();
        int elementCentralX = element.getElementCentralX();
        int elementCentralY = element.getElementCentralY();


        elementsLayout.removeView(element);
        elements.remove(element);

        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(elementWidth, elementHeight);
        layoutParams.leftMargin = elementCentralX - elementWidth / 2;
        layoutParams.topMargin = elementCentralY - elementHeight / 2;
        //对element的层级进行排序
        for (int i = 0; i <= elements.size(); i++) {
            if (i == elements.size()) {
                elements.add(i, element);
                elementsLayout.addView(element, i + bottomViewAmount, layoutParams);
                break;
            }
            Element elementExist = elements.get(i);
            if (elementExist.elementId + ((long) elementExist.layer << 48) > element.elementId + ((long) element.layer << 48)) {
                elements.add(i, element);
                elementsLayout.addView(element, i + bottomViewAmount, layoutParams);
                break;
            }
        }


    }

    protected int editGridHandle(int position) {
        return position - position % editGridWidth;
    }

    protected SnapResult snapElementPosition(Element movingElement, int candidateCentralX, int candidateCentralY) {
        int snappedCentralX = editGridHandle(candidateCentralX);
        int snappedCentralY = editGridHandle(candidateCentralY);
        if (mode != Mode.Edit || !alignmentSnapEnabled || movingElement == null || elementsLayout == null) {
            clearAlignmentGuides();
            return new SnapResult(snappedCentralX, snappedCentralY);
        }

        int threshold = dp(ALIGNMENT_SNAP_THRESHOLD_DP);
        AxisSnap xSnap = new AxisSnap(snappedCentralX);
        AxisSnap ySnap = new AxisSnap(snappedCentralY);

        int halfWidth = movingElement.getElementWidth() / 2;
        int halfHeight = movingElement.getElementHeight() / 2;
        int[] movingXOffsets = new int[]{-halfWidth, 0, halfWidth};
        int[] movingYOffsets = new int[]{-halfHeight, 0, halfHeight};

        int layoutWidth = elementsLayout.getWidth();
        int layoutHeight = elementsLayout.getHeight();
        if (layoutWidth > 0) {
            considerAxisGuides(xSnap, snappedCentralX, movingXOffsets, new int[]{0, layoutWidth / 2, layoutWidth}, threshold);
        }
        if (layoutHeight > 0) {
            considerAxisGuides(ySnap, snappedCentralY, movingYOffsets, new int[]{0, layoutHeight / 2, layoutHeight}, threshold);
        }

        for (Element element : elements) {
            if (element == movingElement || element.getVisibility() != View.VISIBLE) {
                continue;
            }

            int elementHalfWidth = element.getElementWidth() / 2;
            int elementHalfHeight = element.getElementHeight() / 2;
            int elementCentralX = element.getElementCentralX();
            int elementCentralY = element.getElementCentralY();
            considerAxisGuides(
                    xSnap,
                    snappedCentralX,
                    movingXOffsets,
                    new int[]{elementCentralX - elementHalfWidth, elementCentralX, elementCentralX + elementHalfWidth},
                    threshold
            );
            considerAxisGuides(
                    ySnap,
                    snappedCentralY,
                    movingYOffsets,
                    new int[]{elementCentralY - elementHalfHeight, elementCentralY, elementCentralY + elementHalfHeight},
                    threshold
            );
        }

        int finalCentralX = editGridHandle(xSnap.getCenter());
        int finalCentralY = editGridHandle(ySnap.getCenter());
        updateAlignmentGuides(xSnap.getGuide(finalCentralX), ySnap.getGuide(finalCentralY));
        return new SnapResult(finalCentralX, finalCentralY);
    }

    protected void clearAlignmentGuides() {
        if (editGridView != null) {
            editGridView.clearAlignmentGuides();
        }
    }

    private void considerAxisGuides(AxisSnap snap,
                                    int movingCenter,
                                    int[] movingOffsets,
                                    int[] targetGuides,
                                    int threshold) {
        for (int movingOffset : movingOffsets) {
            for (int targetGuide : targetGuides) {
                snap.consider(movingCenter, movingOffset, targetGuide, threshold);
            }
        }
    }

    private void updateAlignmentGuides(float verticalGuide, float horizontalGuide) {
        if (editGridView != null) {
            editGridView.setAlignmentGuides(verticalGuide, horizontalGuide);
        }
    }


    public void toggleInfoPage(SuperPageLayout elementSettingPage) {
        if (elementSettingPage == controllerManager.getSuperPagesController().getPageNow()) {
            controllerManager.getSuperPagesController().openNewPage(
                    controllerManager.getSuperPagesController().getPageNull());
        } else {
            addCrownInspectorPreview(elementSettingPage, selectedElement);
            applyCrownDetailStyle(elementSettingPage);
            compactCrownInspector(elementSettingPage);
            promoteCrownDetailActions(elementSettingPage);
            positionCrownDetailPageAwayFromOwner(elementSettingPage);
            controllerManager.getSuperPagesController().openNewPage(elementSettingPage);
            elementSettingPage.setPageReturnListener(new SuperPageLayout.ReturnListener() {
                @Override
                public void returnCallBack() {
                    controllerManager.getSuperPagesController().openNewPage(controllerManager.getSuperPagesController().getPageNull());
                }
            });
        }
    }

    private void addCrownInspectorPreview(SuperPageLayout page, Element element) {
        if (element == null || page.findViewWithTag("crown_inspector_preview") != null) {
            return;
        }

        ScrollView scrollView = findCrownDetailScrollView(page);
        if (scrollView == null || !(scrollView.getChildAt(0) instanceof ViewGroup)) {
            return;
        }
        ViewGroup content = (ViewGroup) scrollView.getChildAt(0);
        // Design L4: a full-width 84dp dark canvas with the live element centered.
        FrameLayout card = new FrameLayout(context);
        card.setTag("crown_inspector_preview");
        card.setBackgroundResource(R.drawable.crown_inspector_preview_bg);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        CrownInspectorPreviewView preview = new CrownInspectorPreviewView(context);
        preview.bind(element);
        card.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ViewGroup.MarginLayoutParams cardParams = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        cardParams.bottomMargin = dp(10);
        content.addView(card, Math.min(1, content.getChildCount()), cardParams);
    }

    /**
     * The element pages remain the source of truth for their bindings, but their
     * original XML is intentionally verbose. Reflow consecutive numeric controls
     * into compact two-column rows at runtime so every element type gets the same
     * Inspector density without duplicating 13 page layouts.
     */
    private void compactCrownInspector(SuperPageLayout page) {
        ScrollView scrollView = findCrownDetailScrollView(page);
        if (scrollView == null || CROWN_INSPECTOR_COMPACT_TAG.equals(scrollView.getTag())
                || !(scrollView.getChildAt(0) instanceof ViewGroup)) {
            return;
        }

        scrollView.setClipToPadding(false);
        scrollView.setFillViewport(false);
        scrollView.setPadding(
                scrollView.getPaddingLeft(),
                Math.max(scrollView.getPaddingTop(), dp(8)),
                scrollView.getPaddingRight(),
                Math.max(scrollView.getPaddingBottom(), dp(82))
        );
        compactNumberSeekbars((ViewGroup) scrollView.getChildAt(0));
        normalizeCrownInspectorTypography((ViewGroup) scrollView.getChildAt(0));
        scrollView.setTag(CROWN_INSPECTOR_COMPACT_TAG);
    }

    private void compactNumberSeekbars(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount();) {
            View child = parent.getChildAt(i);
            if (child instanceof NumberSeekbar) {
                int start = i;
                List<NumberSeekbar> seekbars = new ArrayList<>();
                while (i < parent.getChildCount() && parent.getChildAt(i) instanceof NumberSeekbar) {
                    seekbars.add((NumberSeekbar) parent.getChildAt(i));
                    i++;
                }
                for (NumberSeekbar seekbar : seekbars) {
                    seekbar.setCompactMode(true);
                }

                if (seekbars.size() >= 2) {
                    parent.removeViews(start, seekbars.size());
                    int insertAt = start;
                    for (int offset = 0; offset < seekbars.size(); offset += 2) {
                        LinearLayout row = new LinearLayout(context);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
                        if (insertAt > 0) {
                            rowParams.topMargin = dp(2);
                        }

                        NumberSeekbar first = seekbars.get(offset);
                        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(
                                0, dp(40), 1f);
                        firstParams.setMarginEnd(dp(4));
                        row.addView(first, firstParams);

                        if (offset + 1 < seekbars.size()) {
                            NumberSeekbar second = seekbars.get(offset + 1);
                            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                                    0, dp(40), 1f);
                            secondParams.setMarginStart(dp(4));
                            row.addView(second, secondParams);
                        }
                        parent.addView(row, insertAt++ == start ? start : insertAt - 1, rowParams);
                    }
                    i = start + (seekbars.size() + 1) / 2;
                }
                continue;
            }

            if (child instanceof ViewGroup && !"crown_inspector_preview".equals(child.getTag())) {
                compactNumberSeekbars((ViewGroup) child);
            }
            i++;
        }
    }

    private void normalizeCrownInspectorTypography(ViewGroup parent) {
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView && !(child instanceof NumberSeekbar)) {
                TextView text = (TextView) child;
                float sizeSp = text.getTextSize() / Math.max(0.1f, scaledDensity);
                if (sizeSp >= 17f) {
                    text.setTextSize(16f);
                } else if (text.getTypeface() != null && text.getTypeface().isBold() && sizeSp >= 13f) {
                    text.setTextSize(12.5f);
                } else if (sizeSp >= 12f) {
                    text.setTextSize(11.5f);
                }
                text.setIncludeFontPadding(false);
            }
            if (child instanceof ViewGroup && !"crown_inspector_preview".equals(child.getTag())) {
                normalizeCrownInspectorTypography((ViewGroup) child);
            }
        }
    }

    private void positionCrownDetailPageAwayFromOwner(SuperPageLayout page) {
        Element owner = getCrownAutoColorOwner(page);
        if (owner == null || owner.getWidth() <= 0) {
            return;
        }

        int[] location = new int[2];
        owner.getLocationOnScreen(location);
        float ownerCenterX = location[0] + owner.getWidth() / 2f;
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        SuperPagesController.BoxPosition targetPosition = ownerCenterX > screenWidth / 2f
                ? SuperPagesController.BoxPosition.Left
                : SuperPagesController.BoxPosition.Right;
        controllerManager.getSuperPagesController().setPosition(targetPosition);
    }

    private void promoteCrownDetailActions(SuperPageLayout page) {
        if (page.findViewWithTag(CROWN_DETAIL_ACTION_BAR_TAG) != null) {
            return;
        }

        List<Button> actionButtons = new ArrayList<>();
        collectCrownActionButtons(page, actionButtons);
        Element autoColorOwner = getCrownAutoColorOwner(page);
        boolean supportsAutoColor = autoColorOwner != null && autoColorOwner.supportsCrownAutoColors();
        Button copyButton = findCrownActionButton(actionButtons, "_copy");
        Button deleteButton = findCrownActionButton(actionButtons, "_delete");
        if (copyButton == null && deleteButton == null && !supportsAutoColor) {
            return;
        }

        LinearLayout actionBar = new LinearLayout(context);
        actionBar.setTag(CROWN_DETAIL_ACTION_BAR_TAG);
        actionBar.setGravity(Gravity.CENTER_VERTICAL);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setPadding(dp(12), dp(10), dp(12), dp(14));
        actionBar.setBackgroundResource(R.drawable.crown_panel_footer_bg);

        ScrollView detailScroll = findCrownDetailScrollView(page);
        if (detailScroll != null) {
            detailScroll.setClipToPadding(false);
            detailScroll.setPadding(
                    detailScroll.getPaddingLeft(),
                    detailScroll.getPaddingTop(),
                    detailScroll.getPaddingRight(),
                    detailScroll.getPaddingBottom() + dp(78)
            );
        }

        FrameLayout.LayoutParams actionBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        actionBar.setElevation(dp(6));

        if (supportsAutoColor) {
            ImageButton autoColorButton = createCrownIconActionButton(
                    R.drawable.phc_action_auto_color,
                    context.getString(R.string.crown_auto_color_action),
                    v -> applyCrownAutoColors(autoColorOwner, page)
            );
            actionBar.addView(autoColorButton, createCrownIconActionParams());
        }

        // Design L4: 复制控件 stays as a labeled ghost action, 完成 closes the page,
        // and delete moves to a danger icon next to the page title.
        if (copyButton != null) {
            LinearLayout copyAction = createCrownTextActionButton(
                    copyButton.getText(), false,
                    v -> copyButton.performClick());
            actionBar.addView(copyAction, new LinearLayout.LayoutParams(
                    0, dp(42), 1f));
            actionBar.addView(new View(context), new LinearLayout.LayoutParams(dp(8), 1));
            copyButton.setVisibility(View.GONE);
            hideActionRowIfNeeded(copyButton);
        }

        LinearLayout doneAction = createCrownTextActionButton(
                context.getString(R.string.crown_edit_done), true,
                v -> {
                    controllerManager.getSuperPagesController()
                            .openNewPage(controllerManager.getSuperPagesController().getPageNull());
                });
        actionBar.addView(doneAction, new LinearLayout.LayoutParams(0, dp(42), 1.2f));

        if (deleteButton != null) {
            addCrownHeaderDeleteButton(page, deleteButton);
            deleteButton.setVisibility(View.GONE);
            hideActionRowIfNeeded(deleteButton);
        }

        page.addView(actionBar, actionBarParams);
    }

    private Button findCrownActionButton(List<Button> actionButtons, String idSuffix) {
        for (Button button : actionButtons) {
            String idName;
            try {
                idName = context.getResources().getResourceEntryName(button.getId());
            } catch (Resources.NotFoundException e) {
                continue;
            }
            if (idName.endsWith(idSuffix)) {
                return button;
            }
        }
        return null;
    }

    private LinearLayout createCrownTextActionButton(CharSequence label, boolean primary,
                                                     View.OnClickListener listener) {
        LinearLayout actionButton = new LinearLayout(context);
        actionButton.setGravity(Gravity.CENTER);
        actionButton.setOrientation(LinearLayout.HORIZONTAL);
        actionButton.setBackgroundResource(primary
                ? R.drawable.crown_primary_button_bg
                : R.drawable.crown_ghost_button_bg);
        actionButton.setClickable(true);
        actionButton.setFocusable(true);
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(13f);
        if (primary) {
            text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        }
        text.setTextColor(context.getResources().getColor(primary
                ? R.color.crown_on_accent
                : R.color.crown_text_secondary));
        actionButton.addView(text);
        actionButton.setOnClickListener(listener);
        return actionButton;
    }

    private void addCrownHeaderDeleteButton(SuperPageLayout page, Button originalDelete) {
        View titleText = page.findViewById(R.id.crown_page_title_text);
        if (!(titleText != null && titleText.getParent() instanceof ViewGroup)
                || page.findViewWithTag("crown_header_delete") != null) {
            return;
        }
        ViewGroup header = (ViewGroup) titleText.getParent();
        ImageButton deleteButton = createCrownIconActionButton(
                R.drawable.phc_action_trash,
                originalDelete.getContentDescription() != null
                        ? originalDelete.getContentDescription()
                        : originalDelete.getText(),
                v -> originalDelete.performClick()
        );
        deleteButton.setTag("crown_header_delete");
        deleteButton.setColorFilter(
                context.getResources().getColor(R.color.app_action_sheet_danger));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
        params.setMarginStart(dp(8));
        header.addView(deleteButton, params);
    }

    private ScrollView findCrownDetailScrollView(View view) {
        if (view instanceof ScrollView) {
            return (ScrollView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView result = findCrownDetailScrollView(group.getChildAt(i));
            if (result != null) return result;
        }
        return null;
    }

    private ImageButton createCrownIconActionButton(int iconResId, CharSequence contentDescription, View.OnClickListener listener) {
        ImageButton actionButton = new ImageButton(context);
        actionButton.setBackgroundResource(R.drawable.crown_action_icon_button_bg);
        actionButton.setColorFilter(context.getResources().getColor(R.color.crown_text_primary));
        actionButton.setContentDescription(contentDescription);
        actionButton.setImageResource(iconResId);
        actionButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        actionButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        actionButton.setOnClickListener(listener);
        return actionButton;
    }

    private LinearLayout.LayoutParams createCrownIconActionParams() {
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                dp(48),
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        buttonParams.setMarginStart(dp(4));
        return buttonParams;
    }

    private Element getCrownAutoColorOwner(SuperPageLayout page) {
        Object owner = page.getTag(R.id.crown_auto_color_owner);
        if (owner instanceof Element) {
            return (Element) owner;
        }
        return null;
    }

    private void applyCrownAutoColors(Element element, SuperPageLayout page) {
        if (!(context instanceof Game)) {
            Toast.makeText(context, R.string.crown_auto_color_no_frame, Toast.LENGTH_SHORT).show();
            return;
        }

        CrownScreenColorSampler.sample((Game) context, new CrownScreenColorSampler.Callback() {
            @Override
            public void onPalette(CrownAutoColorPalette palette) {
                if (element.applyCrownAutoColors(palette, page)) {
                    Toast.makeText(context, R.string.crown_auto_color_done, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, R.string.crown_auto_color_failed, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyCrownAutoColorsToAll() {
        if (!(context instanceof Game)) {
            Toast.makeText(context, R.string.crown_auto_color_no_frame, Toast.LENGTH_SHORT).show();
            return;
        }

        CrownScreenColorSampler.sample((Game) context, new CrownScreenColorSampler.Callback() {
            @Override
            public void onPalette(CrownAutoColorPalette palette) {
                int appliedCount = 0;
                for (Element element : elements) {
                    if (!element.supportsCrownAutoColors()) {
                        continue;
                    }
                    if (element.applyCrownAutoColors(palette, null)) {
                        appliedCount++;
                    }
                }

                if (appliedCount > 0) {
                    Toast.makeText(
                            context,
                            context.getString(R.string.crown_auto_color_all_done, appliedCount),
                            Toast.LENGTH_SHORT
                    ).show();
                } else {
                    Toast.makeText(context, R.string.crown_auto_color_no_supported_elements, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int getCrownActionIconRes(String idName) {
        if (idName.endsWith("_copy")) {
            return R.drawable.phc_action_copy;
        }
        if (idName.endsWith("_delete")) {
            return R.drawable.phc_action_trash;
        }
        if (idName.endsWith("_reset")) {
            return R.drawable.phc_action_reset;
        }
        if (idName.endsWith("_ensure")) {
            return R.drawable.phc_action_check;
        }
        return R.drawable.phc_settings;
    }

    private void collectCrownActionButtons(View view, List<Button> actionButtons) {
        if (view instanceof Button && isCrownDetailActionButton(view)) {
            actionButtons.add((Button) view);
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                collectCrownActionButtons(viewGroup.getChildAt(i), actionButtons);
            }
        }
    }

    private boolean isCrownDetailActionButton(View view) {
        if (view.getId() == View.NO_ID) {
            return false;
        }

        String idName;
        try {
            idName = context.getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException e) {
            return false;
        }
        return idName.endsWith("_copy")
                || idName.endsWith("_delete")
                || idName.endsWith("_reset")
                || idName.endsWith("_ensure");
    }

    private void hideActionRowIfNeeded(Button originalButton) {
        ViewParent parent = originalButton.getParent();
        if (!(parent instanceof ViewGroup)) {
            return;
        }

        ViewGroup row = (ViewGroup) parent;
        if (containsVisibleActionButton(row)) {
            return;
        }
        if (containsVisibleNonActionChild(row)) {
            return;
        }

        row.setVisibility(View.GONE);
        ViewParent sectionParent = row.getParent();
        if (sectionParent instanceof ViewGroup) {
            ViewGroup section = (ViewGroup) sectionParent;
            if (hasOnlyTextAndHiddenActionRows(section)) {
                section.setVisibility(View.GONE);
            }
        }
    }

    private boolean containsVisibleActionButton(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof Button && child.getVisibility() == View.VISIBLE && isCrownDetailActionButton(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsVisibleNonActionChild(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (!(child instanceof Button) || !isCrownDetailActionButton(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOnlyTextAndHiddenActionRows(ViewGroup viewGroup) {
        boolean hasHiddenActionRow = false;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child.getVisibility() == View.GONE && child instanceof ViewGroup) {
                hasHiddenActionRow = true;
                continue;
            }
            if (child instanceof TextView) {
                continue;
            }
            return false;
        }
        return hasHiddenActionRow;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void applyCrownDetailStyle(View view) {
        if (view instanceof SwitchCompat) {
            SwitchCompat switchView = (SwitchCompat) view;
            AppSwitchStyle.apply(switchView);
        } else if (view instanceof RadioButton) {
            RadioButton radioButton = (RadioButton) view;
            radioButton.setTextColor(context.getResources().getColor(R.color.crown_text_primary));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                radioButton.setButtonTintList(getColorStateList(R.color.crown_radio_button));
            }
        } else if (view instanceof Button) {
            Button button = (Button) view;
            button.setBackgroundResource(R.drawable.crown_config_action_button_bg);
            button.setTextColor(context.getResources().getColor(R.color.crown_text_primary));
        } else if (view instanceof EditText) {
            EditText editText = (EditText) view;
            editText.setTextColor(context.getResources().getColor(R.color.crown_text_primary));
            editText.setHintTextColor(context.getResources().getColor(R.color.crown_text_secondary));
        }

        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                applyCrownDetailStyle(viewGroup.getChildAt(i));
            }
        }
    }

    private ColorStateList getColorStateList(int colorResId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getResources().getColorStateList(colorResId, context.getTheme());
        }
        return context.getResources().getColorStateList(colorResId);
    }

    public SuperPageLayout getCurrentEditingPage() {
        return controllerManager.getSuperPagesController().getPageNow();
    }


    public void open() {
        SuperPagesController superPagesController = controllerManager.getSuperPagesController();
        SuperPageLayout pageNull = superPagesController.getPageNull();
        superPagesController.openNewPage(pageNull);
        pageNull.setPageReturnListener(new SuperPageLayout.ReturnListener() {
            @Override
            public void returnCallBack() {
                superPagesController.openNewPage(pageEdit);
            }
        });
        pageEdit.setPageReturnListener(new SuperPageLayout.ReturnListener() {
            @Override
            public void returnCallBack() {
                superPagesController.openNewPage(pageNull);
            }
        });
        pageEdit.post(() -> {
            View scrollView = pageEdit.findViewById(R.id.page_edit_scroll);
            if (scrollView != null) scrollView.scrollTo(0, 0);
        });
    }

    public void changeMode(Mode mode) {
        if (this.mode == mode) {
            return;
        }

        this.mode = mode;
        switch (mode) {
            case Normal:
                selectedElement = null;
                controllerManager.getTouchController().enableTouch(
                        controllerManager.getPageConfigController().isTouchEnabled());
                this.mode = Mode.Normal;
                clearAlignmentGuides();
                elementsLayout.removeView(editGridView);
                for (Element element : elements) {
                    element.invalidate();
                }
                break;
            case Edit:
                selectedElement = null;
                controllerManager.getTouchController().enableTouch(false);
                this.mode = Mode.Edit;
                FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                layoutParams.leftMargin = 0;
                layoutParams.topMargin = 0;
                elementsLayout.addView(editGridView, bottomViewAmount, layoutParams);
                for (Element element : elements) {
                    element.setEditColor(Element.EDIT_COLOR_EDIT);
                    element.invalidate();
                }
                break;
            case Select:
                clearAlignmentGuides();
                elementsLayout.removeView(editGridView);
                this.mode = Mode.Select;
                for (Element element : elements) {
                    element.setEditColor(Element.EDIT_COLOR_SELECT);
                    element.invalidate();
                }
                break;
        }
        // 无论切换到什么模式，都通知所有元素，并让它们重绘
        for (Element element : elements) {
            element.onModeChanged(mode); // <-- 调用新方法

            // 我们仍然需要根据模式设置颜色
            if (mode == Mode.Edit) {
                element.setEditColor(Element.EDIT_COLOR_EDIT);
            } else if (mode == Mode.Select) {
                element.setEditColor(Element.EDIT_COLOR_SELECT);
            }

            element.invalidate();
        }
    }

    public Mode getMode() {
        return mode;
    }

    //其他辅助方法----------------------------------
    public List<Element> getElements() {
        return elements;
    }

    /**
     * 根据提供的元素ID查找并返回对应的 Element 对象。
     *
     * @param elementId 要查找的元素的ID。
     * @return 如果找到，则返回 Element 对象；否则返回 null。
     */
    public Element findElementById(long elementId) {
        // 直接遍历类成员 'elements' 列表
        for (Element element : elements) {
            if (element.elementId.equals(elementId)) {
                return element;
            }
        }
        return null; // 如果没有找到
    }


    // 添加开始滚轮按住的方法
    public void startMouseScrollHold(int scrollDirection) {
        // 立即发送一次滚轮事件（按下事件）
        game.mouseVScroll((byte) scrollDirection);

        // 创建持续滚动的Runnable
        final int direction = scrollDirection;
        Runnable scrollRunnable = new Runnable() {
            @Override
            public void run() {
                // 发送重复的滚轮事件（按住事件）
                game.mouseVScroll((byte) direction);
                // 继续安排下一次滚动
                handler.postDelayed(this, MOUSE_SCROLL_REPEAT_INTERVAL);
            }
        };

        // 取消之前相同方向的滚动任务
        if (mouseScrollRunnableMap.containsKey(scrollDirection)) {
            handler.removeCallbacks(mouseScrollRunnableMap.get(scrollDirection));
        }

        // 存储新的滚动任务
        mouseScrollRunnableMap.put(scrollDirection, scrollRunnable);

        // 安排首次重复滚动（延迟执行，区分按下和按住）
        handler.postDelayed(scrollRunnable, MOUSE_SCROLL_INITIAL_DELAY);
    }

    // 添加停止滚轮按住的方法
    public void stopMouseScrollHold(int scrollDirection) {
        if (mouseScrollRunnableMap.containsKey(scrollDirection)) {
            handler.removeCallbacks(mouseScrollRunnableMap.get(scrollDirection));
            mouseScrollRunnableMap.remove(scrollDirection);
        }
    }

    // 辅助方法，创建一个什么都不做的安全处理器
    private SendEventHandler createEmptyHandler() {
        return new SendEventHandler() {
            @Override
            public void sendEvent(boolean down) {
            }

            @Override
            public void sendEvent(int analog1, int analog2) {
            }
        };
    }


    public SendEventHandler getSendEventHandler(String key) {
        if (key != null && key.startsWith(DirectConfigAction.PREFIX)) {
            Long target = DirectConfigAction.parse(key);
            if (target == null) return createEmptyHandler();
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    // A toggle button also emits false on a click. The touch transaction,
                    // not the logical button level, confirms this one-shot action.
                    if (dispatchingElement != null && target != currentConfigId
                            && controllerManager.getSuperConfigDatabaseHelper().queryAllConfigIds().contains(target)) {
                        directConfigSwitch.request(dispatchingElement, target);
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) { }
            };
        }
        if (key.matches("k\\d+")) {

            int keyCode = Integer.parseInt(key.substring(1));
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    sendKeyEvent(down, (short) keyCode);
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };

        } else if (key.matches("m\\d+")) {
            int mouseCode = Integer.parseInt(key.substring(1));
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    sendMouseEvent(mouseCode, down);
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };

        } else if (key.startsWith("gb")) {
            // 前缀是 "gb"，说明这一定是一个组按键的ID
            String idString = key.substring(2); // 从第3个字符开始截取 (跳过 "gb")
            try {
                long elementId = Long.parseLong(idString);
                Element element = findElementById(elementId);

                if (element instanceof GroupButton) {
                    final GroupButton groupButton = (GroupButton) element;
                    return new SendEventHandler() {
                        @Override
                        public void sendEvent(boolean down) {
                            if (down) {
                                // 在UI线程上执行按钮的动作
                                handler.post(groupButton::triggerAction);
                            }
                        }

                        @Override
                        public void sendEvent(int analog1, int analog2) {
                            // GroupButton 不处理模拟量事件
                        }
                    };
                } else {
                    // 找到了ID，但它不是GroupButton，或者在加载期间没找到（返回null）
                    // 这是一个无效的配置，返回一个安全的空处理器
                    LimeLog.warning("EventHandler:" + "Invalid GroupButton configuration for key: " + key);
                    return createEmptyHandler();
                }
            } catch (NumberFormatException e) {
                // ID部分无法解析为long，无效配置
                LimeLog.warning("EventHandler:" + "Failed to parse GroupButton ID for key: " + key + e);
                return createEmptyHandler();
            }

        } else if (key.matches("g\\d+")) {
            int padCode = Integer.parseInt(key.substring(1));
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        gamepadInputContext.inputMap |= padCode;
                    } else {
                        gamepadInputContext.inputMap &= ~padCode;
                    }
                    sendGamepadEvent();
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };

        } else if (key.equals(SPECIAL_KEY_GAMEPAD_LEFT_STICK)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {

                }

                @Override
                public void sendEvent(int analog1, int analog2) {
                    gamepadInputContext.leftStickX = (short) analog1;
                    gamepadInputContext.leftStickY = (short) analog2;
                    sendGamepadEvent();
                }
            };
        } else if (key.equals(SPECIAL_KEY_GAMEPAD_RIGHT_STICK)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {

                }

                @Override
                public void sendEvent(int analog1, int analog2) {
                    gamepadInputContext.rightStickX = (short) analog1;
                    gamepadInputContext.rightStickY = (short) analog2;
                    sendGamepadEvent();
                }
            };
        } else if (key.equals(SPECIAL_KEY_GAMEPAD_LEFT_TRIGGER)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        gamepadInputContext.leftTrigger = (byte) 0xFF;
                    } else {
                        gamepadInputContext.leftTrigger = (byte) 0;
                    }
                    sendGamepadEvent();
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_GAMEPAD_RIGHT_TRIGGER)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        gamepadInputContext.rightTrigger = (byte) 0xFF;
                    } else {
                        gamepadInputContext.rightTrigger = (byte) 0;
                    }
                    sendGamepadEvent();
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_MOUSE_SCROLL_UP)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        startMouseScrollHold(1);  // 开始向上滚动按住
                    } else {
                        stopMouseScrollHold(1);   // 停止向上滚动按住
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_MOUSE_SCROLL_DOWN)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        startMouseScrollHold(-1); // 开始向下滚动按住
                    } else {
                        stopMouseScrollHold(-1);  // 停止向下滚动按住
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_NULL)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_MOUSE_MODE_SWITCH)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        // 获取当前设置
                        boolean touchMode = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(false)));
                        boolean enhancedTouch = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(false)));

                        // 确定当前模式并切换到下一个模式
                        // 模式1: 经典鼠标模式 (touchMode=false, enhancedTouch=false)
                        // 模式2: 多点触控模式 (touchMode=false, enhancedTouch=true)
                        // 模式3: 触控板模式 (touchMode=true, enhancedTouch=false)

                        ContentValues contentValues = new ContentValues();

                        if (!touchMode && !enhancedTouch) {
                            // 当前是经典鼠标模式 -> 切换到多点触控模式
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(false));
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(true));
                            controllerManager.getTouchController().setTouchMode(false);
                            controllerManager.getTouchController().setEnhancedTouch(true);
                            showToast(context.getString(R.string.touch_mode_multitouch));
                        } else if (!touchMode && enhancedTouch) {
                            // 当前是多点触控模式 -> 切换到触控板模式
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(true));
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(false));
                            controllerManager.getTouchController().setTouchMode(true);
                            controllerManager.getTouchController().setEnhancedTouch(false);
                            showToast(context.getString(R.string.touch_mode_trackpad));
                        } else {
                            // 当前是触控板模式 -> 切换到经典鼠标模式
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(false));
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(false));
                            controllerManager.getTouchController().setTouchMode(false);
                            controllerManager.getTouchController().setEnhancedTouch(false);
                            showToast(context.getString(R.string.touch_mode_classic_mouse));
                        }

                        // 保存到数据库中
                        controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId, contentValues);
                    }

                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_CLASSIC_MOUSE_SWITCH)) {
            return switchMouseMode(false, false, context.getString(R.string.touch_mode_classic_mouse));
        } else if (key.equals(SPECIAL_KEY_TRACKPAD_MODE)) {
            return switchMouseMode(true, false, context.getString(R.string.touch_mode_trackpad));
        } else if (key.equals(SPECIAL_KEY_MULTI_TOUCH_MODE)) {
            return switchMouseMode(false, true, context.getString(R.string.touch_mode_multitouch));
        } else if (key.equals(SPECIAL_KEY_DS5_TOUCHPAD_CYCLE)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        boolean enabled = game.getScreenDs5Touchpad().toggle();
                        int toastRes = enabled ? R.string.toast_ds5_touchpad_on
                            : R.string.toast_ds5_touchpad_off;
                        showToast(context.getString(toastRes));
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_PC_KEYBOARD_SWITCH)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        game.toggleVirtualKeyboard();
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_ANDROID_KEYBOARD_SWITCH)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        game.toggleKeyboard();
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_CONFIG_SWITCH)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (!down) {
                        return;
                    }

                    try {
                        PageConfigController configController = controllerManager.getPageConfigController();
                        long currentConfigId = configController.getCurrentConfigId();
                        List<Long> allConfigIds = controllerManager.getSuperConfigDatabaseHelper().queryAllConfigIds();
                        List<Long> orderedIds = new ArrayList<>();
                        if (allConfigIds.contains(currentConfigId)) orderedIds.add(currentConfigId);
                        for (Long id : allConfigIds) {
                            if (!orderedIds.contains(id)) orderedIds.add(id);
                        }
                        List<CrownConfigChoice> choices = CrownConfigChoiceFactory.create(
                                controllerManager.getSuperConfigDatabaseHelper(), orderedIds, currentConfigId);
                        game.showCrownConfigPicker(choices, choice -> {
                            if (!choice.isSelectable() || choice.getConfigId() == currentConfigId) {
                                return kotlin.Unit.INSTANCE;
                            }
                            PreferenceManager.getDefaultSharedPreferences(context)
                                    .edit()
                                    .putLong("current_config_id", choice.getConfigId())
                                    .apply();
                            try {
                                configController.initConfig();
                            } catch (Exception ignored) { }
                            loadAllElement(choice.getConfigId());
                            showToast(context.getString(
                                    R.string.toast_switched_to_config, choice.getName()));
                            return kotlin.Unit.INSTANCE;
                        }, () -> kotlin.Unit.INSTANCE);
                    } catch (Exception e) {
                        Toast.makeText(context, R.string.toast_config_list_load_failed, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) {
                }
            };
        } else if (key.equals(SPECIAL_KEY_PAN_ZOOM_MODE)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        Toast.makeText(game,
                                game.getisTouchOverrideEnabled() ? R.string.toast_pan_zoom_disabled_short : R.string.toast_pan_zoom_enabled_short,
                                Toast.LENGTH_SHORT).show();
                        game.setisTouchOverrideEnabled(!game.getisTouchOverrideEnabled());
                    }
                }
                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_OPEN_GAME_MENU)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        game.showGameMenu( null);
                    }
                }
                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals(SPECIAL_KEY_EDIT_MODE_SWITCH)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        buttonVibrator();
                        game.toggleBackKeyMenuType();
                        changeMode(Mode.Edit);
                        open();
                    }
                }
                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        }
        else if (key.equals(SPECIAL_KEY_MOUSE_ENABLE_SWITCH)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        boolean enabled = controllerManager.getPageConfigController().toggleTouchEnabled();
                        if (enabled) {
                            showToast(context.getString(R.string.toast_touch_enabled_short));
                        } else {
                            showToast(context.getString(R.string.toast_touch_disabled_short));
                        }
                    }
                }

                @Override
                public void sendDigitalSwitchEvent(boolean down, boolean cleanup) {
                    if (!cleanup) {
                        sendEvent(true);
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        }
        else if (key.equals(SPECIAL_KEY_MOUSE_MOVE_ONLY)) {
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        game.toggleMouseMoveOnly();
                        if (game.isMouseMoveOnlyEnabled()) {
                            showToast(context.getString(R.string.layout_page_device_text_mmo_true_text));
                        } else {
                            showToast(context.getString(R.string.layout_page_device_text_mmo_false_text));
                        }
                    }
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        }
        return null;
    }

    //鼠标模式切换
    private SendEventHandler switchMouseMode(boolean touchMode, boolean enhancedTouch, String toastMessage) {
        return new SendEventHandler() {
            @Override
            public void sendEvent(boolean down) {
                if (down) {
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(touchMode));
                    contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(enhancedTouch));
                    controllerManager.getTouchController().setTouchMode(touchMode);
                    controllerManager.getTouchController().setEnhancedTouch(enhancedTouch);
                    showToast(toastMessage);
                    controllerManager.getSuperConfigDatabaseHelper().

                            updateConfig(currentConfigId, contentValues);
                }
            }

            @Override
            public void sendEvent(int analog1, int analog2) {

            }
        };
    }


    public void sendKeyEvent(boolean buttonDown, short keyCode) {
        game.keyboardEvent(buttonDown, keyCode);
        //如果map中有对应按键的runnable，则删除该按键的runnable。
        if (keyEventRunnableMap.containsKey(keyCode)) {
            handler.removeCallbacks(keyEventRunnableMap.get(keyCode));
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                game.keyboardEvent(buttonDown, keyCode);
            }
        };
        //把这个按键的runnable放到map中，以便这个按键重新发送的时候，重置runnable。
        keyEventRunnableMap.put(keyCode, runnable);


        handler.postDelayed(runnable, 50);
        handler.postDelayed(runnable, 75);
    }

    public void sendMouseEvent(int mouseId, boolean down) {
        game.mouseButtonEvent(mouseId, down);
        if (mouseEventRunnableMap.containsKey(mouseId)) {
            handler.removeCallbacks(mouseEventRunnableMap.get(mouseId));
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                game.mouseButtonEvent(mouseId, down);
            }
        };
        //把这个按键的runnable放到map中，以便这个按键重新发送的时候，重置runnable。
        mouseEventRunnableMap.put(mouseId, runnable);

        handler.postDelayed(runnable, 50);
        handler.postDelayed(runnable, 75);
    }

    public void sendMouseScroll(int scrollDirection) {
        game.mouseVScroll((byte) scrollDirection);
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                game.mouseVScroll((byte) scrollDirection);
            }
        };
        handler.postDelayed(runnable, 50);
        handler.postDelayed(runnable, 75);
    }

    public void sendGamepadEvent() {
        game.getControllerHandler().reportOscState(
                gamepadInputContext.inputMap,
                gamepadInputContext.leftStickX,
                gamepadInputContext.leftStickY,
                gamepadInputContext.rightStickX,
                gamepadInputContext.rightStickY,
                gamepadInputContext.leftTrigger,
                gamepadInputContext.rightTrigger
        );

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                game.getControllerHandler().reportOscState(
                        gamepadInputContext.inputMap,
                        gamepadInputContext.leftStickX,
                        gamepadInputContext.leftStickY,
                        gamepadInputContext.rightStickX,
                        gamepadInputContext.rightStickY,
                        gamepadInputContext.leftTrigger,
                        gamepadInputContext.rightTrigger
                );
            }
        };
        handler.postDelayed(runnable, 50);
        handler.postDelayed(runnable, 75);


    }

    public void setButtonVibrator(boolean buttonVibrator) {
        this.buttonVibrator = buttonVibrator;
    }

    public void setGameVibrator(boolean gameVibrator) {
        this.gameVibrator = gameVibrator;
        if (!gameVibrator) {
            game.getControllerHandler().submitLegacyDeviceRumble((short) 0, (short) 0);
        }
    }

    public void buttonVibrator() {
        if (buttonVibrator) {
            rumbleSingleVibrator((short) 1000, (short) 1000, 50);
        }
    }

    public void gameVibrator(short lowFreqMotor, short highFreqMotor) {
        if (gameVibrator) {
            game.getControllerHandler().submitLegacyDeviceRumble(lowFreqMotor, highFreqMotor);
        }
    }


    public void rumbleSingleVibrator(short lowFreqMotor, short highFreqMotor, int vibratorTime) {
        game.getControllerHandler().playDeviceTouchHaptic(lowFreqMotor, highFreqMotor, vibratorTime);
    }
}
