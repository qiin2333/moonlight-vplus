package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import com.limelight.R;

import java.util.Map;

public class ConfigItem {



    private LinearLayout configItemLayout;
    private CheckBox configItemCheckBox;
    private Button configItemRenameButton;
    private Button configItemDeleteButton;
    private ConfigItem myself;
    private ConfigController configController;
    private final String configId;


    public ConfigItem(ConfigController configController,String configName, String configId,Context context){
        this.myself = this;
        this.configController = configController;
        this.configId = configId;

        configItemLayout = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.config_item,null);
        configItemCheckBox = (CheckBox) configItemLayout.getChildAt(0);
        configItemRenameButton = (Button) ((LinearLayout) configItemLayout.getChildAt(1)).getChildAt(0);
        configItemDeleteButton = (Button) ((LinearLayout) configItemLayout.getChildAt(1)).getChildAt(1);
        setName(configName);
        if (configId.equals(ConfigListPreference.DEFAULT_CONFIG_ID)){
            (configItemLayout.getChildAt(1)).setVisibility(View.INVISIBLE);
        }
        configItemCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                configController.selectItem(myself);
            }
        });
        configItemRenameButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                configController.jumpRenameWindow(myself);
            }
        });

        configItemDeleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                configController.jumpDeleteWindow(myself);
            }
        });
    }

    public void setName(String text){
        configItemCheckBox.setText(text);
    }

    public String getName(){
        return configItemCheckBox.getText().toString();
    }

    public String getId(){
        return configId;
    }

    public void selected(){
        configItemCheckBox.setClickable(false);
        displayDeleteButton(false);
        displayRenameButton(false);
        configItemCheckBox.setChecked(true);
    }

    public void unselected(){
        configItemCheckBox.setClickable(true);
        displayDeleteButton(true);
        displayRenameButton(true);
        configItemCheckBox.setChecked(false);
    }

    public View getView(){
        return configItemLayout;
    }

    private void displayDeleteButton(boolean display){
        if (display){
            configItemDeleteButton.setVisibility(View.VISIBLE);
        } else {
            //这里必须是INVISIBLE，这里使用GONE的话，item高度会变化
            configItemDeleteButton.setVisibility(View.INVISIBLE);
        }
    }

    private void displayRenameButton(boolean display){
        if (display){
            configItemRenameButton.setVisibility(View.VISIBLE);
        } else {
            //这里必须是INVISIBLE，这里使用GONE的话，item高度会变化
            configItemRenameButton.setVisibility(View.INVISIBLE);
        }
    }
}
