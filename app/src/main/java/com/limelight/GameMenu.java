package com.limelight;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Html;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.KeyboardPacket;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // 图标映射缓存
    private static final Map<String, Integer> ICON_MAP = new HashMap<>();

    static {
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
            this.label = label;
            this.withGameFocus = withGameFocus;
            this.runnable = runnable;
            this.iconKey = iconKey;
            this.showIcon = showIcon;
        }

        public String getLabel() { return label; }
        public boolean isWithGameFocus() { return withGameFocus; }
        public Runnable getRunnable() { return runnable; }
        public String getIconKey() { return iconKey; }
        public boolean isShowIcon() { return showIcon; }
    }

    // 实例变量
    private final Game game;
    private final NvApp app;
    private final NvConnection conn;
    private final GameInputDevice device;
    private final Handler handler;

    public GameMenu(Game game, NvApp app, NvConnection conn, GameInputDevice device) {
        this.game = game;
        this.app = app;
        this.conn = conn;
        this.device = device;
        this.handler = new Handler();

        showMenu();
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
     * 切换增强触摸模式
     */
    private void toggleEnhancedTouch() {
        game.prefConfig.enableEnhancedTouch = !game.prefConfig.enableEnhancedTouch;
        String message = game.prefConfig.enableEnhancedTouch ? "增强式多点触控已开启" : "经典鼠标模式已开启";
        Toast.makeText(game, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * 切换麦克风开关
     */
    private void toggleMicrophone() {
        // 切换GameView中麦克风按钮的显示/隐藏状态
        game.toggleMicrophoneButton();
    }

    /**
     * 切换王冠功能
     */
    private void toggleCrownFeature() {
        game.setCrownFeatureEnabled(!game.isCrownFeatureEnabled());
        Toast.makeText(game, game.isCrownFeatureEnabled() ? getString(R.string.crown_switch_to_crown) : getString(R.string.crown_switch_to_normal), Toast.LENGTH_SHORT).show();
    }

    /**
     * 显示码率调整菜单（滑动条版本）
     */
    private void showBitrateAdjustmentMenu() {
        // 创建自定义对话框布局
        View dialogView = LayoutInflater.from(game).inflate(R.layout.bitrate_slider_dialog, null);
        
        // 获取当前码率
        int currentBitrate = conn.getCurrentBitrate();
        int currentBitrateMbps = currentBitrate / 1000;
        
        // 设置当前码率显示
        TextView currentBitrateText = dialogView.findViewById(R.id.current_bitrate_text);
        currentBitrateText.setText(String.format(getString(R.string.game_menu_bitrate_current), currentBitrateMbps));
        
        // 设置滑动条
        SeekBar bitrateSeekBar = dialogView.findViewById(R.id.bitrate_seekbar);
        TextView bitrateValueText = dialogView.findViewById(R.id.bitrate_value_text);
        
        // 设置滑动条范围：500-200000 kbps
        bitrateSeekBar.setMax(1995); // 200000 - 500 = 199500，每100kbps一个单位
        bitrateSeekBar.setProgress((currentBitrate - 500) / 100);
        
        // 显示当前值
        bitrateValueText.setText(String.format("%d Mbps", currentBitrateMbps));
        
        // 滑动条变化监听
        bitrateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int newBitrate = (progress * 100) + 500; // 500 + progress * 100
                    int newBitrateMbps = newBitrate / 1000;
                    bitrateValueText.setText(String.format("%d Mbps", newBitrateMbps));
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 开始拖动时不做任何操作
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 停止拖动时只更新显示，不应用码率调整
                // 码率调整将在点击确认按钮时进行
            }
        });
        
        // 创建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(game, R.style.GameMenuDialogStyle);
        builder.setTitle(getString(R.string.game_menu_adjust_bitrate))
               .setView(dialogView)
               .setPositiveButton(getString(R.string.game_menu_ok), (dialog, which) -> {
                   // 确认时调整码率
                   int newBitrate = (bitrateSeekBar.getProgress() * 100) + 500;
                   adjustBitrate(newBitrate);
               })
               .setNegativeButton(getString(R.string.game_menu_cancel), (dialog, which) -> {
                   dialog.dismiss();
               });
        
        AlertDialog dialog = builder.create();
        dialog.show();
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

        // 设置自定义标题栏
        setupCustomTitleBar(customView, title);

        // 动态设置菜单列表区域高度
        setupMenuListHeight(customView);
        
        // 设置App名字显示
        setupAppNameDisplay(customView);

        // 设置快捷按钮
        setupQuickButtons(customView, dialog);

        // 设置普通菜单
        setupNormalMenu(customView, normalOptions, dialog);

        // 设置超级菜单
        setupSuperMenu(customView, superOptions, dialog);

        // 设置对话框属性
        setupDialogProperties(dialog);

        dialog.show();
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

    /**
     * 动态设置菜单列表区域高度
     * 最大高度就是内容实际高度，不做屏幕高度约束
     */
    private void setupMenuListHeight(View customView) {
        customView.post(() -> {
            View menuListContainer = customView.findViewById(R.id.menuListContainer);
            if (menuListContainer == null) return;

            float density = game.getResources().getDisplayMetrics().density;
            int minHeight = (int) (220 * density);

            int contentHeight = 0;
            try {
                contentHeight = calculateContentHeight(menuListContainer);
            } catch (Exception ignored) {}

            int finalHeight = Math.max(minHeight, contentHeight);
            ViewGroup.LayoutParams lp = menuListContainer.getLayoutParams();
            if (lp != null) {
                lp.height = finalHeight > 0 ? finalHeight : minHeight;
                menuListContainer.setLayoutParams(lp);
            }
        });
    }

    /**
     * 计算内容实际高度
     * 使用性能优化的方式计算
     */
    private int calculateContentHeight(View container) {
        try {
            // 获取ListView
            ListView normalListView = container.findViewById(R.id.gameMenuList);
            ListView superListView = container.findViewById(R.id.superMenuList);
            
            int totalHeight = 0;
            
            // 计算普通菜单高度
            if (normalListView != null && normalListView.getAdapter() != null) {
                int normalItemCount = normalListView.getAdapter().getCount();
                if (normalItemCount > 0) {
                    // 获取单个item的高度
                    View itemView = normalListView.getAdapter().getView(0, null, normalListView);
                    itemView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                   View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int itemHeight = itemView.getMeasuredHeight();
                    
                    // 计算总高度（最多显示8个item）
                    int maxItems = Math.min(normalItemCount, 8);
                    totalHeight = Math.max(totalHeight, itemHeight * maxItems);
                }
            }
            
            // 计算超级菜单高度
            if (superListView != null && superListView.getAdapter() != null) {
                int superItemCount = superListView.getAdapter().getCount();
                if (superItemCount > 0) {
                    // 获取单个item的高度
                    View itemView = superListView.getAdapter().getView(0, null, superListView);
                    itemView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                   View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int itemHeight = itemView.getMeasuredHeight();
                    
                    // 计算总高度（最多显示8个item）
                    int maxItems = Math.min(superItemCount, 8);
                    totalHeight = Math.max(totalHeight, itemHeight * maxItems);
                }
            }
            
            // 添加一些padding和margin
            totalHeight += (int) (32 * game.getResources().getDisplayMetrics().density);
            
            return totalHeight;
            
        } catch (Exception e) {
            // 如果计算失败，返回默认高度
            return (int) (220 * game.getResources().getDisplayMetrics().density);
        }
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

        setupButtonWithAnimation(customView.findViewById(R.id.btnQuit), scaleDown, scaleUp, v -> disconnectAndQuit());

        // 设置码率调整按钮
        setupButtonWithAnimation(customView.findViewById(R.id.btnBitrate), scaleDown, scaleUp, v -> {
            dialog.dismiss(); // 关闭当前对话框
            showBitrateAdjustmentMenu(); // 显示码率调整菜单
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
            if (option != null) {
                run(option);
            }
            dialog.dismiss();
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
        View emptyView = LayoutInflater.from(game).inflate(R.layout.game_menu_super_empty, superListView, false);
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
     * 显示特殊按键菜单
     */
    private void showSpecialKeysMenu() {
        MenuOption[] specialOptions = {
            new MenuOption(getString(R.string.game_menu_send_keys_f11), false,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_F11}), null, false),
            new MenuOption(getString(R.string.game_menu_send_keys_ctrl_v), false,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V}), null, false),
            new MenuOption(getString(R.string.game_menu_send_keys_win_d), false,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D}), null, false),
            new MenuOption(getString(R.string.game_menu_send_keys_win_g), false,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G}), null, false),
            new MenuOption(getString(R.string.game_menu_send_keys_alt_home), false,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_MENU, KeyboardTranslator.VK_HOME}), null, false),
            new MenuOption(getString(R.string.game_menu_send_keys_shift_tab), false,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB}), null, false),
            new MenuOption(getString(R.string.game_menu_cancel), false, null, null, false)
        };

        showMenuDialog(getString(R.string.game_menu_send_keys), specialOptions, new MenuOption[0]);
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

        normalOptions.add(new MenuOption(
                game.prefConfig.enableEnhancedTouch ? "切换到经典鼠标模式" : "切换到增强式多点触控",
                true, this::toggleEnhancedTouch, "mouse_mode", true));

        if (device != null) {
            normalOptions.addAll(device.getGameMenuOptions());
        }

        normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_performance_overlay),
                false, game::togglePerformanceOverlay, "game_menu_toggle_performance_overlay", true));

        // 只有在启用了虚拟手柄时才显示虚拟手柄切换选项
        if (game.prefConfig.onscreenController) {
            normalOptions.add(new MenuOption(getString(R.string.game_menu_toggle_virtual_controller),
                    false, game::toggleVirtualController, "game_menu_toggle_virtual_controller", true));
        }

        normalOptions.add(new MenuOption(getString(R.string.game_menu_send_keys),
                false, this::showSpecialKeysMenu, "game_menu_send_keys", true));

        normalOptions.add(new MenuOption(getString(R.string.game_menu_disconnect), true,
                game::disconnect, "game_menu_disconnect", true));

        normalOptions.add(new MenuOption(getString(R.string.game_menu_disconnect_and_quit), true,
                this::disconnectAndQuit, "game_menu_disconnect_and_quit", true));

        // normalOptions.add(new MenuOption(getString(R.string.game_menu_cancel), false, null, null, true));
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
