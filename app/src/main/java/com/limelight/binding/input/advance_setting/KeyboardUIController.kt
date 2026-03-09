package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class KeyboardUIController implements KeyboardGestureDetector.GestureListener {

    private final FrameLayout keyboardLayout;
    private final View keyboardContent;
    private final FrameLayout parentContainer;
    private final ControllerManager controllerManager;
    private final SeekBar opacitySeekbar;
    private final SharedPreferences prefs;

    private static final String PREF_NAME = "keyboard_settings";
    private static final String KEY_HEIGHT = "keyboard_height";
    private static final String KEY_OPACITY = "keyboard_opacity";
    private static final String KEY_X = "keyboard_x";
    private static final String KEY_Y = "keyboard_y";
    private static final String KEY_IS_MINI = "keyboard_is_mini";
    
    private View layoutMain, layoutNav, layoutNum, layoutMini;
    private TextView btnMain, btnNav, btnNum, btnMini;
    private TextView keyPopup;

    // Sticky Modifiers state: 0=Neutral, 1=Single, 2=Locked
    private final Map<Integer, Integer> modifierStates = new HashMap<>();
    // 追踪哪些修饰键正被手指物理按住（ACTION_DOWN 已触发，ACTION_UP 未到达）
    private final Set<Integer> physicallyHeldModifiers = new HashSet<>();
    private static final int MOD_NEUTRAL = 0;
    private static final int MOD_SINGLE = 1;
    private static final int MOD_LOCKED = 2;

    // KeyCodes for modifiers (based on tags)
    private static final int KEY_LCTRL = 113;
    private static final int KEY_RCTRL = 114;
    private static final int KEY_LSHIFT = 59;
    private static final int KEY_RSHIFT = 60;
    private static final int KEY_LALT = 57;
    private static final int KEY_RALT = 58;
    private static final int KEY_LWIN = 117;
    private static final int KEY_SPACE = 62;

    private View panelAlpha;
    private View panelNumMini;
    private View panelPcMini;

    public KeyboardUIController(FrameLayout container, ControllerManager controllerManager, Context context){
        this.parentContainer = container;
        this.controllerManager = controllerManager;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        // Inflate the keyboard layout into the container if it doesn't already have it
        View view = container.findViewById(R.id.layer_6_keyboard);
        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.layer_6_keyboard, container, true);
            this.keyboardLayout = container.findViewById(R.id.layer_6_keyboard);
        } else {
            this.keyboardLayout = (FrameLayout) view;
        }

        keyboardContent = keyboardLayout.findViewById(R.id.keyboard_content);
        opacitySeekbar = keyboardLayout.findViewById(R.id.float_keyboard_seekbar);
        keyPopup = keyboardLayout.findViewById(R.id.keyboard_key_popup);

        layoutMain = keyboardLayout.findViewById(R.id.layout_main);
        layoutNav = keyboardLayout.findViewById(R.id.layout_nav);
        layoutNum = keyboardLayout.findViewById(R.id.layout_num);
        layoutMini = keyboardLayout.findViewById(R.id.layout_mini);
        
        btnMain = keyboardLayout.findViewById(R.id.btn_key_page_main);
        btnNav = keyboardLayout.findViewById(R.id.btn_key_page_nav);
        btnNum = keyboardLayout.findViewById(R.id.btn_key_page_num);
        btnMini = keyboardLayout.findViewById(R.id.btn_key_page_mini);

        // 初始化子面板
        panelAlpha = keyboardLayout.findViewById(R.id.panel_alpha);
        panelNumMini = keyboardLayout.findViewById(R.id.panel_num_mini);
        panelPcMini = keyboardLayout.findViewById(R.id.panel_pc_mini);

        initModifiers();
        initSeekbars();
        initTabs();
        updateTabStyle(btnMain, true);
        updateTabStyle(btnNav, false);
        updateTabStyle(btnNum, false);
        updateTabStyle(btnMini, false);
        
        loadSettings();
        
        setupTouchListeners(keyboardLayout);
    }

    private void initModifiers() {
        modifierStates.put(KEY_LCTRL, MOD_NEUTRAL);
        modifierStates.put(KEY_RCTRL, MOD_NEUTRAL);
        modifierStates.put(KEY_LSHIFT, MOD_NEUTRAL);
        modifierStates.put(KEY_RSHIFT, MOD_NEUTRAL);
        modifierStates.put(KEY_LALT, MOD_NEUTRAL);
        modifierStates.put(KEY_RALT, MOD_NEUTRAL);
        modifierStates.put(KEY_LWIN, MOD_NEUTRAL);
    }

    private void initSeekbars() {
        opacitySeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float alpha = (float) (progress * 0.1);
                keyboardLayout.setAlpha(alpha);
                if (fromUser) {
                    prefs.edit().putInt(KEY_OPACITY, progress).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void syncResizeHandlePosition() {
        // Now inside keyboardContent LinearLayout, no manual positioning needed.
    }

    private void loadSettings() {
        int savedHeight = prefs.getInt(KEY_HEIGHT, -1);
        if (savedHeight > 0) {
            ViewGroup.LayoutParams params = keyboardContent.getLayoutParams();
            params.height = savedHeight;
            keyboardContent.setLayoutParams(params);
        }
        
        keyboardContent.setTranslationX(prefs.getFloat(KEY_X, 0f));
        keyboardContent.setTranslationY(prefs.getFloat(KEY_Y, 0f));

        boolean isMini = prefs.getBoolean(KEY_IS_MINI, false);
        setMiniMode(isMini);

        int savedOpacity = prefs.getInt(KEY_OPACITY, 10);
        opacitySeekbar.setProgress(savedOpacity);
        keyboardLayout.setAlpha((float) (savedOpacity * 0.1));
    }

    private void setMiniMode(boolean mini) {
        View leftSidebar = keyboardLayout.findViewById(R.id.keyboard_left_sidebar);
        View rightSidebar = keyboardLayout.findViewById(R.id.keyboard_right_sidebar);
        float density = keyboardLayout.getContext().getResources().getDisplayMetrics().density;

        if (mini) {
            leftSidebar.setVisibility(View.GONE);
            rightSidebar.setVisibility(View.GONE);
            
            layoutMain.setVisibility(View.GONE);
            layoutNav.setVisibility(View.GONE);
            layoutNum.setVisibility(View.GONE);
            layoutMini.setVisibility(View.VISIBLE);
            
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) keyboardContent.getLayoutParams();
            params.width = (int) (360 * density);
            int margin = (int) (20 * density);
            params.setMargins(margin, margin, margin, margin);
            keyboardContent.setLayoutParams(params);
        } else {
            leftSidebar.setVisibility(View.VISIBLE);
            rightSidebar.setVisibility(View.VISIBLE);
            
            layoutMini.setVisibility(View.GONE);
            layoutMain.setVisibility(View.VISIBLE);
            
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) keyboardContent.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.setMargins(0, 0, 0, 0);
            keyboardContent.setLayoutParams(params);

            // Reset position when returning to full keyboard
            keyboardContent.setTranslationX(0);
            keyboardContent.setTranslationY(0);

            keyboardLayout.findViewById(R.id.keyboard_resize_handle).setVisibility(View.GONE);

            updateTabStyle(btnMain, true);
            updateTabStyle(btnNav, false);
            updateTabStyle(btnNum, false);
            updateTabStyle(btnMini, false);
        }
        prefs.edit().putBoolean(KEY_IS_MINI, mini).apply();
    }

    private void initTabs() {
        View.OnClickListener tabListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = v.getId();
                if (id == R.id.btn_key_page_mini) {
                    setMiniMode(true);
                    return;
                }
                
                layoutMain.setVisibility(id == R.id.btn_key_page_main ? View.VISIBLE : View.GONE);
                layoutNav.setVisibility(id == R.id.btn_key_page_nav ? View.VISIBLE : View.GONE);
                layoutNum.setVisibility(id == R.id.btn_key_page_num ? View.VISIBLE : View.GONE);
                layoutMini.setVisibility(View.GONE);
                
                updateTabStyle(btnMain, id == R.id.btn_key_page_main);
                updateTabStyle(btnNav, id == R.id.btn_key_page_nav);
                updateTabStyle(btnNum, id == R.id.btn_key_page_num);
                updateTabStyle(btnMini, false);
            }
        };
        btnMain.setOnClickListener(tabListener);
        btnNav.setOnClickListener(tabListener);
        btnNum.setOnClickListener(tabListener);
        btnMini.setOnClickListener(tabListener);

        // Mini keyboard internal switches
        View panelAlpha = keyboardLayout.findViewById(R.id.panel_alpha);
        View panelNumMini = keyboardLayout.findViewById(R.id.panel_num_mini);
        View panelPcMini = keyboardLayout.findViewById(R.id.panel_pc_mini);

        keyboardLayout.findViewById(R.id.btn_switch_num).setOnClickListener(v -> {
            panelAlpha.setVisibility(View.GONE);
            panelNumMini.setVisibility(View.VISIBLE);
        });
        keyboardLayout.findViewById(R.id.btn_keyboard_collapse_alpah).setOnClickListener(v -> {
            hide();
        });
        keyboardLayout.findViewById(R.id.btn_switch_pc).setOnClickListener(v -> {
            panelAlpha.setVisibility(View.GONE);
            panelPcMini.setVisibility(View.VISIBLE);
        });
        keyboardLayout.findViewById(R.id.btn_switch_alpha_from_num_bottom).setOnClickListener(v -> {
            panelNumMini.setVisibility(View.GONE);
            panelAlpha.setVisibility(View.VISIBLE);
        });
        keyboardLayout.findViewById(R.id.btn_keyboard_collapse_num_bottom).setOnClickListener(v -> {
            hide();
        });
        keyboardLayout.findViewById(R.id.btn_switch_pc_from_num).setOnClickListener(v -> {
            panelNumMini.setVisibility(View.GONE);
            panelPcMini.setVisibility(View.VISIBLE);
        });
        keyboardLayout.findViewById(R.id.btn_switch_alpha_from_pc).setOnClickListener(v -> {
            panelPcMini.setVisibility(View.GONE);
            panelAlpha.setVisibility(View.VISIBLE);
        });

        View.OnClickListener backToFullListener = v -> setMiniMode(false);
        keyboardLayout.findViewById(R.id.btn_switch_full).setOnClickListener(backToFullListener);
        keyboardLayout.findViewById(R.id.btn_switch_full_from_num).setOnClickListener(backToFullListener);

        TextView btnCollapse = keyboardLayout.findViewById(R.id.btn_keyboard_collapse);
        if (btnCollapse != null) {
            btnCollapse.setOnClickListener(v -> hide());
        }

        TextView btnResize = keyboardLayout.findViewById(R.id.btn_keyboard_resize);
        View resizeHandle = keyboardLayout.findViewById(R.id.keyboard_resize_handle);
        View miniDragHandle = keyboardLayout.findViewById(R.id.mini_drag_handle);

        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private float initialTouchX, initialTouchY;
            private float initialX, initialY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        initialX = keyboardContent.getTranslationX();
                        initialY = keyboardContent.getTranslationY();
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        keyboardContent.setTranslationX(initialX + dx);
                        keyboardContent.setTranslationY(initialY + dy);

                        if (resizeHandle.getVisibility() == View.VISIBLE) {
                            syncResizeHandlePosition();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        prefs.edit()
                                .putFloat(KEY_X, keyboardContent.getTranslationX())
                                .putFloat(KEY_Y, keyboardContent.getTranslationY())
                                .apply();
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                }
                return false;
            }
        };

        if (miniDragHandle != null) miniDragHandle.setOnTouchListener(dragListener);

        View.OnClickListener resizeToggleListener = v -> {
            boolean isVisible = resizeHandle.getVisibility() == View.VISIBLE;
            resizeHandle.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            updateTabStyle(btnResize, !isVisible);
            if (!isVisible) syncResizeHandlePosition();
        };
        if (btnResize != null) btnResize.setOnClickListener(resizeToggleListener);

        if (resizeHandle != null) {
            resizeHandle.setOnTouchListener(new View.OnTouchListener() {
                private float initialTouchY;
                private int initialHeight;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialTouchY = event.getRawY();
                            initialHeight = keyboardContent.getHeight();
                            v.setPressed(true);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float currentTouchY = event.getRawY();
                            float deltaY = initialTouchY - currentTouchY;
                            int newHeight = (int) (initialHeight + deltaY);
                            int minHeight = v.getContext().getResources().getDisplayMetrics().heightPixels / 5;
                            int maxHeight = v.getContext().getResources().getDisplayMetrics().heightPixels * 4 / 5;
                            if (newHeight > minHeight && newHeight < maxHeight) {
                                ViewGroup.LayoutParams params = keyboardContent.getLayoutParams();
                                params.height = newHeight;
                                keyboardContent.setLayoutParams(params);
                                syncResizeHandlePosition();
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            prefs.edit().putInt(KEY_HEIGHT, keyboardContent.getHeight()).apply();
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            return true;
                    }
                    return false;
                }
            });
        }
    }

    private void updateTabStyle(TextView btn, boolean active) {
        if (btn == null) return;
        btn.setTextColor(active ? Color.WHITE : Color.parseColor("#88FFFFFF"));
        btn.setBackgroundColor(active ? Color.parseColor("#40FFFFFF") : Color.TRANSPARENT);
    }

    private void setupTouchListeners(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String) {
                String sTag = (String) tag;
                if (sTag.startsWith("k")) {
                    KeyboardGestureDetector detector = new KeyboardGestureDetector(child, this);
                    child.setOnTouchListener(detector::onTouchEvent);
                }
            } else if (child instanceof ViewGroup) {
                setupTouchListeners((ViewGroup) child);
            }
        }
    }

    @Override
    public void onKeyPress(int keyCode) {
        View v = null;
        String tag = "k" + keyCode;

        // 优先从当前可见的布局中查找
        if (layoutMini != null && layoutMini.getVisibility() == View.VISIBLE) {
            v = layoutMini.findViewWithTag(tag);
        } else if (layoutNum != null && layoutNum.getVisibility() == View.VISIBLE) {
            v = layoutNum.findViewWithTag(tag);
        } else if (layoutNav != null && layoutNav.getVisibility() == View.VISIBLE) {
            v = layoutNav.findViewWithTag(tag);
        } else {
            // 默认为 Main，或者从整个 content 找
            if (layoutMain != null) {
                v = layoutMain.findViewWithTag(tag);
            }
        }

        // 双重保险：如果特定布局没找到，再从全局找（防止某些特殊按键不在标准区域）
        if (v == null) {
            v = keyboardLayout.findViewWithTag(tag);
        }

        if (modifierStates.containsKey(keyCode)) {
            triggerHaptic("toggle");
            int currentState = modifierStates.get(keyCode);
            if (currentState == MOD_NEUTRAL) {
                modifierStates.put(keyCode, MOD_SINGLE);
                physicallyHeldModifiers.add(keyCode);
                controllerManager.getElementController().sendKeyEvent(true, (short) keyCode);
                updateModifierUI(keyCode, MOD_SINGLE);
            } else {
                handleModifierDown(v, keyCode);
            }
        } else {
            triggerHaptic("normal");
            controllerManager.getElementController().sendKeyEvent(true, (short) keyCode);
            // 只有当找到了 View 且 View 是可见的时候才显示气泡
            if (v != null && v.isShown()) {
                showPopup(v);
            }
        }
    }

    private void triggerHaptic(String type) {
        int duration = 10;
        if (type.equals("toggle") || type.equals("heavy")) duration = 20;
        controllerManager.getElementController().rumbleSingleVibrator((short) 1000, (short) 1000, duration);
    }

    private void showPopup(View v) {
        if (v instanceof TextView && keyPopup != null) {
            String text = ((TextView) v).getText().toString().trim();

            // 确保有内容才显示
            if (text.length() == 1 || text.contains(" ")) {
                String display = text.split(" ")[0];
                keyPopup.setText(display);

                // 1. 获取屏幕密度和 Popup 尺寸
                float density = v.getContext().getResources().getDisplayMetrics().density;
                float popupWidth = 50 * density; // 与 XML 对应
                float popupHeight = 64 * density; // 与 XML 对应

                // 2. 获取按键(v)在整个屏幕上的绝对坐标 [x, y]
                // 无论是否是小键盘模式、是否被拖动、是否有 Margin，这个坐标都是最终显示的真实位置
                int[] keyScreenLoc = new int[2];
                v.getLocationOnScreen(keyScreenLoc);

                // 3. 获取 Popup 父容器(keyboardLayout)在整个屏幕上的绝对坐标 [x, y]
                int[] parentScreenLoc = new int[2];
                keyboardLayout.getLocationOnScreen(parentScreenLoc);

                // 4. 计算相对坐标： (按键绝对位置 - 父容器绝对位置)
                // 这样得出的就是 Popup 相对于父容器应该摆放的 X, Y
                float relativeX = keyScreenLoc[0] - parentScreenLoc[0];
                float relativeY = keyScreenLoc[1] - parentScreenLoc[1];

                // 5. 应用位置并居中校正
                // X轴：相对位置 + (按键宽的一半) - (Popup宽的一半)
                keyPopup.setX(relativeX + (v.getWidth() / 2f) - (popupWidth / 2f));

                // Y轴：相对位置 - Popup高度
                keyPopup.setY(relativeY - popupHeight);

                keyPopup.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onKeyRelease(int keyCode) {
        if (!modifierStates.containsKey(keyCode)) {
            controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
            resetSingleModifiers();
            if (keyPopup != null) keyPopup.setVisibility(View.GONE);
        } else {
            physicallyHeldModifiers.remove(keyCode);
        }
    }

    @Override
    public void onModifierHoldRelease(int keyCode) {
        if (modifierStates.containsKey(keyCode)) {
            physicallyHeldModifiers.remove(keyCode);
            int currentState = modifierStates.getOrDefault(keyCode, MOD_NEUTRAL);
            if (currentState == MOD_SINGLE) {
                modifierStates.put(keyCode, MOD_NEUTRAL);
                controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
                updateModifierUI(keyCode, MOD_NEUTRAL);
            }
        } else {
            controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
            resetSingleModifiers();
            if (keyPopup != null) keyPopup.setVisibility(View.GONE);
        }
    }

    private void sendShortcut(short modCode, short keyCode) {
        controllerManager.getElementController().sendKeyEvent(true, modCode);
        controllerManager.getElementController().sendKeyEvent(true, keyCode);
        controllerManager.getElementController().sendKeyEvent(false, keyCode);
        controllerManager.getElementController().sendKeyEvent(false, modCode);
    }

    @Override
    public void onLongPress(int keyCode) {}

    @Override
    public void onDoubleTap(int keyCode) {
        if (modifierStates.containsKey(keyCode)) {
            triggerHaptic("heavy");
            modifierStates.put(keyCode, MOD_NEUTRAL);
            physicallyHeldModifiers.remove(keyCode);
            updateModifierUI(keyCode, MOD_NEUTRAL);
            controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
        } else {
            onKeyPress(keyCode);
        }
    }

    private void handleModifierDown(View v, int keyCode) {
        int currentState = modifierStates.get(keyCode);
        int newState = (currentState + 1) % 3;
        modifierStates.put(keyCode, newState);
        switch (newState) {
            case MOD_NEUTRAL:
                controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
                break;
            case MOD_SINGLE:
                controllerManager.getElementController().sendKeyEvent(true, (short) keyCode);
                break;
            case MOD_LOCKED:
                break;
        }
        updateModifierUI(keyCode, newState);
    }

    private void resetSingleModifiers() {
        for (Map.Entry<Integer, Integer> entry : modifierStates.entrySet()) {
            if (entry.getValue() == MOD_SINGLE) {
                int keyCode = entry.getKey();
                if (physicallyHeldModifiers.contains(keyCode)) continue;
                modifierStates.put(keyCode, MOD_NEUTRAL);
                controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
                updateModifierUI(keyCode, MOD_NEUTRAL);
            }
        }
    }

    private void updateModifierUI(int keyCode, int state) {
        // 1. 确定背景资源
        int backgroundResId;
        switch (state) {
            case MOD_SINGLE:
                backgroundResId = R.drawable.keyboard_modifier_single_selector;
                break;
            case MOD_LOCKED:
                backgroundResId = R.drawable.keyboard_modifier_locked_selector;
                break;
            case MOD_NEUTRAL:
            default:
                backgroundResId = R.drawable.keyboard_modifier_refined_selector;
                break;
        }

        String tag = "k" + keyCode;

        // 2. 更新全键盘 (layoutMain)
        updateKeyInContainer(layoutMain, tag, backgroundResId);

        // 3. 更新小键盘的所有子面板 (关键修改！)
        // 不要直接查 layoutMini，而是分别查它的子面板
        updateKeyInContainer(panelAlpha, tag, backgroundResId);     // 更新字母板的 Shift/Del/Enter
        updateKeyInContainer(panelNumMini, tag, backgroundResId);   // 更新数字板的 Shift/Del/Enter
        updateKeyInContainer(panelPcMini, tag, backgroundResId);    // 更新PC板的修饰键

        // 4. 更新其他键盘模式
        updateKeyInContainer(layoutNav, tag, backgroundResId);
        updateKeyInContainer(layoutNum, tag, backgroundResId);
    }

    /**
     * 辅助方法：在指定的容器内查找 Tag 并更新背景
     */
    private void updateKeyInContainer(View container, String tag, int resId) {
        if (container != null) {
            View v = container.findViewWithTag(tag);
            if (v != null) {
                v.setBackgroundResource(resId);
                // 如果需要立即重绘，可以强制刷新，但在setBackgroundResource中通常不需要
                // v.invalidate();
            }
        }
    }

    public void toggle() {
        if (isVisible()) hide();
        else show();
    }
    
    public void show() {
        keyboardLayout.setVisibility(View.VISIBLE);
        parentContainer.setVisibility(View.VISIBLE);
    }

    public void hide() {
        keyboardLayout.setVisibility(View.GONE);
        parentContainer.setVisibility(View.GONE);
    }

    public boolean isVisible() {
        return keyboardLayout.getVisibility() == View.VISIBLE;
    }
}
