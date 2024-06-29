package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.limelight.R;

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
        String currentConfigName = configListPreference.getCurrentConfigName();

        for (String name : configListPreference.getSortedConfigurationNames()){
            ConfigItem item = addConfigItem(name);
            if (name.equals(currentConfigName)){
                currentSelectItem = item;
                currentSelectItem.selected();
            }
        }

    }

    private ConfigItem addConfigItem(String layoutName){
        ConfigItem configItem = new ConfigItem(this,layoutName,context);

        configItemContainer.addView(configItem.getView(), (configItemContainer.getChildCount() - 1));
        return configItem;
    }



    public void loadCurrentConfig(){
        String configId = configListPreference.getConfigId(currentSelectItem.getText());
        controllerManager.getElementController().loadElementConfig(configId);
        controllerManager.getSettingController().loadSettingConfig(configId);
    }


    public void selectItem(ConfigItem configItem){
        currentSelectItem.unselected();
        currentSelectItem = configItem;
        currentSelectItem.selected();
        configListPreference.setCurrentConfigName(configItem.getText());
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
                if (!text.matches("^[a-zA-Z0-9]{1,10}$")){
                    Toast.makeText(context,"名称只能由1-10个数字、小写字母组成",Toast.LENGTH_SHORT).show();
                    return false;
                }
                configListPreference.addConfiguration(text);
                addConfigItem(text);
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
                String preName = configItem.getText();
                String nowName = text;
                //如果名字没有改变，点击确认键也可以返回，如果没有这个判断，点击确认键会显示名称已存在
                if (preName.equals(nowName)){
                    return true;
                }
                if (configListPreference.isContainedName(nowName)){
                    Toast.makeText(context,"配置名字已存在",Toast.LENGTH_SHORT).show();
                    return false;
                }
                if (!nowName.matches("^[a-zA-Z0-9]{1,10}$")){
                    Toast.makeText(context,"名称只能由1-10个数字、小写字母组成",Toast.LENGTH_SHORT).show();
                    return false;
                }
                //如果编辑的这个配置先前的名字和当前选择的配置的名字一样，则说明在修改选中的配置，需要修改存储中目前的配置名称
                if (preName.equals(currentSelectItem.getText())){
                    configListPreference.setCurrentConfigName(nowName);
                }
                configListPreference.renameConfiguration(preName,nowName);
                configItem.setText(nowName);
                return true;
            }

            @Override
            public boolean onCancelClick(String text) {
                return true;
            }

        };
        controllerManager.getWindowsController().openEditTextWindow(renameListener,configItem.getText(),"","",InputType.TYPE_CLASS_TEXT);
    }

    public void jumpDeleteWindow(ConfigItem configItem){
        WindowsController.TextWindowListener deleteListener = new WindowsController.TextWindowListener() {
            @Override
            public boolean onConfirmCLick() {
                String configName = configItem.getText();
                //1.先把element的preference删除
                new ElementPreference(configListPreference.getConfigId(configName), context).delete();
                //2.再把setting的preference删除
                new SettingPreference(configListPreference.getConfigId(configName),context).delete();
                //3.删除配置列表中的名字
                configListPreference.deleteConfig(configName);
                configItemContainer.removeView(configItem.getView());

                return true;
            }

            @Override
            public boolean onCancelClick() {
                return true;
            }

        };

        controllerManager.getWindowsController().openTextWindow(deleteListener, "是否删除:" + configItem.getText());
    }

    public void open(){
        layerConfig.setVisibility(View.VISIBLE);
    }

    public void close(){
        layerConfig.setVisibility(View.GONE);
    }




}
