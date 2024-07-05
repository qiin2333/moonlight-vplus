package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.R;

public class ControllerManager {

    private FrameLayout advanceSettingView;
    private FrameLayout fatherLayout;
    private MenuController menuController;
    private ConfigController configController;
    private EditController editController;
    private SettingController settingController;
    private ElementController elementController;
    private TouchController touchController;
    private WindowsController windowsController;
    private Context context;

    public ControllerManager(FrameLayout layout, Context context){
        advanceSettingView = (FrameLayout) LayoutInflater.from(context).inflate(R.layout.advance_setting_view,null);
        this.fatherLayout = layout;

        //window controller
        FrameLayout layerWindows = advanceSettingView.findViewById(R.id.layer_windows);
        windowsController = new WindowsController(this,layerWindows,context);

        FrameLayout layerElement = advanceSettingView.findViewById(R.id.layer_element);
        elementController = new ElementController(this,layerElement,context);

        View touchView = layerElement.findViewById(R.id.element_touch_view);
        touchController = new TouchController((Game) context,this,touchView);

        //Edit controller
        FrameLayout layerEdit = advanceSettingView.findViewById(R.id.layer_edit);
        editController = new EditController(this,layerEdit,context);

        //setting controller
        FrameLayout layerFloat = advanceSettingView.findViewById(R.id.layer_float);
        FrameLayout layerSetting = advanceSettingView.findViewById(R.id.layer_setting);
        settingController = new SettingController(this,layerSetting,layerFloat,context);

        //configController
        FrameLayout layerConfig = advanceSettingView.findViewById(R.id.layer_config);
        configController = new ConfigController(this,layerConfig,context);

        //menuController 创建并注册
        FrameLayout layerMenu = advanceSettingView.findViewById(R.id.layer_menu);
        menuController = new MenuController(this,layerMenu);


        //configController.loadCurrentConfig();
    }

    public MenuController getMenuController() {
        return menuController;
    }

    public ConfigController getConfigController() {
        return configController;
    }

    public EditController getEditController() {
        return editController;
    }

    public SettingController getSettingController() {
        return settingController;
    }

    public ElementController getElementController() {
        return elementController;
    }

    public WindowsController getWindowsController() {
        return windowsController;
    }

    public TouchController getTouchController() {
        return touchController;
    }

    public void refreshLayout(){
        fatherLayout.removeView(advanceSettingView);
        fatherLayout.addView(advanceSettingView);
    }

}
