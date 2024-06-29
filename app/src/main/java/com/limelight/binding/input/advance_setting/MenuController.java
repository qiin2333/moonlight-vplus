package com.limelight.binding.input.advance_setting;

import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import com.limelight.R;

public class MenuController {

    public enum Mode{
        config,
        setting,
        edit,
    }

    private final ControllerManager controllerManager;

    private final FrameLayout layerMenu;
    private Mode lastMode;
    private Button lastButton;

    private Button openMenuButton;
    private Button closeMenuButton;
    private Button configModeButton;
    private Button settingModeButton;
    private Button editModeButton;

    public MenuController(ControllerManager controllerManager, FrameLayout layerMenu) {
        this.controllerManager = controllerManager;
        this.layerMenu = layerMenu;

        openMenuButton = layerMenu.findViewById(R.id.button_open_menu);
        openMenuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMenu();
            }
        });
        closeMenuButton = layerMenu.findViewById(R.id.button_close_menu);
        closeMenuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
            }
        });
        configModeButton = layerMenu.findViewById(R.id.button_config_mode);
        configModeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchMode(Mode.config);
            }
        });
        settingModeButton = layerMenu.findViewById(R.id.button_setting_mode);
        settingModeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchMode(Mode.setting);
            }
        });
        editModeButton = layerMenu.findViewById(R.id.button_edit_mode);
        editModeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchMode(Mode.edit);
            }
        });
        lastButton = settingModeButton;
        lastMode = Mode.setting;
    }

    public void showMenu(){
        layerMenu.setVisibility(View.VISIBLE);
    }

    public void hideMenu(){
        layerMenu.setVisibility(View.GONE);
    }


    private void openMenu(){
        openMenuButton.setVisibility(View.GONE);
        closeMenuButton.setVisibility(View.VISIBLE);
        configModeButton.setVisibility(View.VISIBLE);
        settingModeButton.setVisibility(View.VISIBLE);
        editModeButton.setVisibility(View.VISIBLE);
        lastButton.setAlpha(1);
        modeOpen(lastMode);
    }

    private void closeMenu(){
        openMenuButton.setVisibility(View.VISIBLE);
        closeMenuButton.setVisibility(View.GONE);
        configModeButton.setVisibility(View.GONE);
        settingModeButton.setVisibility(View.GONE);
        editModeButton.setVisibility(View.GONE);
        modeClose(lastMode);
    }

    private void modeOpen(Mode mode){
        switch (mode) {
            case setting:
                controllerManager.getSettingController().open();
                break;
            case edit:
                controllerManager.getEditController().open();
                break;
            case config:
                controllerManager.getConfigController().open();
                break;

        }
    }
    private void modeClose(Mode mode){
        switch (mode) {
            case setting:
                controllerManager.getSettingController().close();
                break;
            case edit:
                controllerManager.getEditController().close();
                break;
            case config:
                controllerManager.getConfigController().close();
                break;

        }
    }

    private void switchMode(Mode nowMode){
        modeClose(lastMode);
        lastButton.setAlpha(0.5f);
        lastButton.setClickable(true);

        modeOpen(nowMode);
        lastMode = nowMode;
        switch (nowMode){
            case config:
                lastButton = configModeButton;
                break;
            case setting:
                lastButton = settingModeButton;
                break;
            case edit:
                lastButton = editModeButton;
                break;
            default:
                return;
        }
        lastButton.setAlpha(1);
        lastButton.setClickable(false);



    }


}
