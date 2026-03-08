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
    
    private View layoutMain, layoutNav, layoutNum;
    private TextView btnMain, btnNav, btnNum;
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
        
        loadSettings();

        layoutMain = keyboardLayout.findViewById(R.id.layout_main);
        layoutNav = keyboardLayout.findViewById(R.id.layout_nav);
        layoutNum = keyboardLayout.findViewById(R.id.layout_num);
        
        btnMain = keyboardLayout.findViewById(R.id.btn_key_page_main);
        btnNav = keyboardLayout.findViewById(R.id.btn_key_page_nav);
        btnNum = keyboardLayout.findViewById(R.id.btn_key_page_num);

        initModifiers();
        initSeekbars();
        initTabs();
        updateTabStyle(btnMain, true);
        updateTabStyle(btnNav, false);
        updateTabStyle(btnNum, false);
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

    private void loadSettings() {
        int savedHeight = prefs.getInt(KEY_HEIGHT, -1);
        if (savedHeight > 0) {
            ViewGroup.LayoutParams params = keyboardContent.getLayoutParams();
            params.height = savedHeight;
            keyboardContent.setLayoutParams(params);
        }

        int savedOpacity = prefs.getInt(KEY_OPACITY, 10);
        opacitySeekbar.setProgress(savedOpacity);
        keyboardLayout.setAlpha((float) (savedOpacity * 0.1));
    }

    private void initTabs() {
        View.OnClickListener tabListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = v.getId();
                layoutMain.setVisibility(id == R.id.btn_key_page_main ? View.VISIBLE : View.GONE);
                layoutNav.setVisibility(id == R.id.btn_key_page_nav ? View.VISIBLE : View.GONE);
                layoutNum.setVisibility(id == R.id.btn_key_page_num ? View.VISIBLE : View.GONE);
                
                updateTabStyle(btnMain, id == R.id.btn_key_page_main);
                updateTabStyle(btnNav, id == R.id.btn_key_page_nav);
                updateTabStyle(btnNum, id == R.id.btn_key_page_num);
            }
        };
        btnMain.setOnClickListener(tabListener);
        btnNav.setOnClickListener(tabListener);
        btnNum.setOnClickListener(tabListener);

        TextView btnCollapse = keyboardLayout.findViewById(R.id.btn_keyboard_collapse);
        if (btnCollapse != null) {
            btnCollapse.setOnClickListener(v -> hide());
        }

        TextView btnResize = keyboardLayout.findViewById(R.id.btn_keyboard_resize);
        View resizeHandle = keyboardLayout.findViewById(R.id.keyboard_resize_handle);
        if (btnResize != null && resizeHandle != null) {
            btnResize.setOnClickListener(v -> {
                boolean isVisible = resizeHandle.getVisibility() == View.VISIBLE;
                resizeHandle.setVisibility(isVisible ? View.GONE : View.VISIBLE);
                updateTabStyle(btnResize, !isVisible);

                if (!isVisible) {
                    // Initialize handle position to current keyboard top
                    FrameLayout.LayoutParams handleParams = (FrameLayout.LayoutParams) resizeHandle.getLayoutParams();
                    handleParams.bottomMargin = keyboardContent.getHeight() - (resizeHandle.getLayoutParams().height / 2);
                    resizeHandle.setLayoutParams(handleParams);
                }
            });

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
                            // Set some reasonable limits (e.g., 1/5 to 4/5 of screen height)
                            int minHeight = v.getContext().getResources().getDisplayMetrics().heightPixels / 5;
                            int maxHeight = v.getContext().getResources().getDisplayMetrics().heightPixels * 4 / 5;
                            if (newHeight > minHeight && newHeight < maxHeight) {
                                // Update keyboard height
                                ViewGroup.LayoutParams params = keyboardContent.getLayoutParams();
                                params.height = newHeight;
                                keyboardContent.setLayoutParams(params);

                                // Sync handle position
                                FrameLayout.LayoutParams handleParams = (FrameLayout.LayoutParams) resizeHandle.getLayoutParams();
                                handleParams.bottomMargin = newHeight - (resizeHandle.getLayoutParams().height / 2);
                                resizeHandle.setLayoutParams(handleParams);
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
        View v = keyboardLayout.findViewWithTag("k" + keyCode);
        
        if (modifierStates.containsKey(keyCode)) {
            triggerHaptic("toggle");
            // 无论短按还是长按，DOWN 时都立即发送 keyDown
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
            showPopup(v);
        }
    }

    private void triggerHaptic(String type) {
        int duration = 10; // Default subtle vibration
        if (type.equals("toggle") || type.equals("heavy")) {
            duration = 20;
        }
        controllerManager.getElementController().rumbleSingleVibrator((short) 1000, (short) 1000, duration);
    }

    private void showPopup(View v) {
        if (v instanceof TextView && keyPopup != null) {
            String text = ((TextView) v).getText().toString().trim();
            if (text.length() == 1 || text.contains(" ")) { // Pop for chars
                String display = text.split(" ")[0];
                keyPopup.setText(display);
                
                int[] location = new int[2];
                v.getLocationInWindow(location);
                int[] parentLoc = new int[2];
                keyboardLayout.getLocationInWindow(parentLoc);
                
                keyPopup.setX(location[0] - parentLoc[0] + (v.getWidth() - keyPopup.getWidth()) / 2);
                keyPopup.setY(location[1] - parentLoc[1] - keyPopup.getHeight());
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
            // 短点击松手：从物理按住集合中移除，让 resetSingleModifiers 能正常释放它
            physicallyHeldModifiers.remove(keyCode);
        }
    }

    @Override
    public void onModifierHoldRelease(int keyCode) {
        if (modifierStates.containsKey(keyCode)) {
            physicallyHeldModifiers.remove(keyCode);
            // 长按住后松手：直接释放修饰键，不进入粘滞流程
            int currentState = modifierStates.getOrDefault(keyCode, MOD_NEUTRAL);
            if (currentState == MOD_SINGLE) {
                modifierStates.put(keyCode, MOD_NEUTRAL);
                controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
                updateModifierUI(keyCode, MOD_NEUTRAL);
            }
            // MOD_LOCKED 状态不受长按释放影响（已锁定）
        } else {
            // 普通键长按后松手，跟短按释放一样
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
    public void onLongPress(int keyCode) {
        // Placeholder for future long-press features
    }

    @Override
    public void onDoubleTap(int keyCode) {
        if (modifierStates.containsKey(keyCode)) {
            triggerHaptic("heavy");
            // If it's a modifier, the first tap already sent a KEY DOWN and entered MOD_SINGLE.
            // On double tap, we send a KEY UP and reset the state to NEUTRAL, effectively completing a single press.
            modifierStates.put(keyCode, MOD_NEUTRAL);
            physicallyHeldModifiers.remove(keyCode);
            updateModifierUI(keyCode, MOD_NEUTRAL);
            controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
        } else {
            // For normal keys, just trigger another press
            onKeyPress(keyCode);
        }
    }

    private void handleModifierDown(View v, int keyCode) {
        int currentState = modifierStates.get(keyCode);
        int newState = (currentState + 1) % 3;
        modifierStates.put(keyCode, newState);
        
        switch (newState) {
            case MOD_NEUTRAL:
                // Normal state: handled by selector
                controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
                break;
            case MOD_SINGLE:
                // Active (Single use): Blue highlight
                controllerManager.getElementController().sendKeyEvent(true, (short) keyCode);
                break;
            case MOD_LOCKED:
                // Locked: Red highlight
                break;
        }
        updateModifierUI(keyCode, newState);
    }

    private void resetSingleModifiers() {
        for (Map.Entry<Integer, Integer> entry : modifierStates.entrySet()) {
            if (entry.getValue() == MOD_SINGLE) {
                int keyCode = entry.getKey();
                // 如果修饰键仍被手指物理按住，跳过释放
                if (physicallyHeldModifiers.contains(keyCode)) {
                    continue;
                }
                modifierStates.put(keyCode, MOD_NEUTRAL);
                controllerManager.getElementController().sendKeyEvent(false, (short) keyCode);
                updateModifierUI(keyCode, MOD_NEUTRAL);
            }
        }
    }

    private void updateModifierUI(int keyCode, int state) {
        View v = keyboardLayout.findViewWithTag("k" + keyCode);
        if (v == null) return;
        
        switch (state) {
            case MOD_NEUTRAL:
                v.setBackgroundResource(R.drawable.keyboard_modifier_refined_selector);
                break;
            case MOD_SINGLE:
                v.setBackgroundResource(R.drawable.keyboard_modifier_single_selector);
                break;
            case MOD_LOCKED:
                v.setBackgroundResource(R.drawable.keyboard_modifier_locked_selector);
                break;
        }
    }

    public void toggle() {
        if (isVisible()) {
            hide();
        } else {
            show();
        }
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
