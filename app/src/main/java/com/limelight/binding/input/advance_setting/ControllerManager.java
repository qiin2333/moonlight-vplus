package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.config.PageConfigController;
import com.limelight.binding.input.advance_setting.element.ElementController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController;

public class ControllerManager {

    private FrameLayout advanceSettingView;
    private FrameLayout fatherLayout;
    private PageConfigController pageConfigController;
    private TouchController touchController;
    private SuperPagesController superPagesController;
    private PageDeviceController pageDeviceController;
    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private ElementController elementController;
    private PageSuperMenuController pageSuperMenuController;
    private KeyboardUIController keyboardUIController;
    private Context context;

    public ControllerManager(FrameLayout layout, Context context){
        advanceSettingView = layout.findViewById(R.id.advance_setting_view);
        this.fatherLayout = layout;
        this.context = context;
        pageSuperMenuController = new PageSuperMenuController(context,this);
    }


    public PageConfigController getPageConfigController() {
        if (pageConfigController == null){
            pageConfigController = new PageConfigController(this,context);
        }
        return pageConfigController;
    }


    public TouchController getTouchController() {
        if (touchController == null){
            FrameLayout layerElement = advanceSettingView.findViewById(R.id.layer_2_element);
            touchController = new TouchController((Game) context,this,layerElement.findViewById(R.id.element_touch_view));
        }
        return touchController;
    }


    public SuperPagesController getSuperPagesController() {
        if (superPagesController == null){
            FrameLayout superPagesBox = advanceSettingView.findViewById(R.id.super_pages_box);
            superPagesController = new SuperPagesController(superPagesBox,context);
        }
        return superPagesController;
    }

    public PageDeviceController getPageDeviceController() {
        if (pageDeviceController == null){
            pageDeviceController = new PageDeviceController(context,this);
        }
        return pageDeviceController;
    }

    public SuperConfigDatabaseHelper getSuperConfigDatabaseHelper() {
        if (superConfigDatabaseHelper == null){
            superConfigDatabaseHelper = new SuperConfigDatabaseHelper(context);
        }
        return superConfigDatabaseHelper;
    }

    public ElementController getElementController() {
        if (elementController == null){
            FrameLayout layerElement = advanceSettingView.findViewById(R.id.layer_2_element);
            elementController = new ElementController(this,layerElement,context);
        }
        return elementController;
    }

    public PageSuperMenuController getPageSuperMenuController() {
        return pageSuperMenuController;
    }

    public KeyboardUIController getKeyboardUIController(){
        if (keyboardUIController == null){
            FrameLayout layoutKeyboard = advanceSettingView.findViewById(R.id.layer_6_keyboard);
            keyboardUIController = new KeyboardUIController(layoutKeyboard,this,context);
        }
        return keyboardUIController;
    }

    public void refreshLayout(){
        getPageConfigController().initConfig();
    }

    /**
     * 隐藏王冠功能界面
     */
    public void hide() {
        if (advanceSettingView != null) {
            advanceSettingView.setVisibility(android.view.View.GONE);
        }
    }

    /**
     * 显示王冠功能界面
     */
    public void show() {
        if (advanceSettingView != null) {
            advanceSettingView.setVisibility(android.view.View.VISIBLE);
        }
    }

}
