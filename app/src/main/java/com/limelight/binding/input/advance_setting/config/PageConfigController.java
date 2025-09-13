package com.limelight.binding.input.advance_setting.config;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.Game; // 确保导入 Game 类
import com.limelight.R;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.advance_setting.element.ElementController;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.ArrayList;
import java.util.List;

public class PageConfigController {


    private static final String CURRENT_CONFIG_KEY = "current_config_id";
    public static final String COLUMN_STRING_CONFIG_NAME = "config_name";
    public static final String COLUMN_BOOLEAN_TOUCH_ENABLE = "touch_enable";
    public static final String COLUMN_BOOLEAN_TOUCH_MODE = "touch_mode";
    private static final String COLUMN_INT_TOUCH_SENSE = "touch_sense";
    public static final String COLUMN_BOOLEAN_GAME_VIBRATOR = "game_vibrator";
    public static final String COLUMN_BOOLEAN_BUTTON_VIBRATOR = "button_vibrator";
    public static final String COLUMN_LONG_CONFIG_ID = "config_id";
    private static final String COLUMN_INT_MOUSE_WHEEL_SPEED = "mouse_wheel_speed";
    public static final String COLUMN_BOOLEAN_ENHANCED_TOUCH = "enhanced_touch";


    private SuperPageLayout pageConfig;
    private Context context;
    private ControllerManager controllerManager;
    private Long currentConfigId = 0L;
    private Spinner configSelectSpinner;
    private LinearLayout enhancedTouchLayout;

    private List<Long> configIds = new ArrayList<>();
    private List<String> configNames = new ArrayList<>();



    public PageConfigController(ControllerManager controllerManager, Context context){
        this.context = context;
        this.pageConfig = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_config,null);
        this.enhancedTouchLayout = pageConfig.findViewById(R.id.enhanced_touch_layout);
        this.controllerManager = controllerManager;
        configSelectSpinner = pageConfig.findViewById(R.id.config_select_spinner);

        //新增布局按钮
        pageConfig.findViewById(R.id.add_config_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SuperPageLayout pageWindow = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_window,null);
                TextView title = pageWindow.findViewById(R.id.window_title);
                title.setText("配置名称");
                EditText editText = pageWindow.findViewById(R.id.window_edittext);
                //窗口确认按钮
                pageWindow.findViewById(R.id.window_confirm).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String configName = editText.getText().toString();
                        if (!configName.matches("^.{1,10}$")){
                            Toast.makeText(context,"名称只能由1-20个字符组成",Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(COLUMN_LONG_CONFIG_ID,System.currentTimeMillis());
                        contentValues.put(COLUMN_STRING_CONFIG_NAME,configName);
                        contentValues.put(COLUMN_BOOLEAN_TOUCH_ENABLE,String.valueOf(true));
                        contentValues.put(COLUMN_BOOLEAN_TOUCH_MODE,String.valueOf(true));
                        contentValues.put(COLUMN_INT_TOUCH_SENSE,100);
                        //保存到数据库中
                        controllerManager.getSuperConfigDatabaseHelper().insertConfig(contentValues);
                        returnPrePage(pageWindow.getLastPage());
                        loadAllConfigToSpinner();
                        loadCurrentConfig();
                    }
                });
                //窗口取消按钮
                pageWindow.findViewById(R.id.window_cancel).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        returnPrePage(pageWindow.getLastPage());
                    }
                });
                controllerManager.getSuperPagesController().openNewPage(pageWindow);
            }
        });
        //重命名布局按钮
        pageConfig.findViewById(R.id.rename_config_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SuperPageLayout pageWindow = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_window,null);
                TextView title = pageWindow.findViewById(R.id.window_title);
                title.setText("配置名称");
                EditText editText = pageWindow.findViewById(R.id.window_edittext);
                editText.setText((String)configSelectSpinner.getSelectedItem());
                //窗口确认按钮
                pageWindow.findViewById(R.id.window_confirm).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (currentConfigId.equals(0L)){
                            returnPrePage(pageWindow.getLastPage());
                            return;
                        }
                        String configNewName = editText.getText().toString();
                        if (!configNewName.matches("^.{1,10}$")){
                            Toast.makeText(context,"名称只能由1-20个字符组成",Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(COLUMN_STRING_CONFIG_NAME,configNewName);
                        //保存到数据库中
                        controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId,contentValues);
                        returnPrePage(pageWindow.getLastPage());
                        loadAllConfigToSpinner();
                        loadCurrentConfig();
                    }
                });
                //窗口取消按钮
                pageWindow.findViewById(R.id.window_cancel).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        returnPrePage(pageWindow.getLastPage());
                    }
                });
                controllerManager.getSuperPagesController().openNewPage(pageWindow);
            }
        });
        //删除布局按钮
        pageConfig.findViewById(R.id.delete_config_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SuperPageLayout pageWindow = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_window,null);
                TextView title = pageWindow.findViewById(R.id.window_title);
                String titleString = "是否删除:" + configNames.get(configIds.indexOf(currentConfigId));
                title.setText(titleString);
                pageWindow.findViewById(R.id.window_edittext).setVisibility(View.GONE);
                //窗口确认按钮
                pageWindow.findViewById(R.id.window_confirm).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (currentConfigId.equals(0L)){
                            returnPrePage(pageWindow.getLastPage());
                            return;
                        }
                        controllerManager.getSuperConfigDatabaseHelper().deleteConfig(currentConfigId);
                        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
                        editor.putLong(CURRENT_CONFIG_KEY,0L);
                        editor.apply();
                        loadAllConfigToSpinner();
                        loadCurrentConfig();
                        returnPrePage(pageWindow.getLastPage());
                    }
                });
                //窗口取消按钮
                pageWindow.findViewById(R.id.window_cancel).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        returnPrePage(pageWindow.getLastPage());
                    }
                });
                controllerManager.getSuperPagesController().openNewPage(pageWindow);
            }
        });

        // 退出王冠配置按钮
        pageConfig.findViewById(R.id.exit_crown_config_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 将 Game Activity 中的 currentBackKeyMenu 标志位设为 GAME_MENU，以切换回GAME_MENU模式
                ((Game)context).setcurrentBackKeyMenu(Game.BackKeyMenuMode.GAME_MENU);

                // 关闭当前的高级设置页面，相当于按返回键
                controllerManager.getSuperPagesController().returnOperation();

                // 显示提示信息
                Toast.makeText(context, context.getString(R.string.toast_back_key_menu_switch_1), Toast.LENGTH_SHORT).show();
            }
        });

    }

    public void initConfig(){
        loadAllConfigToSpinner();
        loadCurrentConfig();
    }

    private void loadAllConfigToSpinner(){
        configIds = controllerManager.getSuperConfigDatabaseHelper().queryAllConfigIds();
        //判断是否有default布局
        if (!configIds.contains(0L)){
            ContentValues contentValues = new ContentValues();
            contentValues.put(COLUMN_LONG_CONFIG_ID,0L);
            contentValues.put(COLUMN_STRING_CONFIG_NAME,"default");
            //保存到数据库中
            controllerManager.getSuperConfigDatabaseHelper().insertConfig(contentValues);
            configIds = controllerManager.getSuperConfigDatabaseHelper().queryAllConfigIds();
        }
        configNames.clear();
        for (Long configId : configIds){
            String name = (String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(configId,COLUMN_STRING_CONFIG_NAME,"default");
            configNames.add(name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context,
                R.layout.app_spinner_item,
                configNames
        );
        configSelectSpinner.setAdapter(adapter);

    }

    private void loadCurrentConfig(){
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        currentConfigId = sharedPreferences.getLong(CURRENT_CONFIG_KEY,0L);
        //spinner选中
        for (int i = 0;i < configIds.size();i ++){
            if (currentConfigId.equals(configIds.get(i))){
                configSelectSpinner.setOnItemSelectedListener(null);
                configSelectSpinner.setSelection(i);
                configSelectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        Long configId = configIds.get(position);
                        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
                        editor.putLong(CURRENT_CONFIG_KEY,configId);
                        editor.apply();
                        if (!configId.equals(currentConfigId)){
                            loadCurrentConfig();
                        }

                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {

                    }
                });
                break;
            }
        }

        loadMouseEnable();
        loadMouseMode();
        loadMouseSense();
        loadMouseWheelSpeed();
        loadGameVibrator();
        loadButtonVibrator();
        loadEnhancedTouch();
        controllerManager.getElementController().loadAllElement(currentConfigId);
        if (currentConfigId == 0L){
            pageConfig.findViewById(R.id.rename_config_button).setVisibility(View.GONE);
            pageConfig.findViewById(R.id.delete_config_button).setVisibility(View.GONE);
        } else {
            pageConfig.findViewById(R.id.rename_config_button).setVisibility(View.VISIBLE);
            pageConfig.findViewById(R.id.delete_config_button).setVisibility(View.VISIBLE);
        }
    }

    private void loadMouseEnable(){
        //mouse enable
        Switch mouseEnableSwitch = pageConfig.findViewById(R.id.mouse_enable_switch);
        boolean mouseEnable = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_TOUCH_ENABLE, String.valueOf(true)));
        //设置switch
        mouseEnableSwitch.setOnCheckedChangeListener(null);
        mouseEnableSwitch.setChecked(mouseEnable);
        //做实际的设置
        controllerManager.getTouchController().enableTouch(mouseEnable);
        //设置listener
        mouseEnableSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_BOOLEAN_TOUCH_ENABLE,String.valueOf(isChecked));
                //保存到数据库中
                controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId,contentValues);
                //做实际的设置
                controllerManager.getTouchController().enableTouch(isChecked);
            }
        });
    }

    private void loadMouseMode(){
        //mouse mode
        Switch mouseModeSwitch = pageConfig.findViewById(R.id.trackpad_enable_switch);
        boolean mouseMode = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_TOUCH_MODE, String.valueOf(true)));
        if (mouseMode) {
            enhancedTouchLayout.setVisibility(View.GONE); // 如果是触控板模式，隐藏
        } else {
            enhancedTouchLayout.setVisibility(View.VISIBLE); // 否则，显示
        }
        mouseModeSwitch.setOnCheckedChangeListener(null);
        mouseModeSwitch.setChecked(mouseMode);
        controllerManager.getTouchController().setTouchMode(mouseMode);
        mouseModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    // 如果开启了触控板模式，隐藏“多点触控模式”布局
                    enhancedTouchLayout.setVisibility(View.GONE);
                } else {
                    // 如果关闭了触控板模式，显示“多点触控模式”布局
                    enhancedTouchLayout.setVisibility(View.VISIBLE);
                }
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_BOOLEAN_TOUCH_MODE,String.valueOf(isChecked));
                //保存到数据库中
                controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId,contentValues);
                //做实际的设置
                controllerManager.getTouchController().setTouchMode(isChecked);
            }
        });
    }

    private void loadMouseSense(){
        NumberSeekbar mouseSenseSeekBar = pageConfig.findViewById(R.id.mouse_sense_number_seekbar);
        int mouseSense = ((Long) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_INT_TOUCH_SENSE, 100L)).intValue();
        mouseSenseSeekBar.setValueWithNoCallBack(mouseSense);
        controllerManager.getTouchController().adjustTouchSense(mouseSense);
        mouseSenseSeekBar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_TOUCH_SENSE,seekBar.getProgress());
                //保存到数据库中
                controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId,contentValues);
                //做实际的设置
                controllerManager.getTouchController().adjustTouchSense(seekBar.getProgress());
            }
        });
    }

    private void loadMouseWheelSpeed() {
        NumberSeekbar mouseWheelSpeedSeekBar = pageConfig.findViewById(R.id.mouse_wheel_speed_number_seekbar);
        int mouseWheelSpeed = ((Long) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_INT_MOUSE_WHEEL_SPEED, 20L)).intValue();
        mouseWheelSpeedSeekBar.setValueWithNoCallBack(mouseWheelSpeed);
        // 数值越小，滚动越快，所以用 120 - mouseWheelSpeed 来转换
        ElementController.setMouseScrollRepeatInterval(120 - mouseWheelSpeed);
        mouseWheelSpeedSeekBar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_MOUSE_WHEEL_SPEED, seekBar.getProgress());
                //保存到数据库中
                controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId, contentValues);
                //做实际的设置
                // 数值越小，滚动越快，所以用 120 - seekBar.getProgress() 来转换
                ElementController.setMouseScrollRepeatInterval(120 - seekBar.getProgress());
            }
        });
    }


    private void loadGameVibrator(){
        //mouse mode
        Switch gameVibratorSwitch = pageConfig.findViewById(R.id.game_vibrator_enable_switch);
        boolean gameVibrator = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_GAME_VIBRATOR, String.valueOf(false)));
        gameVibratorSwitch.setOnCheckedChangeListener(null);
        gameVibratorSwitch.setChecked(gameVibrator);
        controllerManager.getElementController().setGameVibrator(gameVibrator);
        gameVibratorSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_BOOLEAN_GAME_VIBRATOR,String.valueOf(isChecked));
                //保存到数据库中
                controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId,contentValues);
                //做实际的设置
                controllerManager.getElementController().setGameVibrator(isChecked);
            }
        });
    }

    private void loadButtonVibrator(){
        //mouse mode
        Switch buttonVibratorSwitch = pageConfig.findViewById(R.id.button_vibrator_enable_switch);
        boolean buttonVibrator = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_BUTTON_VIBRATOR, String.valueOf(false)));
        buttonVibratorSwitch.setOnCheckedChangeListener(null);
        buttonVibratorSwitch.setChecked(buttonVibrator);
        controllerManager.getElementController().setButtonVibrator(buttonVibrator);
        buttonVibratorSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_BOOLEAN_BUTTON_VIBRATOR,String.valueOf(isChecked));
                //保存到数据库中
                controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId,contentValues);
                //做实际的设置
                controllerManager.getElementController().setButtonVibrator(isChecked);
            }
        });
    }

    private void loadEnhancedTouch() {
        Switch enhancedTouchSwitch = pageConfig.findViewById(R.id.enhanced_touch_switch);

        // 从数据库读取值，提供一个默认值 'false'
        String dbValue = (String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(
                currentConfigId,
                COLUMN_BOOLEAN_ENHANCED_TOUCH,
                String.valueOf(false)
        );
        boolean isEnabled = Boolean.parseBoolean(dbValue);
        // 更新 UI，但不触发监听器
        enhancedTouchSwitch.setOnCheckedChangeListener(null);
        enhancedTouchSwitch.setChecked(isEnabled);
        controllerManager.getTouchController().setEnhancedTouch(isEnabled);
        // 重新设置监听器，用于用户手动操作
        enhancedTouchSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ContentValues contentValues = new ContentValues();
            // 使用我们新定义的常量来保存新的状态
            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, String.valueOf(isChecked));
            // 更新到数据库
            controllerManager.getSuperConfigDatabaseHelper().updateConfig(currentConfigId, contentValues);
            controllerManager.getTouchController().setEnhancedTouch(isChecked);
        });
    }

    public Long getCurrentConfigId(){
        return currentConfigId;
    }

    public void open(){
        controllerManager.getSuperPagesController().openNewPage(pageConfig);
        pageConfig.setPageReturnListener(new SuperPageLayout.ReturnListener() {
            @Override
            public void returnCallBack() {
                controllerManager.getSuperPagesController().openNewPage(controllerManager.getSuperPagesController().getPageNull());
            }
        });

    }

    public void returnPrePage(SuperPageLayout prePage){
        controllerManager.getSuperPagesController().openNewPage(prePage);
    }

}
