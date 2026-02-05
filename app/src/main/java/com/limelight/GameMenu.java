package com.limelight;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Html;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.advance_setting.config.PageConfigController;
import com.limelight.binding.input.advance_setting.element.ElementController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.KeyCodeMapper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Objects;

/**
 * 提供游戏流媒体进行中的选项菜单
 * 在游戏活动中按返回键时显示
 */
public class GameMenu {

    // 常量定义
    private static final long TEST_GAME_FOCUS_DELAY = 10L;
    private static final long KEY_UP_DELAY = 25L;
    private static final long SLEEP_DELAY = 200L;
    private static final float DIALOG_ALPHA = 0.7f;
    private static final float DIALOG_DIM_AMOUNT = 0.3f;
    private static final String GAME_MENU_TITLE = "🍥🍬 V+ GAME MENU";

    // 用于存储自定义按键的 SharedPreferences 文件名和键名
    private static final String PREF_NAME = "custom_special_keys";
    private static final String KEY_NAME = "data";

    private static boolean mouse_enable_switch = false;

    // 图标映射缓存
    private static final Map<String, Integer> ICON_MAP = new HashMap<>();

    static {
        ICON_MAP.put("game_menu_change_resolution", R.drawable.ic_resolution_cute);
        ICON_MAP.put("game_menu_toggle_keyboard", R.drawable.ic_keyboard_cute);
        ICON_MAP.put("game_menu_toggle_performance_overlay", R.drawable.ic_performance_cute);
        ICON_MAP.put("game_menu_toggle_virtual_controller", R.drawable.ic_controller_cute);
        ICON_MAP.put("game_menu_disconnect", R.drawable.ic_disconnect_cute);
        ICON_MAP.put("game_menu_send_keys", R.drawable.ic_send_keys_cute);
        ICON_MAP.put("game_menu_toggle_host_keyboard", R.drawable.ic_host_keyboard);
        ICON_MAP.put("game_menu_disconnect_and_quit", R.drawable.ic_btn_quit);
        ICON_MAP.put("game_menu_cancel", R.drawable.ic_cancel_cute);
        ICON_MAP.put("mouse_mode", R.drawable.ic_mouse_cute);
        ICON_MAP.put("game_menu_mouse_emulation", R.drawable.ic_mouse_emulation_cute);
        ICON_MAP.put("crown_function_menu", R.drawable.ic_super_crown);
        ICON_MAP.put("game_menu_test_local_rumble", R.drawable.ic_rumble_cute);
    }

    /**
     * 菜单选项类
     */
    public static class MenuOption {
        private final String label;
        private final boolean withGameFocus;
        private final Runnable runnable;
        private final String iconKey; // 用于图标映射的键
        private final boolean showIcon; // 是否显示图标
        private final boolean keepDialog; // 点击此项时是否保留对话框并替换左侧菜单（用于二级菜单）

        public MenuOption(String label, boolean withGameFocus, Runnable runnable) {
            this(label, withGameFocus, runnable, null, true);
        }

        public MenuOption(String label, Runnable runnable) {
            this(label, false, runnable, null, true);
        }

        public MenuOption(String label, boolean withGameFocus, Runnable runnable, String iconKey) {
            this(label, withGameFocus, runnable, iconKey, true);
        }

        public MenuOption(String label, boolean withGameFocus, Runnable runnable, String iconKey, boolean showIcon) {
            this(label, withGameFocus, runnable, iconKey, showIcon, false);
        }

        public MenuOption(String label, boolean withGameFocus, Runnable runnable, String iconKey, boolean showIcon, boolean keepDialog) {
            this.label = label;
            this.withGameFocus = withGameFocus;
            this.runnable = runnable;
            this.iconKey = iconKey;
            this.showIcon = showIcon;
            this.keepDialog = keepDialog;
        }

        public String getLabel() { return label; }
        public boolean isWithGameFocus() { return withGameFocus; }
        public Runnable getRunnable() { return runnable; }

        public String getIconKey() {
            return iconKey;
        }

        public boolean isShowIcon() {
            return showIcon;
        }

        public boolean isKeepDialog() {
            return keepDialog;
        }
    }

    // 实例变量
    private final Game game;
    private final NvApp app;
    private final NvConnection conn;
    private final GameInputDevice device;
    private final Handler handler;
    // 当前激活的对话框（如果有）
    private AlertDialog activeDialog;
    // 当前激活对话框所用的自定义视图引用（便于内部替换）
    private View activeCustomView;
    // 标志：上一次运行的选项是否打开了子菜单（由 showSubMenu 设置）
    private boolean lastActionOpenedSubmenu = false;
    // 菜单历史栈，用于二级/多级菜单的回退
    private final java.util.Deque<MenuState> menuStack = new java.util.ArrayDeque<>();

    public GameMenu(Game game, NvApp app, NvConnection conn, GameInputDevice device) {
        this.game = game;
        this.app = app;
        this.conn = conn;
        this.device = device;
        this.handler = new Handler();

        showMenu();
    }

    /**
     * 菜单状态，用于回退
     */
    private static class MenuState {
        final String title;
        final MenuOption[] normalOptions;

        MenuState(String title, MenuOption[] normalOptions) {
            this.title = title;
            this.normalOptions = normalOptions;
        }
    }

    /**
     * 获取字符串资源
     */
    private String getString(int id) {
        return game.getResources().getString(id);
    }

    /**
     * 键盘修饰符枚举
     */
    private enum KeyModifier {
        SHIFT((short) KeyboardTranslator.VK_LSHIFT, KeyboardPacket.MODIFIER_SHIFT),
        CTRL((short) KeyboardTranslator.VK_LCONTROL, KeyboardPacket.MODIFIER_CTRL),
        META((short) KeyboardTranslator.VK_LWIN, KeyboardPacket.MODIFIER_META),
        ALT((short) KeyboardTranslator.VK_MENU, KeyboardPacket.MODIFIER_ALT);

        final short keyCode;
        final byte modifier;

        KeyModifier(short keyCode, byte modifier) {
            this.keyCode = keyCode;
            this.modifier = modifier;
        }

        public static byte getModifier(short key) {
            for (KeyModifier km : values()) {
                if (km.keyCode == key) {
                    return km.modifier;
                }
            }
            return 0;
        }
    }

    /**
     * 获取键盘修饰符
     */
    private static byte getModifier(short key) {
        return KeyModifier.getModifier(key);
    }

    /**
     * 断开连接并退出
     */
    private void disconnectAndQuit() {
        try {
            game.disconnect();
            conn.doStopAndQuit();
        } catch (IOException | XmlPullParserException e) {
            Toast.makeText(game, "断开连接时发生错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 发送键盘按键序列
     */
    private void sendKeys(short[] keys) {
        if (keys == null || keys.length == 0) {
            return;
        }

        final byte[] modifier = { (byte) 0 };

        // 按下所有按键
        for (short key : keys) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], (byte) 0);
            modifier[0] |= getModifier(key);
        }

        // 延迟后释放按键
        handler.postDelayed(() -> {
            for (int pos = keys.length - 1; pos >= 0; pos--) {
                short key = keys[pos];
                modifier[0] &= ~getModifier(key);
                conn.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], (byte) 0);
            }
        }, KEY_UP_DELAY);
    }

    /**
     * 在游戏获得焦点时运行任务
     */
    private void runWithGameFocus(Runnable runnable) {
        if (game.isFinishing()) {
            return;
        }

        if (!game.hasWindowFocus()) {
            handler.postDelayed(() -> runWithGameFocus(runnable), TEST_GAME_FOCUS_DELAY);
            return;
        }

        runnable.run();
    }

    /**
     * 执行菜单选项
     */
    private void run(MenuOption option) {
        if (option == null || option.getRunnable() == null) {
            return;
        }

        if (option.isWithGameFocus()) {
            runWithGameFocus(option.getRunnable());
        } else {
            option.getRunnable().run();
        }
    }

    /**
     * 显示一个菜单列表，用于在"增强式多点触控","经典鼠标模式","触控板模式","本地鼠标指针"之间切换。
     */
    private void showTouchModeMenu() {
        boolean isEnhancedTouch = game.prefConfig.enableEnhancedTouch;
        boolean isTouchscreenTrackpad = game.prefConfig.touchscreenTrackpad;
        boolean isNativeMousePointer = game.prefConfig.enableNativeMousePointer;

        // 创建一个列表来存储菜单选项
        List<MenuOption> touchModeOptionsList = new ArrayList<>();

        touchModeOptionsList.add(
                new MenuOption(
                        getString(R.string.game_menu_touch_mode_enhanced),
                        isEnhancedTouch && !isTouchscreenTrackpad && !isNativeMousePointer,
                        () -> {
                            game.prefConfig.enableEnhancedTouch = true;
                            game.prefConfig.enableNativeMousePointer = false;
                            game.enableNativeMousePointer(false);  // 关闭本地鼠标模式
                            game.setTouchMode(false);
                            updateEnhancedTouchSetting(true);
                            updateTouchModeSetting(false);
                            Toast.makeText(game, getString(R.string.toast_touch_mode_enhanced_on), Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                ));
        touchModeOptionsList.add(
                new MenuOption(
                        getString(R.string.game_menu_touch_mode_classic),
                        !isEnhancedTouch && !isTouchscreenTrackpad && !isNativeMousePointer,
                        () -> {
                            game.prefConfig.enableEnhancedTouch = false;
                            game.prefConfig.enableNativeMousePointer = false;
                            game.enableNativeMousePointer(false);  // 关闭本地鼠标模式
                            game.setTouchMode(false);
                            updateEnhancedTouchSetting(false);
                            updateTouchModeSetting(false);
                            Toast.makeText(game, getString(R.string.toast_touch_mode_classic_on), Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                ));
        touchModeOptionsList.add(
                new MenuOption(
                        getString(R.string.game_menu_touch_mode_trackpad),
                        isTouchscreenTrackpad && !isNativeMousePointer,
                        () -> {
                            game.prefConfig.enableNativeMousePointer = false;
                            game.enableNativeMousePointer(false);  // 关闭本地鼠标模式
                            game.setTouchMode(true);
                            updateTouchModeSetting(true);
                            Toast.makeText(game, getString(R.string.toast_touch_mode_trackpad_on), Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                ));
        touchModeOptionsList.add(
                new MenuOption(
                        getString(R.string.game_menu_touch_mode_trackpad) + " - " +
                                (game.prefConfig.enableDoubleClickDrag ? "关闭双击按住" : "开启双击按住"),
                        false,
                        () -> {
                            game.prefConfig.enableDoubleClickDrag = !game.prefConfig.enableDoubleClickDrag;
                            // 不保存到持久化存储，只在当前会话中生效
                            Toast.makeText(game,
                                    game.prefConfig.enableDoubleClickDrag ? "已开启双击按住功能" : "已关闭双击按住功能",
                                    Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                ));
        
        // 本地光标渲染选项（仅在触屏触控板模式下显示）
        if (isTouchscreenTrackpad) {
            touchModeOptionsList.add(
                    new MenuOption(
                            getString(R.string.game_menu_local_cursor_rendering) + " - " +
                                    (game.prefConfig.enableLocalCursorRendering ? "开启" : "关闭"),
                            false,
                            () -> {
                                game.prefConfig.enableLocalCursorRendering = !game.prefConfig.enableLocalCursorRendering;
                                game.refreshLocalCursorState(game.prefConfig.enableLocalCursorRendering);
                                String message = game.prefConfig.enableLocalCursorRendering ? 
                                    "本地光标渲染已开启" : "本地光标渲染已关闭";
                                Toast.makeText(game, message, Toast.LENGTH_SHORT).show();
                            },
                            null,
                            false
                    )
            );
        }
        
        touchModeOptionsList.add(
                new MenuOption(
                        getString(R.string.game_menu_touch_mode_native_mouse),
                        isNativeMousePointer,
                        () -> {
                            game.prefConfig.enableNativeMousePointer = true;
                            game.prefConfig.enableEnhancedTouch = false;
                            game.setTouchMode(false);
                            game.enableNativeMousePointer(true);
                            updateTouchModeSetting(false);
                            Toast.makeText(game, getString(R.string.toast_touch_mode_native_mouse_on), Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                ));

        touchModeOptionsList.add(
                new MenuOption(
                        getString(R.string.game_menu_toggle_remote_mouse),
                        false,
                        () -> {
                            sendKeys(new short[]{
                                    KeyboardTranslator.VK_LCONTROL,
                                    KeyboardTranslator.VK_MENU,
                                    KeyboardTranslator.VK_LSHIFT,
                                    KeyboardTranslator.VK_N
                            });
                            Toast.makeText(game, getString(R.string.toast_remote_mouse_toast), Toast.LENGTH_SHORT).show();
                        },
                        null,
                        false
                )
        );

        // 将列表转换为数组
        MenuOption[] touchModeOptions = touchModeOptionsList.toArray(new MenuOption[0]);

        // 3. 显示为子菜单（在活动对话框内替换普通菜单区域）
        showSubMenu(getString(R.string.game_menu_switch_touch_mode), touchModeOptions);
    }

    /**
     * 将当前的触控模式（是否为触摸板模式）保存到数据库中。
     * @param isTrackpadMode true 表示保存为触摸板板模式，false 表示保存为其他模式。
     */
    private void updateTouchModeSetting(boolean isTrackpadMode) {
        // 从 Game Activity 获取 ControllerManager 实例
        ControllerManager controllerManager = game.getControllerManager();

        // 添加空值检查
        if (controllerManager == null) {
            LimeLog.warning("ControllerManager is null, cannot update touch mode setting");
            return;
        }

        // 创建一个 ContentValues 对象，用于存放要更新的数据
        ContentValues contentValues = new ContentValues();

        // 1. 从 PageConfigController 获取当前正在使用的配置ID
        Long currentConfigId = controllerManager.getPageConfigController().getCurrentConfigId();

        // 2. 将传入的布尔值转换为字符串，并放入 ContentValues
        //    键是数据库的列名，值是传入的 isTrackpadMode 的状态
        contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(isTrackpadMode));

        // 3. 调用数据库帮助类的方法，将数据更新到数据库中
        controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId, contentValues);
    }

    private void updateEnhancedTouchSetting(boolean isEnabled) {
        // 从 Game Activity 获取 ControllerManager 实例
        ControllerManager controllerManager = game.getControllerManager();

        // 添加空值检查
        if (controllerManager == null) {
            LimeLog.warning("ControllerManager is null, cannot update touch mode setting");
            return;
        }

        ContentValues contentValues = new ContentValues();
        Long currentConfigId = controllerManager.getPageConfigController().getCurrentConfigId();

        contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(isEnabled));

        // 更新到数据库
        controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId, contentValues);
    }

    /**
     * 切换麦克风开关
     */
    private void toggleMicrophone() {
        // 切换GameView中麦克风按钮的显示/隐藏状态
        game.toggleMicrophoneButton();
    }

    /**
     * 切换王冠功能并即时刷新菜单内容
     */
    private void toggleCrownFeature() {
        // 切换王冠功能状态
        game.setCrownFeatureEnabled(!game.isCrownFeatureEnabled());

        // 显示状态变更提示
        Toast.makeText(game, game.isCrownFeatureEnabled() ?
                        getString(R.string.crown_switch_to_crown) :
                        getString(R.string.crown_switch_to_normal),
                Toast.LENGTH_SHORT).show();

        // 即时更新菜单内容，而不是重新创建整个对话框
        if (activeDialog != null && activeDialog.isShowing()) {
            // 更新标题栏的王冠按钮文本
            updateCrownToggleButton();

            // 重新构建并更新菜单列表
            rebuildAndReplaceMenu();
        }
    }

    /**
     * 更新标题栏王冠按钮文本
     */
    private void updateCrownToggleButton() {
        if (activeCustomView != null) {
            TextView crownToggleButton = activeCustomView.findViewById(R.id.btnCrownToggle);
            if (crownToggleButton != null) {
                String crownText = game.isCrownFeatureEnabled() ?
                        getString(R.string.crown_switch_to_normal) :
                        getString(R.string.crown_switch_to_crown);
                crownToggleButton.setText(Html.fromHtml("<u>" + crownText + "</u>"));
            }
        }
    }

    /**
     * 重新构建并替换菜单内容
     */
    private void rebuildAndReplaceMenu() {
        if (activeDialog == null || activeCustomView == null) return;

        // 重新构建普通菜单选项
        List<MenuOption> normalOptions = new ArrayList<>();
        buildNormalMenuOptions(normalOptions);

        // 更新普通菜单列表
        ListView normalListView = activeCustomView.findViewById(R.id.gameMenuList);
        if (normalListView != null) {
            GameMenuAdapter adapter = new GameMenuAdapter(game,
                    normalOptions.toArray(new MenuOption[0]));
            normalListView.setAdapter(adapter);
            // 重新设置点击监听器
            setupMenu(normalListView, adapter, activeDialog);
        }
    }

    /**
     * 显示“王冠功能”的二级菜单，包含显隐和配置选项。
     */
    private void showCrownFunctionMenu() {
        // 从 Game Activity 获取 ControllerManager 实例
        ControllerManager controllerManager = game.getControllerManager();

        // 检查 王冠功能是否开启，如果没有开启则不显示任何选项
        if (!game.isCrownFeatureEnabled()) {
            Toast.makeText(game, "王冠功能未启用", Toast.LENGTH_SHORT).show();
            return;
        }
        MenuOption[] crownFunctionOptions = {
                // --- 选项1: 显示/隐藏虚拟按键 ---
                new MenuOption(
                        getString(R.string.game_menu_toggle_elements_visibility),
                        false,
                        game::toggleVirtualControllerVisibility,
                        "crown_function_menu",
                        true
                ),
                new MenuOption(
                        "开启/关闭触控",
                        false,
                        () -> {
                            controllerManager.getTouchController().enableTouch(mouse_enable_switch);
                            Toast.makeText(game, mouse_enable_switch ? "触控已开启" : "触控已关闭", Toast.LENGTH_SHORT).show();
                            mouse_enable_switch = !mouse_enable_switch;
                        },
                        "crown_function_menu",
                        true
                ),
                // --- 配置设置 ---
                new MenuOption(
                        getString(R.string.game_menu_configure_settings),
                        false,
                        () -> {
                            if (controllerManager != null) {
                                game.toggleBackKeyMenuType();
                                game.setcurrentBackKeyMenu(Game.BackKeyMenuMode.NO_MENU);
                                controllerManager.getPageConfigController().open();
                            }
                        },
                        "crown_function_menu",
                        true
                ),
                // --- 编辑模式 ---
                new MenuOption(
                        getString(R.string.game_menu_edit_mode),
                        false,
                        () -> {
                            if (controllerManager != null) {
                                game.toggleBackKeyMenuType();
                                controllerManager.getElementController().changeMode(ElementController.Mode.Edit);
                                controllerManager.getElementController().open();
                            }
                        },
                        "crown_function_menu",
                        true
                ),
                // --- 配置王冠功能 ---
                new MenuOption(
                        getString(R.string.game_menu_configure_crown_function),
                        false,
                        game::toggleBackKeyMenuType,
                        "crown_function_menu",
                        true
                )
        };

        // 使用 showSubMenu 方法来显示这个二级菜单
        showSubMenu(getString(R.string.game_menu_crown_function), crownFunctionOptions);
    }

    /**
     * 本地测试震动：对控制器 0..3 发送 1 秒强震动
     */
    private void testLocalRumbleAll() {
        try {
            com.limelight.binding.input.ControllerHandler ch = game.getControllerHandler();
            if (ch == null) {
                Toast.makeText(game, "无法访问控制器", Toast.LENGTH_SHORT).show();
                return;
            }

            short on = (short) 0xFFFF;
            short off = 0;
            for (short n = 0; n < 4; n++) {
                ch.handleRumble(n, on, on);
            }

            handler.postDelayed(() -> {
                try {
                    for (short n = 0; n < 4; n++) {
                        ch.handleRumble(n, off, off);
                    }
                } catch (Exception ignored) {}
            }, 1000);

            Toast.makeText(game, "已发送本地震动测试", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(game, "震动测试失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * 显示分辨率选择菜单
     */
    private void showResolutionMenu() {
        List<MenuOption> options = new ArrayList<>();

        // 获取当前的分辨率字符串，用于标记
        String currentResStr = game.prefConfig.width + "x" + game.prefConfig.height;

        // 1. 添加预设分辨率
        for (String res : PreferenceConfiguration.RESOLUTIONS) {
            String label = res;
            // 尝试简单的匹配标记
            if (res.equals(currentResStr)) {
                label += " (Current)";
            }

            final String targetRes = res;
            options.add(new MenuOption(
                    label,
                    false,
                    () -> changeResolution(targetRes),
                    null,
                    false
            ));
        }

        // 2. 添加自定义分辨率
        SharedPreferences customPrefs = game.getSharedPreferences("custom_resolutions", Context.MODE_PRIVATE);
        Set<String> customResolutions = customPrefs.getStringSet("custom_resolutions", null);

        if (customResolutions != null && !customResolutions.isEmpty()) {
            List<String> sortedCustom = new ArrayList<>(customResolutions);
            // 简单的分辨率排序
            Collections.sort(sortedCustom, (s1, s2) -> {
                String[] parts1 = s1.split("x");
                String[] parts2 = s2.split("x");
                if (parts1.length != 2 || parts2.length != 2) return s1.compareTo(s2);
                try {
                    int w1 = Integer.parseInt(parts1[0]);
                    int h1 = Integer.parseInt(parts1[1]);
                    int w2 = Integer.parseInt(parts2[0]);
                    int h2 = Integer.parseInt(parts2[1]);

                    if (w1 != w2) return Integer.compare(w1, w2);
                    return Integer.compare(h1, h2);
                } catch (NumberFormatException e) {
                    return s1.compareTo(s2);
                }
            });

            for (String res : sortedCustom) {
                // 避免重复显示预设中已有的分辨率
                boolean isPreset = false;
                for (String preset : PreferenceConfiguration.RESOLUTIONS) {
                    if (preset.equals(res)) {
                        isPreset = true;
                        break;
                    }
                }
                if (isPreset) continue;

                String label = res + " (Custom)";
                if (res.equals(currentResStr)) {
                    label += " (Current)";
                }

                final String targetRes = res;
                options.add(new MenuOption(
                        label,
                        false,
                        () -> changeResolution(targetRes),
                        null,
                        false
                ));
            }
        }

        showSubMenu("Change Resolution", options.toArray(new MenuOption[0]));
    }

    private void changeResolution(String resString) {
        // 更新 SharedPreferences
        android.preference.PreferenceManager.getDefaultSharedPreferences(game)
                .edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, resString)
                .apply();

        Toast.makeText(game, "Resolution changed to " + resString + ". Restarting...", Toast.LENGTH_SHORT).show();

        // 重启 Activity 以应用新配置
        game.changeResolution();

        if (activeDialog != null) {
            activeDialog.dismiss();
        }
    }

    /**
     * 调整码率
     */
    private void adjustBitrate(int bitrateKbps) {
        try {
            // 显示正在调整的提示
            Toast.makeText(game, "正在调整码率...", Toast.LENGTH_SHORT).show();
            
            // 调用码率调整，使用回调等待API真正返回结果
            conn.setBitrate(bitrateKbps, new NvConnection.BitrateAdjustmentCallback() {
                @Override
                public void onSuccess(int newBitrate) {
                    // API成功返回，在主线程显示成功消息
                    game.runOnUiThread(() -> {
                        try {
                            String successMessage = String.format(getString(R.string.game_menu_bitrate_adjustment_success), newBitrate / 1000);
                            Toast.makeText(game, successMessage, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            LimeLog.warning("Failed to show success toast: " + e.getMessage());
                        }
                    });
                }

                @Override
                public void onFailure(String errorMessage) {
                    // API失败返回，在主线程显示错误消息
                    game.runOnUiThread(() -> {
                        try {
                            String errorMsg = getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + errorMessage;
                            Toast.makeText(game, errorMsg, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            LimeLog.warning("Failed to show error toast: " + e.getMessage());
                        }
                    });
                }
            });
            
        } catch (Exception e) {
            // 调用setBitrate时发生异常（如参数错误等）
            game.runOnUiThread(() -> {
                try {
                    Toast.makeText(game, getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                } catch (Exception toastException) {
                    LimeLog.warning("Failed to show error toast: " + toastException.getMessage());
                }
            });
        }
    }

    /**
     * 显示菜单对话框
     */
    private void showMenuDialog(String title, MenuOption[] normalOptions, MenuOption[] superOptions) {
        AlertDialog.Builder builder = new AlertDialog.Builder(game, R.style.GameMenuDialogStyle);

        // 创建自定义视图
        View customView = createCustomView(builder);
        AlertDialog dialog = builder.create();
        // 记录为当前活动对话框
        this.activeDialog = dialog;
        this.activeCustomView = customView;

        // 设置自定义标题栏
        setupCustomTitleBar(customView, title);

        // 动态设置菜单列表区域高度
//        setupMenuListHeight(customView);
        
        // 设置App名字显示
        setupAppNameDisplay(customView);

        // 设置快捷按钮
        setupQuickButtons(customView, dialog);

        // 设置普通菜单
        setupNormalMenu(customView, normalOptions, dialog);

        // 设置超级菜单
        setupSuperMenu(customView, superOptions, dialog);

        // 设置码率调整区域
        if (game.prefConfig.showBitrateCard) {
            new BitrateCardController(game, conn).setup(customView, dialog);
        } else {
            View bitrate = customView.findViewById(R.id.bitrateAdjustmentContainer);
            if (bitrate != null) bitrate.setVisibility(View.GONE);
        }

        // 设置陀螺仪控制卡片
        if (game.prefConfig.showGyroCard) {
            new GyroCardController(game).setup(customView, dialog);
        } else {
            View gyro = customView.findViewById(R.id.gyroAdjustmentContainer);
            if (gyro != null) gyro.setVisibility(View.GONE);
        }

        // --- 设置快捷指令卡片 ---
        setupCustomKeysCard(customView);

        // 卡片编辑入口
        View cardEditor = customView.findViewById(R.id.cardEditorButton);
        if (cardEditor != null) {
            cardEditor.setOnClickListener(v -> showCardEditorDialog());
        }

        // 设置对话框属性
        setupDialogProperties(dialog);

        // 设置返回键监听器，处理二级菜单返回
        dialog.setOnKeyListener((dialogInterface, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                // 如果菜单栈不为空，说明在二级菜单中，需要返回一级菜单
                if (!menuStack.isEmpty()) {
                    MenuState previousState = menuStack.pop();
                    replaceNormalMenuInDialog(dialog, previousState.title, previousState.normalOptions, false);
                    return true; // 消费返回键事件
                }
                // 如果菜单栈为空，说明在一级菜单，正常关闭对话框
                return false; // 不消费返回键事件，让系统正常关闭对话框
            }
            return false;
        });

        // 在对话框关闭时清理状态
        dialog.setOnDismissListener(d -> {
            // 清理活动引用和菜单栈
            if (this.activeDialog == dialog) this.activeDialog = null;
            if (this.activeCustomView != null) this.activeCustomView = null;
            menuStack.clear();
        });

        dialog.show();
    }

    private void showCardEditorDialog() {
        final String[] items = new String[] {
                "码率调整 Bitrate",
                "体感助手 Gyro",
                "特殊按键 Shortcuts"
        };

        // 获取当前状态
        final boolean[] checked = new boolean[] {
                game.prefConfig.showBitrateCard,
                game.prefConfig.showGyroCard,
                game.prefConfig.showQuickKeyCard
        };

        new AlertDialog.Builder(game, R.style.AppDialogStyle)
                .setTitle("卡片配置 Visible cards")
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("OK", (d, w) -> {
                    game.prefConfig.showBitrateCard = checked[0];
                    game.prefConfig.showGyroCard = checked[1];
                    game.prefConfig.showQuickKeyCard = checked[2];

                    // Persist
                    game.prefConfig.writePreferences(game);

                    // Update UI within current dialog (刷新界面)
                    View root = activeCustomView != null ? activeCustomView :
                            d instanceof AlertDialog ? Objects.requireNonNull(((AlertDialog) d).getOwnerActivity()).findViewById(android.R.id.content) : null;

                    if (root != null) {
                        View bitrate = root.findViewById(R.id.bitrateAdjustmentContainer);
                        if (bitrate != null) bitrate.setVisibility(game.prefConfig.showBitrateCard ? View.VISIBLE : View.GONE);

                        View gyro = root.findViewById(R.id.gyroAdjustmentContainer);
                        if (gyro != null) gyro.setVisibility(game.prefConfig.showGyroCard ? View.VISIBLE : View.GONE);

                        // 刷新快捷指令卡片可见性
                        View keysCard = root.findViewById(R.id.customKeysCardContainer);
                        if (keysCard != null) {
                            // 如果刚才设置为显示，可能需要重新 build 一次按钮(如果之前是GONE的话)
                            if (game.prefConfig.showQuickKeyCard) {
                                setupCustomKeysCard(root);
                            } else {
                                keysCard.setVisibility(View.GONE);
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * 创建自定义视图
     */
    private View createCustomView(AlertDialog.Builder builder) {
        LayoutInflater inflater = game.getLayoutInflater();
        View customView = inflater.inflate(R.layout.custom_dialog, null);
        builder.setView(customView);
        return customView;
    }

    // --- 简单的按键数据模型 ---
    private static class CustomKeyData {
        String name;
        short[] keys;

        CustomKeyData(String name, short[] keys) {
            this.name = name;
            this.keys = keys;
        }
    }

    /**
     * 从存储或默认资源中获取解析好的按键数据列表
     */
    private List<CustomKeyData> getSavedCustomKeys() {
        List<CustomKeyData> resultList = new ArrayList<>();

        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME, "");

        // 1. 如果 SharedPreferences 中没有数据（例如首次启动），则从 raw 资源文件加载默认按键
        if (TextUtils.isEmpty(value)) {
            value = readRawResourceAsString(R.raw.default_special_keys);
            if (!TextUtils.isEmpty(value)) {
                // 将从文件读取的默认值保存到 SharedPreferences 中，以便后续可以对其进行修改
                preferences.edit().putString(KEY_NAME, value).apply();
            }
        }

        // 2. 如果依然为空，直接返回空列表
        if (TextUtils.isEmpty(value)) {
            return resultList;
        }

        // 3. 解析 JSON 数据
        try {
            JSONObject root = new JSONObject(value);
            JSONArray dataArray = root.optJSONArray("data");

            if (dataArray != null && dataArray.length() > 0) {
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject keyObject = dataArray.getJSONObject(i);
                    String name = keyObject.optString("name");
                    JSONArray codesArray = keyObject.getJSONArray("data");

                    short[] datas = new short[codesArray.length()];
                    for (int j = 0; j < codesArray.length(); j++) {
                        String code = codesArray.getString(j);
                        // 解析 "0xXX" 格式的十六进制字符串
                        datas[j] = (short) Integer.parseInt(code.substring(2), 16);
                    }
                    resultList.add(new CustomKeyData(name, datas));
                }
            }
        } catch (Exception e) {
            LimeLog.warning("Exception while loading keys from SharedPreferences: " + e.getMessage());
            Toast.makeText(game, getString(R.string.toast_load_custom_keys_corrupted), Toast.LENGTH_SHORT).show();
        }

        return resultList;
    }

    /**
     * 设置自定义按键卡片
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    private void setupCustomKeysCard(View customView) {
        View cardContainer = customView.findViewById(R.id.customKeysCardContainer);
        LinearLayout listLayout = customView.findViewById(R.id.customKeysListLayout);

        if (cardContainer == null || listLayout == null) return;

        // 检查配置开关
        if (!game.prefConfig.showQuickKeyCard) {
            cardContainer.setVisibility(View.GONE);
            return;
        }

        List<CustomKeyData> keys = getSavedCustomKeys();
        if (keys.isEmpty()) {
            cardContainer.setVisibility(View.GONE);
            return;
        }

        // 显示容器
        cardContainer.setVisibility(View.VISIBLE);
        listLayout.removeAllViews();

        // 3. 循环创建美化的列表项
        for (int i = 0; i < keys.size(); i++) {
            CustomKeyData keyData = keys.get(i);

            // --- 创建文本项  ---
            TextView itemView = new TextView(game);
            itemView.setText(keyData.name);

            // 样式设置
            itemView.setTextColor(0xFF333333); // 灰色文字
            itemView.setTextSize(14); // 字体大小
            itemView.setGravity(android.view.Gravity.CENTER); // 文字居中

            // 设置内边距 (Padding)
            int paddingVertical = dpToPx(7);
            int paddingHorizontal = dpToPx(10);
            itemView.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);

            // 布局参数
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            itemView.setLayoutParams(params);

            // 添加点击效果
            itemView.setBackground(game.getDrawable(R.drawable.button_selector_background));

            // 点击事件
            itemView.setOnClickListener(v -> {
                sendKeys(keyData.keys);
                // 震动反馈
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            });

            // 添加 Item
            listLayout.addView(itemView);

            // --- 添加分割线 (除了最后一个) ---
            if (i < keys.size() - 1) {
                View divider = new View(game);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1 // 高度 1px
                );
                // 移除左右边距
                dividerParams.setMargins(0, 0, 0, 0);

                divider.setLayoutParams(dividerParams);
                divider.setBackgroundColor(0x33000000); // 20% 透明度的黑色
                listLayout.addView(divider);
            }
        }
    }

    // 辅助方法：dp转px
    private int dpToPx(float dp) {
        return (int) (dp * game.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 设置自定义标题
     */
    private void setupCustomTitleBar(View customView, String title) {
        TextView titleTextView = customView.findViewById(R.id.customTitleTextView);
        if (titleTextView != null) {
            titleTextView.setText(title);
        }
        
        // 设置王冠按钮的下划线样式和动态文本
        TextView crownToggleButton = customView.findViewById(R.id.btnCrownToggle);
        if (crownToggleButton != null) {
            // 根据王冠功能状态设置文本
            String crownText = game.isCrownFeatureEnabled() ? getString(R.string.crown_switch_to_normal) : getString(R.string.crown_switch_to_crown);
            crownToggleButton.setText(Html.fromHtml("<u>" + crownText + "</u>"));
            crownToggleButton.setOnClickListener(v -> {
                // 先切换状态
                boolean wasEnabled = game.isCrownFeatureEnabled();
                toggleCrownFeature();
                // 根据切换后的状态更新文本
                String newCrownText = !wasEnabled ? getString(R.string.crown_switch_to_normal) : getString(R.string.crown_switch_to_crown);
                crownToggleButton.setText(Html.fromHtml("<u>" + newCrownText + "</u>"));
            });
        }
    }

    /**
     * 设置当前串流应用信息 (名字、HDR支持)
     */
    @SuppressLint("SetTextI18n")
    private void setupAppNameDisplay(View customView) {
        try {
            // 获取当前串流应用的名字
            String appName = app.getAppName();
            // 获取当前串流应用的HDR支持状态
            boolean hdrSupported = app.isHdrSupported();
            
            // 找到App名字显示的TextView
            TextView appNameTextView = customView.findViewById(R.id.appNameTextView);
            appNameTextView.setText(appName + " (" + (hdrSupported ? "HDR: Supported" : "HDR: Unknown") + ")");
        } catch (Exception e) {
            // 如果获取失败，使用默认名字
            TextView appNameTextView = customView.findViewById(R.id.appNameTextView);
            if (appNameTextView != null) {
                appNameTextView.setText("Moonlight V+");
            }
        }
    }

    /**
     * 设置快捷按钮
     */
    private void setupQuickButtons(View customView, AlertDialog dialog) {
        // 创建动画
        android.view.animation.Animation scaleDown = android.view.animation.AnimationUtils.loadAnimation(game, R.anim.button_scale_animation);
        android.view.animation.Animation scaleUp = android.view.animation.AnimationUtils.loadAnimation(game, R.anim.button_scale_restore);
        
        // 设置按钮点击动画
        setupButtonWithAnimation(customView.findViewById(R.id.btnEsc), scaleDown, scaleUp, v ->
                sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE}));

        setupButtonWithAnimation(customView.findViewById(R.id.btnWin), scaleDown, scaleUp, v ->
                sendKeys(new short[]{KeyboardTranslator.VK_LWIN}));

        setupButtonWithAnimation(customView.findViewById(R.id.btnHDR), scaleDown, scaleUp, v ->
                sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_MENU, KeyboardTranslator.VK_B}));

        // 设置麦克风按钮，根据设置决定是否启用
        View micButton = customView.findViewById(R.id.btnMic);
        if (game.prefConfig != null && game.prefConfig.enableMic) {
            // 麦克风重定向已开启，启用按钮
            setupButtonWithAnimation(micButton, scaleDown, scaleUp, v -> toggleMicrophone());
        } else {
            // 麦克风重定向未开启，禁用按钮
            micButton.setEnabled(false);
            micButton.setAlpha(0.5f);
            // 设置禁用图标
            if (micButton instanceof android.widget.Button) {
                android.widget.Button button = (android.widget.Button) micButton;
                button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_mic_gm_disabled, 0, 0, 0);
            }
            micButton.setOnClickListener(v -> {
                // 显示提示信息
                Toast.makeText(game, "请在设置中开启麦克风重定向", Toast.LENGTH_SHORT).show();
            });
        }

        setupButtonWithAnimation(customView.findViewById(R.id.btnSleep), scaleDown, scaleUp, v -> {
            sendKeys(new short[]{KeyboardTranslator.VK_LWIN, 88});
            handler.postDelayed(() -> sendKeys(new short[]{85, 83}), SLEEP_DELAY);
        });

        setupButtonWithAnimation(customView.findViewById(R.id.btnQuit), scaleDown, scaleUp, v -> {
            if (game.prefConfig.swapQuitAndDisconnect) {
                game.disconnect();
            }
            else {
                disconnectAndQuit();
            }
        });
    }

    /**
     * 为按钮设置动画效果
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupButtonWithAnimation(View button, android.view.animation.Animation scaleDown,
                                          android.view.animation.Animation scaleUp, View.OnClickListener listener) {
        // 设置按钮样式
        if (button instanceof android.widget.Button) {
            android.widget.Button btn = (android.widget.Button) button;
            btn.setTextAppearance(game, R.style.GameMenuButtonStyle);
        }

        // 设置按钮支持焦点
        button.setFocusable(true);
        button.setClickable(true);
        button.setFocusableInTouchMode(true);

        // 设置触摸事件
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.startAnimation(scaleDown);
                    // 添加按下状态的视觉反馈
                    v.setAlpha(0.8f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.startAnimation(scaleUp);
                    // 恢复透明度
                    v.setAlpha(1.0f);
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        // 添加点击反馈
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        listener.onClick(v);
                    }
                    break;
            }
            return true;
        });

        // 设置键盘事件支持（手柄和遥控器）
        setupButtonKeyListener(button, scaleDown, scaleUp, listener);
    }

    /**
     * 通用按钮键盘事件处理方法
     */
    private void setupButtonKeyListener(View button, android.view.animation.Animation scaleDown,
                                        android.view.animation.Animation scaleUp, View.OnClickListener listener) {
        button.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    // 添加点击反馈
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    // 播放动画
                    v.startAnimation(scaleDown);
                    v.postDelayed(() -> {
                        v.startAnimation(scaleUp);
                        listener.onClick(v);
                    }, 100);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * 通用菜单设置方法
     */
    private void setupMenu(ListView listView, ArrayAdapter<MenuOption> adapter, AlertDialog dialog) {
        // 设置ListView支持手柄和遥控导航
        listView.setItemsCanFocus(true);

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            MenuOption option = adapter.getItem(pos);
            // 在执行前清除子菜单打开标志
            lastActionOpenedSubmenu = false;
            if (option != null) {
                run(option);
            }

            // 根据选项或运行结果决定是否关闭 dialog：如果该选项需要保留 dialog（如打开二级菜单），或最近操作已打开子菜单，则不关闭
            boolean shouldKeep = (option != null && option.isKeepDialog()) || lastActionOpenedSubmenu;
            if (!shouldKeep) {
                dialog.dismiss();
            }
            // 重置标志
            lastActionOpenedSubmenu = false;
        });
    }

    /**
     * 设置普通菜单
     */
    private void setupNormalMenu(View customView, MenuOption[] normalOptions, AlertDialog dialog) {
        GameMenuAdapter normalAdapter = new GameMenuAdapter(game, normalOptions);
        ListView normalListView = customView.findViewById(R.id.gameMenuList);
        normalListView.setAdapter(normalAdapter);
        setupMenu(normalListView, normalAdapter, dialog);
    }

    /**
     * 在现有对话框中替换普通菜单区域为新的选项（用于二级菜单），并将当前状态推入栈以便回退
     */
    private void replaceNormalMenuInDialog(AlertDialog dialog, String title, MenuOption[] newNormalOptions, boolean pushToStack) {
        if (dialog == null || dialog.getWindow() == null) return;
        // 首先尝试使用保存的 custom view 引用
        View customView = this.activeCustomView;
        if (customView == null) {
            customView = dialog.findViewById(android.R.id.content);
        }
        // dialog 的自定义视图可能不在 android.R.id.content，尝试通过 getLayoutInflater 找到
        if (customView == null) {
            // 通过反向查找原始创建时的视图引用
            customView = dialog.getWindow().getDecorView().findViewById(android.R.id.content);
        }

        if (customView == null) return;

        // 更新标题
        TextView titleTextView = customView.findViewById(R.id.customTitleTextView);
        if (titleTextView != null && title != null) titleTextView.setText(title);

        // 更新普通菜单列表
        ListView normalListView = customView.findViewById(R.id.gameMenuList);
        if (normalListView != null) {
            GameMenuAdapter adapter = new GameMenuAdapter(game, newNormalOptions);
            normalListView.setAdapter(adapter);
            setupMenu(normalListView, adapter, dialog);
        }

        // 可选地推入栈
        if (pushToStack) {
            // 因为调用者可能已经将当前状态入栈，只有当需要时再入栈
            menuStack.push(new MenuState(title, newNormalOptions));
        }
    }

    /**
     * 在当前打开的 dialog 中显示一个子菜单（保持超级菜单不变）
     */
    private void showSubMenu(String title, MenuOption[] subOptions) {
        if (activeDialog != null && activeDialog.isShowing()) {
            // 表示接下来要打开子菜单，避免点击后被自动 dismiss
            lastActionOpenedSubmenu = true;
            // 尝试读取当前普通菜单并保存到栈，以便回退
            if (this.activeCustomView != null) {
                // 获取当前标题
                TextView titleTextView = this.activeCustomView.findViewById(R.id.customTitleTextView);
                String currentTitle = titleTextView != null ? titleTextView.getText().toString() : null;
                
                ListView normalListView = this.activeCustomView.findViewById(R.id.gameMenuList);
                if (normalListView != null && normalListView.getAdapter() != null) {
                    int count = normalListView.getAdapter().getCount();
                    MenuOption[] currentOptions = new MenuOption[count];
                    for (int i = 0; i < count; i++) {
                        currentOptions[i] = (MenuOption) normalListView.getAdapter().getItem(i);
                    }
                    menuStack.push(new MenuState(currentTitle, currentOptions));
                }
            }

            // 替换为子菜单（不再自动将子菜单入栈，因为已经保存了当前状态）
            replaceNormalMenuInDialog(activeDialog, title, subOptions, false);
        } else {
            // 没有活动 dialog，则创建新的
            showMenuDialog(title, subOptions, new MenuOption[0]);
        }
    }

    /**
     * 设置超级菜单
     */
    private void setupSuperMenu(View customView, MenuOption[] superOptions, AlertDialog dialog) {
        ListView superListView = customView.findViewById(R.id.superMenuList);

        if (superOptions.length > 0) {
            SuperMenuAdapter superAdapter = new SuperMenuAdapter(game, superOptions);
            superListView.setAdapter(superAdapter);
            setupMenu(superListView, superAdapter, dialog);
        } else {
            setupEmptySuperMenu(superListView);
        }
    }

    /**
     * 设置空的超级菜单
     */
    private void setupEmptySuperMenu(ListView superListView) {
        // 计算当前显示的卡片数量
        int visibleCardCount = 0;
        if (game.prefConfig.showBitrateCard) visibleCardCount++;
        if (game.prefConfig.showGyroCard) visibleCardCount++;
        if (game.prefConfig.showQuickKeyCard) visibleCardCount++;
        
        // 根据卡片数量决定使用哪个布局
        int layoutRes = (visibleCardCount >= 2 | game.prefConfig.showQuickKeyCard) ?
            R.layout.game_menu_super_empty_text_only : 
            R.layout.game_menu_super_empty;
            
        View emptyView = LayoutInflater.from(game).inflate(layoutRes, superListView, false);
        ViewGroup parent = (ViewGroup) superListView.getParent();
        parent.addView(emptyView);
        superListView.setEmptyView(emptyView);
        SuperMenuAdapter emptyAdapter = new SuperMenuAdapter(game, new MenuOption[0]);
        superListView.setAdapter(emptyAdapter);
    }

    /**
     * 设置对话框属性
     */
    private void setupDialogProperties(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            WindowManager.LayoutParams layoutParams = dialog.getWindow().getAttributes();
            layoutParams.alpha = DIALOG_ALPHA;
            layoutParams.dimAmount = DIALOG_DIM_AMOUNT;
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            
            dialog.getWindow().setAttributes(layoutParams);
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.game_menu_dialog_bg);
        }
    }

    /**
     * 显示特殊按键菜单（从rew加载默认配置，可自定义和添加选项）
     */
    private void showSpecialKeysMenu() {
        List<MenuOption> options = new ArrayList<>();

        // 从 SharedPreferences 加载所有按键。
        // 如果是首次运行，则从 res/raw/default_special_keys.json 加载默认值。
        boolean hasKeys = loadAndAddAllKeys(options);

        // 添加 "添加自定义按键" 选项
        options.add(new MenuOption(getString(R.string.game_menu_add_custom_key), false, this::showAddCustomKeyDialog, null, false));

        // 如果存在任何按键 (默认或自定义)，则添加 "删除" 选项
        if (hasKeys) {
            options.add(new MenuOption(getString(R.string.game_menu_delete_custom_key), false, this::showDeleteKeysDialog, null, false));
        }

        // 添加 "取消" 选项
        options.add(new MenuOption(getString(R.string.game_menu_cancel), false, null, null, false));

        //  显示为子菜单
        showSubMenu(getString(R.string.game_menu_send_keys), options.toArray(new MenuOption[0]));
    }


    /**
     * 加载所有按键并添加到菜单选项列表中
     * @param options 用于填充菜单选项的列表
     * @return 如果成功加载了至少一个按键，则返回 true
     */
    private boolean loadAndAddAllKeys(List<MenuOption> options) {
        List<CustomKeyData> loadedKeys = getSavedCustomKeys();

        if (loadedKeys.isEmpty()) {
            return false;
        }

        // 将数据转换为菜单选项
        for (CustomKeyData keyData : loadedKeys) {
            MenuOption option = new MenuOption(
                    keyData.name,
                    false,
                    () -> sendKeys(keyData.keys),
                    null,
                    false
            );
            options.add(option);
        }

        return true;
    }

    /**
     * 从 res/raw 目录读取资源文件内容并返回字符串。
     * @param resourceId 资源文件的 ID (例如 R.raw.default_special_keys)
     * @return 文件内容的字符串，如果失败则返回空字符串
     */
    private String readRawResourceAsString(int resourceId) {
        try (InputStream inputStream = game.getResources().openRawResource(resourceId);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } catch (IOException e) {
            LimeLog.warning("Failed to read raw resource file: " + resourceId + ": " + e);
            return "";
        }
    }

    /**
     * 将新的自定义按键保存到 SharedPreferences
     * @param name 按键的显示名称
     * @param keysString 逗号分隔的十六进制按键码字符串
     */
    private void saveCustomKey(String name, String keysString) {
        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME, "{\"data\":[]}"); // 如果为空，提供默认JSON结构

        try {
            // 解析按键码
            String[] keyParts = keysString.split(",");
            JSONArray keyCodesArray = new JSONArray();
            for (String part : keyParts) {
                String trimmedPart = part.trim();
                // 简单验证是否是 "0x" 开头的十六进制
                if (!trimmedPart.startsWith("0x")) {
                    Toast.makeText(game, R.string.toast_key_code_format_error, Toast.LENGTH_LONG).show();
                    return;
                }
                keyCodesArray.put(trimmedPart);
            }

            // 读取现有的JSON数据
            JSONObject root = new JSONObject(value);
            JSONArray dataArray = root.getJSONArray("data");

            // 创建新的JSON对象并添加
            JSONObject newKeyEntry = new JSONObject();
            newKeyEntry.put("name", name);
            newKeyEntry.put("data", keyCodesArray);
            dataArray.put(newKeyEntry);

            // 写回SharedPreferences
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(KEY_NAME, root.toString());
            editor.apply();

            Toast.makeText(game, game.getString(R.string.toast_custom_key_saved, name), Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            LimeLog.warning("Exception while saving custom key" + e.getMessage());
            Toast.makeText(game, R.string.toast_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAddCustomKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(game, R.style.VirtualKeyboardDialogStyle);

        View dialogView = LayoutInflater.from(game).inflate(R.layout.dialog_add_custom_key, null);
        builder.setView(dialogView);

        final LinearLayout dialogContent = dialogView.findViewById(R.id.dialog_content);
        final EditText nameInput = dialogView.findViewById(R.id.edit_text_key_name);
        final TextView keysDisplay = dialogView.findViewById(R.id.text_view_key_codes);
        final Button clearButton = dialogView.findViewById(R.id.button_clear_keys);
        final Button closeButton = dialogView.findViewById(R.id.button_close_dialog);
        final Button saveButton = dialogView.findViewById(R.id.button_save_key);

        AlertDialog dialog = builder.create();

        // 设置返回键监听器，返回到二级菜单
        dialog.setOnKeyListener((dialogInterface, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                dialog.dismiss();
                return true; // 消费返回键事件
            }
            return false;
        });

        // 关闭按钮事件
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }

        // 点击背景关闭对话框
        if (dialogView instanceof FrameLayout) {
            FrameLayout rootLayout = (FrameLayout) dialogView;
            rootLayout.setOnClickListener(v -> {
                // 只有点击背景区域才关闭对话框
                dialog.dismiss();
            });
            
            // 防止内容区域的点击事件传播到背景
            View contentArea = rootLayout.getChildAt(0); // ScrollView
            if (contentArea != null) {
                contentArea.setOnClickListener(v -> {
                    // 阻止事件传播，不关闭对话框
                });
            }
        }

        // 初始化/重置 TextView 的数据存储 (tag) 和显示 (text)
        keysDisplay.setTag("");
        keysDisplay.setText("");
        keysDisplay.setHint(R.string.dialog_hint_key_codes);

        // 清空按钮: 同时清空数据(tag)和显示(text)
        clearButton.setOnClickListener(v -> {
            keysDisplay.setTag("");
            keysDisplay.setText("");
        });

        // 递归设置键盘监听器
        setupCompactKeyboardListeners(dialogView.findViewById(R.id.keyboard_drawing), keysDisplay);

        // 保存按钮事件
        if (saveButton != null) {
            saveButton.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String androidKeyCodesStr = keysDisplay.getTag().toString(); // 从 tag 获取原始数据

            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(androidKeyCodesStr)) {
                Toast.makeText(game, R.string.toast_name_and_codes_cannot_be_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            // 将 Android KeyCodes 字符串转换为 Windows KeyCodes 字符串
            String[] androidCodes = androidKeyCodesStr.split(",");
            StringBuilder windowsCodesBuilder = new StringBuilder();
            for (int i = 0; i < androidCodes.length; i++) {
                try {
                    int code = Integer.parseInt(androidCodes[i]);
                    String windowsCode = KeyCodeMapper.getWindowsKeyCode(code);
                    if (windowsCode == null) throw new NullPointerException(); // 如果找不到映射，则抛出异常

                    windowsCodesBuilder.append(windowsCode).append(i < androidCodes.length - 1 ? "," : "");
                } catch (Exception e) {
                    Toast.makeText(game, "error: invalid key code", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            saveCustomKey(name, windowsCodesBuilder.toString());
            dialog.dismiss();
            });
        }

        // 显示对话框
        dialog.show();
        dialogContent.setMinimumHeight(game.getResources().getDisplayMetrics().heightPixels);
    }

    /**
     * 键盘监听器设置方法
     * @param parent 键盘布局的根视图
     * @param keysDisplay 用于存储和显示按键的 TextView
     */
    private void setupCompactKeyboardListeners(ViewGroup parent, final TextView keysDisplay) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) {
                setupCompactKeyboardListeners((ViewGroup) child, keysDisplay); // 递归
            } else if (child instanceof TextView && child.getTag() != null) {
                child.setOnClickListener(v -> {
                    String androidKeyCode = v.getTag().toString();
                    String currentTag = keysDisplay.getTag().toString();

                    // 1. 更新数据 (Tag)
                    String newTag = currentTag.isEmpty() ? androidKeyCode : currentTag + "," + androidKeyCode;
                    keysDisplay.setTag(newTag);

                    // 2. 更新显示 (Text)
                    String currentText = keysDisplay.getText().toString();
                    String displayName = KeyCodeMapper.getDisplayName(Integer.parseInt(androidKeyCode));
                    String newText = currentText.isEmpty() ? displayName : currentText + " + " + displayName;
                    keysDisplay.setText(newText);
                });
            }
        }
    }

    /**
     * 显示一个对话框，列出所有自定义按键，允许用户选择并删除。
     */
    private void showDeleteKeysDialog() {
        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME, "");

        if (TextUtils.isEmpty(value)) {
            Toast.makeText(game, R.string.toast_no_custom_keys_to_delete, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject root = new JSONObject(value);
            JSONArray dataArray = root.optJSONArray("data");

            if (dataArray == null || dataArray.length() == 0) {
                Toast.makeText(game, R.string.toast_no_custom_keys_to_delete, Toast.LENGTH_SHORT).show();
                return;
            }

            // 准备列表和选中状态
            final List<String> keyNames = new ArrayList<>();
            for (int i = 0; i < dataArray.length(); i++) {
                keyNames.add(dataArray.getJSONObject(i).optString("name"));
            }
            final boolean[] checkedItems = new boolean[keyNames.size()];

            // 创建并显示对话框
            AlertDialog.Builder builder = new AlertDialog.Builder(game, R.style.AppDialogStyle);
            builder.setTitle(R.string.dialog_title_select_keys_to_delete)
                    .setMultiChoiceItems(keyNames.toArray(new CharSequence[0]), checkedItems, (dialog, which, isChecked) ->
                            checkedItems[which] = isChecked)
                    .setPositiveButton(R.string.dialog_button_delete, (dialog, which) -> {
                        try {
                            // 从后往前删除选中项
                            for (int i = checkedItems.length - 1; i >= 0; i--) {
                                if (checkedItems[i]) {
                                    dataArray.remove(i);
                                }
                            }
                            // 保存更改
                            root.put("data", dataArray);
                            preferences.edit().putString(KEY_NAME, root.toString()).apply();
                            Toast.makeText(game, R.string.toast_selected_keys_deleted, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            LimeLog.warning("Exception while deleting keys" + e.getMessage());
                            Toast.makeText(game, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.dialog_button_cancel, null)
                    .create()
                    .show();

        } catch (Exception e) {
            LimeLog.warning("Exception while loading key list" + e.getMessage());
            Toast.makeText(game, R.string.toast_load_key_list_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 显示主菜单
     */
    private void showMenu() {
        List<MenuOption> normalOptions = new ArrayList<>();
        List<MenuOption> superOptions = new ArrayList<>();

        // 构建普通菜单项
        buildNormalMenuOptions(normalOptions);

        // 构建超级菜单项
        buildSuperMenuOptions(superOptions);

        showMenuDialog(GAME_MENU_TITLE,
                normalOptions.toArray(new MenuOption[0]),
                superOptions.toArray(new MenuOption[0]));
    }

    /**
     * 构建普通菜单选项
     */
    private void buildNormalMenuOptions(List<MenuOption> normalOptions) {
        normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_keyboard), true,
                game::toggleKeyboard, "game_menu_toggle_keyboard", true));

        normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_host_keyboard), true,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_O}),
                "game_menu_toggle_host_keyboard", true));

        // 此菜单是 UI 操作，不应该依赖游戏窗口焦点
        normalOptions.add(new MenuOption(
                getTouchModeDescription(),
                false,
                this::showTouchModeMenu,
                "mouse_mode",
                true, true));

        normalOptions.add(new MenuOption(
                game.getisTouchOverrideEnabled()?"关闭平移/缩放":"开启平移/缩放",
                false,
                () -> {
                    Toast.makeText(game, game.getisTouchOverrideEnabled()?"已关闭平移/缩放":"已开启平移/缩放", Toast.LENGTH_SHORT).show();
                    game.setisTouchOverrideEnabled(!game.getisTouchOverrideEnabled());
                },
                "game_menu_mouse_emulation",
                true
        ));

        // 王冠功能 - 只在开启王冠功能时显示
        if (game.isCrownFeatureEnabled()) {
            normalOptions.add(new MenuOption(
                    getString(R.string.game_menu_crown_function),
                    false,
                    this::showCrownFunctionMenu,
                    "crown_function_menu",
                    true,
                    true
            ));
        }

        if (device != null) {
            normalOptions.addAll(device.getGameMenuOptions());
        }

        // 性能显示
        normalOptions.add(new MenuOption(
                getPerfOverlayMenuLabel(),
                false,
                ()->{
                    game.togglePerformanceOverlay();
                    rebuildAndReplaceMenu();
                },
                "game_menu_toggle_performance_overlay",
                true,
                 true
        ));

        normalOptions.add(new MenuOption(
                getString(R.string.game_menu_change_resolution),
                false,
                this::showResolutionMenu,
                "game_menu_change_resolution",
                true,
                true
        ));

        // 只有在启用了虚拟手柄时才显示虚拟手柄切换选项
        if (game.prefConfig.onscreenController) {
            normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_virtual_controller),
                    false, game::toggleVirtualController, "game_menu_toggle_virtual_controller", true));
        }

        normalOptions.add(new MenuOption(getString(R.string.game_menu_send_keys),
                false, this::showSpecialKeysMenu, "game_menu_send_keys", true, true));

        // 本地测试震动
        // normalOptions.add(new MenuOption("震动测试", false, this::testLocalRumbleAll, "game_menu_test_local_rumble", true));

        normalOptions.add(new MenuOption(getString(R.string.game_menu_disconnect), true,
                game::disconnect, "game_menu_disconnect", true));

        normalOptions.add(new MenuOption(getString(R.string.game_menu_disconnect_and_quit), true,
                () -> {
                    if (game.prefConfig.lockScreenAfterDisconnect) {
                        lockAndDisconnectWithDelay();
                    }
                    else disconnectAndQuit();
                }, "game_menu_disconnect_and_quit", true));

        // normalOptions.add(new MenuOption(getString(R.string.game_menu_cancel), false, null, null, true));
    }

    private String getTouchModeDescription() {
        String touchModeText = getString(R.string.game_menu_switch_touch_mode) + ": ";

        if (game.prefConfig.enableNativeMousePointer) {
            touchModeText += getString(R.string.game_menu_touch_mode_native_mouse);
        } else if (game.prefConfig.touchscreenTrackpad) {
            touchModeText += getString(R.string.game_menu_touch_mode_trackpad);
        } else if (game.prefConfig.enableEnhancedTouch) {
            touchModeText += getString(R.string.game_menu_touch_mode_enhanced);
        } else {
            touchModeText += getString(R.string.game_menu_touch_mode_classic);
        }
        return touchModeText;
    }

    private String getPerfOverlayMenuLabel() {
        String status;

        // 1. 如果未开启 -> 显示 "关闭"
        if (!game.prefConfig.enablePerfOverlay) {
            status = getString(R.string.perf_overlay_hidden);
        }
        // 2. 如果开启且锁定 -> 显示 "固定"
        else if (game.prefConfig.perfOverlayLocked) {
            status = getString(R.string.perf_overlay_locked);
        }
        // 3. 如果开启且未锁定 -> 显示 "悬浮"
        else {
            status = getString(R.string.perf_overlay_floating);
        }

        // 拼接结果，例如 "性能监控：悬浮"
        return getString(R.string.game_menu_toggle_performance_overlay) + ": " + status;
    }

    // 由于不能直接发送win+L来锁定屏幕，可以先打开Windows的屏幕键盘，再发送win+L
    public void lockAndDisconnectWithDelay() {
        //需要用户先自行打开屏幕键盘
        // 发送 Win+L 锁定屏幕
        sendKeys(new short[]{
                KeyboardTranslator.VK_LWIN,
                KeyboardTranslator.VK_L
        });
        // 断开并退出串流
        disconnectAndQuit();
    }

    /**
     * 构建超级菜单选项
     */
    private void buildSuperMenuOptions(List<MenuOption> superOptions) {
        JsonArray cmdList = app.getCmdList();
        if (cmdList != null) {
            for (int i = 0; i < cmdList.size(); i++) {
                JsonObject cmd = cmdList.get(i).getAsJsonObject();
                superOptions.add(new MenuOption(cmd.get("name").getAsString(), true, () -> {
                    try {
                        conn.sendSuperCmd(cmd.get("id").getAsString());
                    } catch (IOException | XmlPullParserException e) {
                        Toast.makeText(game, "发送超级命令时发生错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }, null, false)); // 超级指令菜单不显示图标
            }
        }
    }

    /**
     * 获取菜单项图标
     */
    private static int getIconForMenuOption(String iconKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return ICON_MAP.getOrDefault(iconKey, R.drawable.ic_menu_item_default);
        }
        return -1;
    }

    /**
     * 自定义适配器用于显示美化的菜单项
     */
    private static class GameMenuAdapter extends ArrayAdapter<MenuOption> {
        private final Context context;

        public GameMenuAdapter(Context context, MenuOption[] options) {
            super(context, 0, options);
            this.context = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.game_menu_list_item, parent, false);
            }

            MenuOption option = getItem(position);
            if (option != null) {
                TextView textView = convertView.findViewById(R.id.menu_item_text);
                ImageView iconView = convertView.findViewById(R.id.menu_item_icon);

                textView.setText(option.getLabel());
                
                if (option.isShowIcon()) {
                    iconView.setImageResource(getIconForMenuOption(option.getIconKey()));
                    iconView.setVisibility(View.VISIBLE);
                } else {
                    iconView.setVisibility(View.GONE);
                }
            }

            return convertView;
        }
    }

    /**
     * 超级菜单适配器
     */
    private static class SuperMenuAdapter extends ArrayAdapter<MenuOption> {
        private final Context context;

        public SuperMenuAdapter(Context context, MenuOption[] options) {
            super(context, 0, options);
            this.context = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.game_menu_list_item, parent, false);
            }

            MenuOption option = getItem(position);
            if (option != null) {
                TextView textView = convertView.findViewById(R.id.menu_item_text);
                ImageView iconView = convertView.findViewById(R.id.menu_item_icon);

                textView.setText(option.getLabel());
                
                if (option.isShowIcon()) {
                    iconView.setImageResource(R.drawable.ic_cmd_cute);
                    iconView.setVisibility(View.VISIBLE);
                } else {
                    iconView.setVisibility(View.GONE);
                }
            }

            return convertView;
        }
    }
}
