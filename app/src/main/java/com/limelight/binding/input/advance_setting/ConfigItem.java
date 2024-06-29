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


    public ConfigItem(ConfigController configController,String configName, Context context){
        this.myself = this;
        this.configController = configController;

        configItemLayout = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.config_item,null);
        configItemCheckBox = (CheckBox) configItemLayout.getChildAt(0);
        configItemRenameButton = (Button) ((LinearLayout) configItemLayout.getChildAt(1)).getChildAt(0);
        configItemDeleteButton = (Button) ((LinearLayout) configItemLayout.getChildAt(1)).getChildAt(1);
        setText(configName);
        if (configName.equals(ConfigListPreference.DEFAULT_CONFIG_NAME)){
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

    public void setText(String text){
        configItemCheckBox.setText(text);
    }

    public String getText(){
        return configItemCheckBox.getText().toString();
    }

    public void selected(){
        configItemCheckBox.setClickable(false);
        displayDeleteButton(false);
        configItemCheckBox.setChecked(true);
    }

    public void unselected(){
        configItemCheckBox.setClickable(true);
        displayDeleteButton(true);
        configItemCheckBox.setChecked(false);
    }

    public View getView(){
        return configItemLayout;
    }

    private void displayDeleteButton(boolean display){
        if (display){
            configItemDeleteButton.setVisibility(View.VISIBLE);
        } else {
            configItemDeleteButton.setVisibility(View.GONE);
        }
    }
}
