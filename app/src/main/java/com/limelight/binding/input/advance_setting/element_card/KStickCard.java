package com.limelight.binding.input.advance_setting.element_card;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.EditController;
import com.limelight.binding.input.advance_setting.WindowsController;

import java.util.HashMap;
import java.util.Map;

public class KStickCard extends ElementCard{

    private LinearLayout kStickCardLayout;
    private TextView topValue;
    private TextView downValue;
    private TextView leftValue;
    private TextView rightValue;
    private TextView middleValue;

    public KStickCard(EditController editController, Context context){
        kStickCardLayout = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.element_type4_k_stick,null);
        topValue = kStickCardLayout.findViewById(R.id.element_k_stick_card_top);
        downValue = kStickCardLayout.findViewById(R.id.element_k_stick_card_down);
        leftValue = kStickCardLayout.findViewById(R.id.element_k_stick_card_left);
        rightValue = kStickCardLayout.findViewById(R.id.element_k_stick_card_right);
        middleValue = kStickCardLayout.findViewById(R.id.element_k_stick_card_middle);

        View.OnClickListener jumpDeviceLayout = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editController.jumpDeviceLayout((TextView) v);
            }
        };


        topValue.setOnClickListener(jumpDeviceLayout);
        downValue.setOnClickListener(jumpDeviceLayout);
        leftValue.setOnClickListener(jumpDeviceLayout);
        rightValue.setOnClickListener(jumpDeviceLayout);
        middleValue.setOnClickListener(jumpDeviceLayout);
    }


    @Override
    public Map<String, String> getTypeAttributes() {
        Map<String, String> typeAttributes = new HashMap<>();
        typeAttributes.put("top_value",(String) topValue.getTag());
        typeAttributes.put("down_value",(String) downValue.getTag());
        typeAttributes.put("left_value",(String) leftValue.getTag());
        typeAttributes.put("right_value",(String) rightValue.getTag());
        typeAttributes.put("middle_value",(String) middleValue.getTag());
        return typeAttributes;
    }

    @Override
    public View getView() {
        return kStickCardLayout;
    }
}
