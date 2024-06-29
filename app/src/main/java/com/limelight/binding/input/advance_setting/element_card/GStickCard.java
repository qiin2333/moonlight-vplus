package com.limelight.binding.input.advance_setting.element_card;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.EditController;
import com.limelight.binding.input.advance_setting.WindowsController;

import java.util.HashMap;
import java.util.Map;

public class GStickCard extends ElementCard{

    private LinearLayout buttonCardLayout;
    private TextView middleValue;
    private Spinner stick;

    public GStickCard(EditController editController, Context context){
        buttonCardLayout = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.element_type5_g_stick,null);
        middleValue = buttonCardLayout.findViewById(R.id.g_stick_card_middle_value);
        middleValue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editController.jumpDeviceLayout((TextView) v);
            }
        });
        stick = buttonCardLayout.findViewById(R.id.g_stick_card_stick);

    }


    @Override
    public Map<String, String> getTypeAttributes() {
        Map<String, String> typeAttributes = new HashMap<>();
        typeAttributes.put("middle_value",(String) middleValue.getTag());
        typeAttributes.put("value",stick.getSelectedItem().toString());
        return typeAttributes;
    }

    @Override
    public View getView() {
        return buttonCardLayout;
    }
}
