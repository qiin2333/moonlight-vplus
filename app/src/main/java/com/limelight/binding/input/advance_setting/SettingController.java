package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;

import java.util.Map;

public class SettingController {

    private static final String KEYBOARD_SETTING = "keyboard_setting";
    private static final String MOUSE_SENSE = "mouse_sense";
    private static final String ELEMENT_OPACITY = "element_opacity";
    private static final String MOUSE_ENABLE = "mouse_enable";
    private static final String MOUSE_MODE = "mouse_mode";

    private SettingPreference settingPreference;
    private ControllerManager controllerManager;
    private FrameLayout settingLayout;
    private FrameLayout floatLayout;

    private Switch floatKeyboardSwitch;
    private TextView msenseTextView;
    private SeekBar elementOpacitySeekbar;
    private Switch mouseEnableSwitch;
    private Switch mouseModeSwitch;


    private Context context;

    public SettingController(ControllerManager controllerManager, FrameLayout settingLayout, FrameLayout floatLayout, Context context){
        this.controllerManager = controllerManager;
        this.settingLayout = settingLayout;
        this.floatLayout = floatLayout;
        this.context = context;
        floatKeyboardSwitch = settingLayout.findViewById(R.id.float_keyboard_enable_switch);
        msenseTextView = settingLayout.findViewById(R.id.msense_textview);
        elementOpacitySeekbar = settingLayout.findViewById(R.id.element_opacity_seekbar);
        mouseEnableSwitch = settingLayout.findViewById(R.id.mouse_enable_switch);
        mouseModeSwitch = settingLayout.findViewById(R.id.trackpad_enable_switch);

        initFloatKeyboard();
        initMouseSense();
        initElementOpacity();
        initMouseEnable();
        initMouseMode();
    }

    private void initFloatKeyboard(){

        Button floatKeyboardDisplayButton = floatLayout.findViewById(R.id.float_keyboard_display_button);
        SeekBar opacitySeekbar = floatLayout.findViewById(R.id.float_keyboard_seekbar);
        LinearLayout keyboardLayout = floatLayout.findViewById(R.id.float_keyboard);
        LinearLayout keyboard = floatLayout.findViewById(R.id.keyboard_drawing);

        floatKeyboardDisplayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (((Button)v).getText().equals("↑")){
                    opacitySeekbar.setVisibility(View.VISIBLE);
                    keyboardLayout.setVisibility(View.VISIBLE);
                    ((Button)v).setText("↓");
                } else {
                    opacitySeekbar.setVisibility(View.GONE);
                    keyboardLayout.setVisibility(View.GONE);
                    ((Button)v).setText("↑");
                }
            }
        });




        opacitySeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float alpha = (float) (progress * 0.1);
                keyboardLayout.setAlpha(alpha);
                floatKeyboardDisplayButton.setAlpha(alpha);
                opacitySeekbar.setAlpha(alpha);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                String keyString = (String) v.getTag();
                int keyCode = Integer.parseInt(keyString.substring(1));
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // 处理按下事件
                        controllerManager.getElementController().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,keyCode));
                        v.setBackgroundResource(R.drawable.confirm_square_border);
                        return true;
                    case MotionEvent.ACTION_UP:
                        // 处理释放事件
                        controllerManager.getElementController().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,keyCode));
                        v.setBackgroundResource(R.drawable.square_border);
                        return true;
                }
                return false;
            }
        };
        for (int i = 0; i < keyboard.getChildCount(); i++){
            LinearLayout keyboardRow = (LinearLayout) keyboard.getChildAt(i);
            for (int j = 0; j < keyboardRow.getChildCount(); j++){
                keyboardRow.getChildAt(j).setOnTouchListener(touchListener);
            }
        }

        floatKeyboardSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                doSetting(KEYBOARD_SETTING,String.valueOf(isChecked));
                settingPreference.saveSetting(KEYBOARD_SETTING, String.valueOf(isChecked));
            }
        });


    }

    private void initMouseSense(){
        int min = 1;
        int max = 500;
        WindowsController.EditTextWindowListener inputMsenseListener = new WindowsController.EditTextWindowListener() {
            @Override
            public boolean onConfirmClick(String text) {
                if (text.equals("")){
                    Toast.makeText(context,"请输入" + min + "~" + max + "的数字",Toast.LENGTH_SHORT).show();
                    return false;
                }
                int value = Integer.parseInt(text);
                if (value > max || value < min){
                    Toast.makeText(context,"请输入" + min + "~" + max + "的数字",Toast.LENGTH_SHORT).show();
                    return false;
                }
                String sense = String.valueOf(value);
                msenseTextView.setText(sense);
                doSetting(MOUSE_SENSE, sense);
                settingPreference.saveSetting(MOUSE_SENSE, sense);

                return true;
            }

            @Override
            public boolean onCancelClick(String text) {
                return true;
            }

        };
        msenseTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controllerManager.getWindowsController().openEditTextWindow(inputMsenseListener,msenseTextView.getText().toString(),null,null, InputType.TYPE_CLASS_NUMBER);
            }
        });
    }
    private void initElementOpacity(){
        elementOpacitySeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                doSetting(ELEMENT_OPACITY,String.valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                settingPreference.saveSetting(ELEMENT_OPACITY,String.valueOf(seekBar.getProgress()));
            }
        });
    }

    private void initMouseEnable(){
        mouseEnableSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                doSetting(MOUSE_ENABLE,String.valueOf(isChecked));
                settingPreference.saveSetting(MOUSE_ENABLE, String.valueOf(isChecked));
            }
        });
    }

    private void initMouseMode(){
        mouseModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                doSetting(MOUSE_MODE,String.valueOf(isChecked));
                settingPreference.saveSetting(MOUSE_MODE, String.valueOf(isChecked));
            }
        });
    }


    public void loadSettingConfig(String configId){
        settingPreference = new SettingPreference(configId,context);
        Map<String, String> map = settingPreference.getSettings();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            disPlaySetting(key,value);
            doSetting(key,value);
        }

    }
    private void disPlaySetting(String settingName, String settingValue){
        switch (settingName){
            case KEYBOARD_SETTING:
                floatKeyboardSwitch.setChecked(Boolean.valueOf(settingValue));
                break;
            case MOUSE_SENSE:
                msenseTextView.setText(settingValue);
                break;
            case ELEMENT_OPACITY:
                elementOpacitySeekbar.setProgress(Integer.parseInt(settingValue));
                break;
            case MOUSE_ENABLE:
                mouseEnableSwitch.setChecked(Boolean.valueOf(settingValue));
                break;
            case MOUSE_MODE:
                mouseModeSwitch.setChecked(Boolean.valueOf(settingValue));
                break;
        }
    }
    private void doSetting(String settingName, String settingValue){
        switch (settingName){
            case KEYBOARD_SETTING:{
                LinearLayout floatKeyboardBox = floatLayout.findViewById(R.id.float_keyboard_box);
                boolean isDisplay = Boolean.valueOf(settingValue);
                if (isDisplay){
                    floatKeyboardBox.setVisibility(View.VISIBLE);
                } else {
                    floatKeyboardBox.setVisibility(View.GONE);
                }
                break;
            }
            case MOUSE_SENSE:{
                controllerManager.getTouchController().adjustTouchSense(Integer.parseInt(settingValue));
                break;
            }
            case ELEMENT_OPACITY:
                controllerManager.getElementController().setOpacity(Integer.parseInt(settingValue) * 10);
                break;
            case MOUSE_ENABLE:
                controllerManager.getTouchController().enableTouch(Boolean.valueOf(settingValue));
                break;
            case MOUSE_MODE:
                boolean touchMode = Boolean.valueOf(settingValue);
                controllerManager.getTouchController().setTouchMode(touchMode);
                if (touchMode){
                    doSetting(MOUSE_SENSE,msenseTextView.getText().toString());
                }
                break;
        }
    }


    public void open(){
        settingLayout.setVisibility(View.VISIBLE);
    }

    public void close(){
        settingLayout.setVisibility(View.INVISIBLE);
    }

    public void hideFloat(){
        floatLayout.setVisibility(View.GONE);
    }

    public void displayFloat(){
        floatLayout.setVisibility(View.VISIBLE);
    }
}
