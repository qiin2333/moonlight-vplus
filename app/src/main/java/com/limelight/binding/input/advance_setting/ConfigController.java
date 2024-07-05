package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.limelight.R;

import java.util.Map;

public class ConfigController {



    private FrameLayout layerConfig;
    private ConfigListPreference configListPreference;
    private Context context;
    private ConfigItem currentSelectItem;

    private ControllerManager controllerManager;

    private LinearLayout configItemContainer;



    public ConfigController(ControllerManager controllerManager,FrameLayout layout, Context context){
        this.context = context;
        this.layerConfig = layout;
        this.controllerManager = controllerManager;
        configListPreference = new ConfigListPreference(context);
        configItemContainer = layout.findViewById(R.id.config_item_container);
        layout.findViewById(R.id.add_config_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpAddWindow();
            }
        });
        loadConfigs();
    }

    private void loadConfigs(){
        String currentConfigId = configListPreference.getCurrentConfigId();

        for (Map.Entry<String,String> entry: configListPreference.getSortedConfigurationMap().entrySet()){
            String configId = entry.getKey();
            ConfigItem item = addDispalyConfigItem(configId,entry.getValue());
            if (configId.equals(currentConfigId)){
                selectItem(item);
            }
        }

    }

    private void addConfig(String configName){
        String configId = String.valueOf(System.currentTimeMillis());
        configListPreference.addConfiguration(configId,configName);
        addDispalyConfigItem(configId,configName);
    }

    private ConfigItem addDispalyConfigItem(String layoutId, String layoutName){
        ConfigItem configItem = new ConfigItem(this,layoutName,layoutId,context);

        configItemContainer.addView(configItem.getView(), (configItemContainer.getChildCount() - 1));
        return configItem;
    }



    public void loadCurrentConfig(){
        String configId = configListPreference.getCurrentConfigId();
        controllerManager.getElementController().loadElementConfig(configId);
        controllerManager.getSettingController().loadSettingConfig(configId);
    }


    public void selectItem(ConfigItem configItem){
        if (currentSelectItem != null){
            currentSelectItem.unselected();
        }
        currentSelectItem = configItem;
        currentSelectItem.selected();
        configListPreference.setCurrentConfigId(configItem.getId());
        loadCurrentConfig();

    }

    public void jumpAddWindow(){
        WindowsController.EditTextWindowListener addListener = new WindowsController.EditTextWindowListener() {
            @Override
            public boolean onConfirmClick(String text) {
                if (configListPreference.isContainedName(text)){
                    Toast.makeText(context,"配置名字已存在",Toast.LENGTH_SHORT).show();
                    return false;
                }
                if (!text.matches("^[\\u4e00-\\u9fa5a-zA-Z0-9]{1,15}$")){
                    Toast.makeText(context,"名称不能有符号，且长度为1-15个字符",Toast.LENGTH_SHORT).show();
                    return false;
                }
                addConfig(text);
                return true;
            }

            @Override
            public boolean onCancelClick(String text) {
                return true;
            }
        };
        controllerManager.getWindowsController().openEditTextWindow(addListener,"","","", InputType.TYPE_CLASS_TEXT);
    }



    public void jumpRenameWindow(ConfigItem configItem){
        WindowsController.EditTextWindowListener renameListener = new WindowsController.EditTextWindowListener() {
            @Override
            public boolean onConfirmClick(String text) {
                String nowName = text;
                //如果名字没有改变，点击确认键也可以返回，如果没有这个判断，点击确认键会显示名称已存在
                if (configItem.getName().equals(nowName)){
                    return true;
                }
                if (configListPreference.isContainedName(nowName)){
                    Toast.makeText(context,"配置名字已存在",Toast.LENGTH_SHORT).show();
                    return false;
                }
                if (!nowName.matches("^[\\u4e00-\\u9fa5a-zA-Z0-9]{1,15}$")){
                    Toast.makeText(context,"名称不能有符号，且长度为1-15个字符",Toast.LENGTH_SHORT).show();
                    return false;
                }
                configListPreference.renameConfiguration(configItem.getId(),nowName);
                configItem.setName(nowName);
                return true;
            }

            @Override
            public boolean onCancelClick(String text) {
                return true;
            }

        };
        controllerManager.getWindowsController().openEditTextWindow(renameListener,configItem.getName(),"","",InputType.TYPE_CLASS_TEXT);
    }

    public void jumpDeleteWindow(ConfigItem configItem){
        WindowsController.TextWindowListener deleteListener = new WindowsController.TextWindowListener() {
            @Override
            public boolean onConfirmCLick() {
                String configId = configItem.getId();
                //1.先把element的preference删除
                new ElementPreference(configId, context).delete();
                //2.再把setting的preference删除
                new SettingPreference(configId,context).delete();
                //3.删除配置列表中的名字
                configListPreference.deleteConfig(configId);
                configItemContainer.removeView(configItem.getView());

                return true;
            }

            @Override
            public boolean onCancelClick() {
                return true;
            }

        };

        controllerManager.getWindowsController().openTextWindow(deleteListener, "是否删除:" + configItem.getName());
    }

    public void open(){
        layerConfig.setVisibility(View.VISIBLE);
    }

    public void close(){
        layerConfig.setVisibility(View.GONE);
    }




}
