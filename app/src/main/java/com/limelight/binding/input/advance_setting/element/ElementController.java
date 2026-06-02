package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.preference.PreferenceManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
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
import android.app.AlertDialog;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.config.PageConfigController;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
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



    public interface SendEventHandler {
        void sendEvent(boolean down);

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
    private Vibrator deviceVibrator;

    private final ControllerManager controllerManager;
    private final ControllerHandler controllerHandler;
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
    private boolean gameVibrator = false;
    private boolean buttonVibrator = false;

    // 滚轮按住事件管理
    private Map<Integer, Runnable> mouseScrollRunnableMap = new HashMap<>();
    private static final int MOUSE_SCROLL_INITIAL_DELAY = 150; // 初始延迟（毫秒）
    private static int MOUSE_SCROLL_REPEAT_INTERVAL = 100; // 重复间隔（毫秒）

    private static final String CROWN_DETAIL_ACTION_BAR_TAG = "crown_detail_action_bar";
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
        this.controllerHandler = game.getControllerHandler();
        this.pageDeviceController = controllerManager.getPageDeviceController();
        this.handler = new Handler(Looper.getMainLooper());
        this.pageEdit = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_edit, null);
        this.editGridView = new EditGridView(context);
        this.bottomViewAmount = elementsLayout.getChildCount();
        this.deviceVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.alignmentSnapEnabled = preferences.getBoolean(PREF_CROWN_ALIGNMENT_SNAP_ENABLED, true);
        initEditPage();
    }

    private void initEditPage() {
        pageEdit.findViewById(R.id.page_edit_exit_edit_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeMode(Mode.Normal);
                controllerManager.pageSuperMenuController.open();
                // 1. 通过公共方法通知 Game Activity 切换回普通菜单模式
                ((Game) context).setcurrentBackKeyMenu(Game.BackKeyMenuMode.GAME_MENU);

                // 2. 显示操作成功的提示信息
                Toast.makeText(context, context.getString(R.string.toast_back_key_menu_switch_1), Toast.LENGTH_SHORT).show();
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

        pageEdit.findViewById(R.id.page_edit_add_digital_common_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalCommonButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_switch_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalSwitchButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_movable_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalMovableButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_pad).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalPad.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_analog_stick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = AnalogStick.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_stick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalStick.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_invisible_analog_stick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = InvisibleAnalogStick.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_invisible_digital_stick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = InvisibleDigitalStick.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_simplify_performance).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
                ContentValues contentValues = SimplifyPerformance.getInitialInfo();
                contentValues.put(Element.COLUMN_INT_ELEMENT_CENTRAL_X, displayMetrics.widthPixels / 2);
                contentValues.put(Element.COLUMN_INT_ELEMENT_CENTRAL_Y, 30);
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_combine_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalCombineButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_group_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = GroupButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_wheel_pad).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = WheelPad.getInitialInfo();
                addElement(contentValues);
            }
        });
        Switch dragEditSwitch = pageEdit.findViewById(R.id.page_edit_drag_edit_switch);
        if (dragEditSwitch != null) {
            // 设置初始状态
            dragEditSwitch.setChecked(true); // 默认启用长按移动按键

            // 添加开关监听器
            dragEditSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    setDragEditEnabled(isChecked);
                    String message = isChecked ? "长按移动按键" : "可直接拖动按键";
                    showToast(message);
                }
            });
        }
        Switch alignmentSnapSwitch = pageEdit.findViewById(R.id.page_edit_alignment_snap_switch);
        if (alignmentSnapSwitch != null) {
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


    protected Handler getHandler() {
        return handler;
    }


    public void loadAllElement(Long configId) {
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
            elementsLayout.removeView(element);
            elements.remove(element);
        }
    }

    private void removeAllElementsOnScreen() {
        for (Element element : elements) {
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
            applyCrownDetailStyle(elementSettingPage);
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
        if (actionButtons.isEmpty() && !supportsAutoColor) {
            return;
        }

        LinearLayout actionBar = new LinearLayout(context);
        actionBar.setTag(CROWN_DETAIL_ACTION_BAR_TAG);
        actionBar.setGravity(Gravity.END);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);

        FrameLayout.LayoutParams actionBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32),
                Gravity.TOP | Gravity.END
        );
        actionBarParams.setMargins(0, dp(10), dp(20), 0);

        if (supportsAutoColor) {
            ImageButton autoColorButton = createCrownIconActionButton(
                    R.drawable.phc_action_auto_color,
                    context.getString(R.string.crown_auto_color_action),
                    v -> applyCrownAutoColors(autoColorOwner, page)
            );
            actionBar.addView(autoColorButton, createCrownIconActionParams());
        }

        for (Button originalButton : actionButtons) {
            String idName = context.getResources().getResourceEntryName(originalButton.getId());
            ImageButton actionButton = createCrownIconActionButton(
                    getCrownActionIconRes(idName),
                    originalButton.getText(),
                    v -> originalButton.performClick()
            );
            actionBar.addView(actionButton, createCrownIconActionParams());
            originalButton.setVisibility(View.GONE);
            hideActionRowIfNeeded(originalButton);
        }

        page.addView(actionBar, actionBarParams);
    }

    private ImageButton createCrownIconActionButton(int iconResId, CharSequence contentDescription, View.OnClickListener listener) {
        ImageButton actionButton = new ImageButton(context);
        actionButton.setBackgroundResource(R.drawable.crown_action_icon_button_bg);
        actionButton.setColorFilter(context.getResources().getColor(R.color.crown_text_primary));
        actionButton.setContentDescription(contentDescription);
        actionButton.setImageResource(iconResId);
        actionButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        actionButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        actionButton.setOnClickListener(listener);
        return actionButton;
    }

    private LinearLayout.LayoutParams createCrownIconActionParams() {
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                dp(32),
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
        if (view instanceof Switch) {
            Switch switchView = (Switch) view;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                switchView.setThumbTintList(getColorStateList(R.color.crown_switch_thumb));
                switchView.setTrackTintList(getColorStateList(R.color.crown_switch_track));
            }
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
            if (!(editText.getBackground() instanceof ColorDrawable)) {
                editText.setTextColor(context.getResources().getColor(R.color.crown_text_primary));
                editText.setHintTextColor(context.getResources().getColor(R.color.crown_text_secondary));
            }
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(context.getResources().getColor(R.color.crown_text_primary));
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
        pageEdit.post(new Runnable() {
            @Override
            public void run() {
                View scrollView = pageEdit.findViewById(R.id.page_edit_scroll);
                if (scrollView != null) {
                    scrollView.scrollTo(0, 0);
                }
            }
        });
    }

    public void changeMode(Mode mode) {
        if (this.mode == mode) {
            return;
        }

        this.mode = mode;
        switch (mode) {
            case Normal:
                controllerManager.getTouchController().enableTouch(true);
                this.mode = Mode.Normal;
                clearAlignmentGuides();
                elementsLayout.removeView(editGridView);
                for (Element element : elements) {
                    element.invalidate();
                }
                break;
            case Edit:
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
                            showToast("多点触控模式");
                        } else if (!touchMode && enhancedTouch) {
                            // 当前是多点触控模式 -> 切换到触控板模式
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(true));
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(false));
                            controllerManager.getTouchController().setTouchMode(true);
                            controllerManager.getTouchController().setEnhancedTouch(false);
                            showToast("触控板模式");
                        } else {
                            // 当前是触控板模式 -> 切换到经典鼠标模式
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(false));
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(false));
                            controllerManager.getTouchController().setTouchMode(false);
                            controllerManager.getTouchController().setEnhancedTouch(false);
                            showToast("经典鼠标模式");
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
            return switchMouseMode(false, false, "经典鼠标模式");
        } else if (key.equals(SPECIAL_KEY_TRACKPAD_MODE)) {
            return switchMouseMode(true, false, "触控板模式");
        } else if (key.equals(SPECIAL_KEY_MULTI_TOUCH_MODE)) {
            return switchMouseMode(false, true, "多点触控模式");
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
                        // 读取所有配置ID与名称
                        List<Long> configIds = controllerManager.getSuperConfigDatabaseHelper().queryAllConfigIds();
                        List<String> configNames = new ArrayList<>();
                        for (Long id : configIds) {
                            String name = (String) controllerManager.getSuperConfigDatabaseHelper()
                                    .queryConfigAttribute(id, PageConfigController.COLUMN_STRING_CONFIG_NAME, "default");
                            configNames.add(name);
                        }

                        // 弹出选择对话框
                        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppDialogStyle);
                        builder.setTitle("选择配置")
                                .setItems(configNames.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        if (which < 0 || which >= configIds.size()) return;
                                        Long newConfigId = configIds.get(which);
                                        String newName = configNames.get(which);

                                        // 保存到首选项
                                        PreferenceManager.getDefaultSharedPreferences(context)
                                                .edit()
                                                .putLong("current_config_id", newConfigId)
                                                .apply();

                                        // 刷新配置与元素
                                        try {
                                            controllerManager.getPageConfigController().initConfig();
                                        } catch (Exception ignored) {}
                                        loadAllElement(newConfigId);

                                        showToast("已切换到: " + newName);
                                    }
                                })
                                .setNegativeButton(R.string.game_menu_cancel, null)
                                .show();
                    } catch (Exception e) {
                        Toast.makeText(context, "无法加载配置列表", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(game, game.getisTouchOverrideEnabled()?"已关闭平移/缩放":"已开启平移/缩放", Toast.LENGTH_SHORT).show();
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
                        boolean mouseEnable = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, PageConfigController.COLUMN_BOOLEAN_TOUCH_ENABLE, String.valueOf(true)));
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_ENABLE, String.valueOf(!mouseEnable));
                        //保存到数据库中
                        controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId, contentValues);
                        //做实际的设置
                        controllerManager.getTouchController().enableTouch(!mouseEnable);
                        if (!mouseEnable) {
                            showToast("开启触控");
                        } else {
                            showToast("关闭触控");
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
        controllerHandler.reportOscState(
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
                controllerHandler.reportOscState(
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
    }

    public void buttonVibrator() {
        if (buttonVibrator) {
            rumbleSingleVibrator((short) 1000, (short) 1000, 50);
        }
    }

    public void gameVibrator(short lowFreqMotor, short highFreqMotor) {
        if (gameVibrator) {
            rumbleSingleVibrator(lowFreqMotor, highFreqMotor, 60000);
        }
    }


    public void rumbleSingleVibrator(short lowFreqMotor, short highFreqMotor, int vibratorTime) {
        // Since we can only use a single amplitude value, compute the desired amplitude
        // by taking 80% of the big motor and 33% of the small motor, then capping to 255.
        // NB: This value is now 0-255 as required by VibrationEffect.
        short lowFreqMotorMSB = (short) ((lowFreqMotor >> 8) & 0xFF);
        short highFreqMotorMSB = (short) ((highFreqMotor >> 8) & 0xFF);
        int simulatedAmplitude = Math.min(255, (int) ((lowFreqMotorMSB * 0.80) + (highFreqMotorMSB * 0.33)));

        if (simulatedAmplitude == 0) {
            // This case is easy - just cancel the current effect and get out.
            // NB: We cannot simply check lowFreqMotor == highFreqMotor == 0
            // because our simulatedAmplitude could be 0 even though our inputs
            // are not (ex: lowFreqMotor == 0 && highFreqMotor == 1).
            deviceVibrator.cancel();
            return;
        }

        // Attempt to use amplitude-based control if we're on Oreo and the device
        // supports amplitude-based vibration control.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (deviceVibrator.hasAmplitudeControl()) {
                VibrationEffect effect = VibrationEffect.createOneShot(vibratorTime, simulatedAmplitude);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    VibrationAttributes vibrationAttributes = new VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_MEDIA)
                            .build();
                    deviceVibrator.vibrate(effect, vibrationAttributes);
                } else {
                    AudioAttributes audioAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .build();
                    deviceVibrator.vibrate(effect, audioAttributes);
                }
                return;
            }
        }

        // If we reach this point, we don't have amplitude controls available, so
        // we must emulate it by PWMing the vibration. Ick.
        long pwmPeriod = 20;
        long onTime = (long) ((simulatedAmplitude / 255.0) * pwmPeriod);
        long offTime = pwmPeriod - onTime;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes vibrationAttributes = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build();
            deviceVibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, onTime, offTime}, 0), vibrationAttributes);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build();
            deviceVibrator.vibrate(new long[]{0, onTime, offTime}, 0, audioAttributes);
        } else {
            deviceVibrator.vibrate(new long[]{0, onTime, offTime}, 0);
        }
    }
}
