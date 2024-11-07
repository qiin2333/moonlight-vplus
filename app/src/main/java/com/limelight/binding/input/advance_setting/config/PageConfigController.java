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
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.ArrayList;
import java.util.List;

public class PageConfigController {


    private static final String CURRENT_CONFIG_KEY = "current_config_id";
    public static final String COLUMN_STRING_CONFIG_NAME = "config_name";
    public static final String COLUMN_BOOLEAN_TOUCH_ENABLE = "touch_enable";
    public static final String COLUMN_BOOLEAN_TOUCH_MODE = "touch_mode";
    private static final String COLUMN_INT_TOUCH_SENSE = "touch_sense";
    public static final String COLUMN_LONG_CONFIG_ID = "config_id";

    private SuperPageLayout pageConfig;
    private Context context;
    private ControllerManager controllerManager;
    private Long currentConfigId = 0L;
    private Spinner configSelectSpinner;

    private List<Long> configIds = new ArrayList<>();
    private List<String> configNames = new ArrayList<>();
    private SuperPageLayout openPage;



    public PageConfigController(ControllerManager controllerManager, Context context){
        this.context = context;
        this.pageConfig = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_config,null);
        this.controllerManager = controllerManager;
        configSelectSpinner = pageConfig.findViewById(R.id.config_select_spinner);
        openPage = pageConfig;

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
                        controllerManager.getSuperPagesController().close();
                        loadAllConfigToSpinner();
                        loadCurrentConfig();
                    }
                });
                //窗口取消按钮
                pageWindow.findViewById(R.id.window_cancel).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        controllerManager.getSuperPagesController().close();
                    }
                });
                controllerManager.getSuperPagesController().open(pageWindow);
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
                            controllerManager.getSuperPagesController().close();
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
                        controllerManager.getSuperPagesController().close();
                        loadAllConfigToSpinner();
                        loadCurrentConfig();
                    }
                });
                //窗口取消按钮
                pageWindow.findViewById(R.id.window_cancel).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        controllerManager.getSuperPagesController().close();
                    }
                });
                controllerManager.getSuperPagesController().open(pageWindow);
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
                            controllerManager.getSuperPagesController().close();
                            return;
                        }
                        controllerManager.getSuperConfigDatabaseHelper().deleteConfig(currentConfigId);
                        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
                        editor.putLong(CURRENT_CONFIG_KEY,0L);
                        editor.apply();
                        loadAllConfigToSpinner();
                        loadCurrentConfig();
                        controllerManager.getSuperPagesController().close();
                    }
                });
                //窗口取消按钮
                pageWindow.findViewById(R.id.window_cancel).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        controllerManager.getSuperPagesController().close();
                    }
                });
                controllerManager.getSuperPagesController().open(pageWindow);
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
            contentValues.put(COLUMN_BOOLEAN_TOUCH_ENABLE,String.valueOf(true));
            contentValues.put(COLUMN_BOOLEAN_TOUCH_MODE,String.valueOf(true));
            contentValues.put(COLUMN_INT_TOUCH_SENSE,100);
            //保存到数据库中
            controllerManager.getSuperConfigDatabaseHelper().insertConfig(contentValues);
            configIds = controllerManager.getSuperConfigDatabaseHelper().queryAllConfigIds();
        }
        configNames.clear();
        for (Long configId : configIds){
            String name = (String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(configId,COLUMN_STRING_CONFIG_NAME);
            configNames.add(name);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_spinner_item,
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
        boolean mouseEnable = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_TOUCH_ENABLE));
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
        Boolean mouseMode = Boolean.parseBoolean((String) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_TOUCH_MODE));
        mouseModeSwitch.setOnCheckedChangeListener(null);
        mouseModeSwitch.setChecked(mouseMode);
        controllerManager.getTouchController().setTouchMode(mouseMode);
        mouseModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
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
        int mouseSense = ((Long) controllerManager.getSuperConfigDatabaseHelper().queryConfigAttribute(currentConfigId, COLUMN_INT_TOUCH_SENSE)).intValue();
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
    public Long getCurrentConfigId(){
        return currentConfigId;
    }

    public void open(){
        controllerManager.getSuperPagesController().open(openPage);
    }

}
