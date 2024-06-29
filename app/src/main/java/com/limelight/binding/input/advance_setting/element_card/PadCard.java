package com.limelight.binding.input.advance_setting.element_card;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.EditController;

import java.util.HashMap;
import java.util.Map;

public class PadCard extends ElementCard{

    private LinearLayout kPadCardLayout;
    private TextView topValue;
    private TextView downValue;
    private TextView leftValue;
    private TextView rightValue;

    public PadCard(EditController editController, Context context){
        kPadCardLayout = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.element_type2_k_pad,null);
        topValue = kPadCardLayout.findViewById(R.id.element_k_pad_card_top);
        downValue = kPadCardLayout.findViewById(R.id.element_k_pad_card_down);
        leftValue = kPadCardLayout.findViewById(R.id.element_k_pad_card_left);
        rightValue = kPadCardLayout.findViewById(R.id.element_k_pad_card_right);

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
    }


    @Override
    public Map<String, String> getTypeAttributes() {
        Map<String, String> typeAttributes = new HashMap<>();
        typeAttributes.put("top_value",(String) topValue.getTag());
        typeAttributes.put("down_value",(String) downValue.getTag());
        typeAttributes.put("left_value",(String) leftValue.getTag());
        typeAttributes.put("right_value",(String) rightValue.getTag());
        return typeAttributes;
    }

    @Override
    public View getView() {
        return kPadCardLayout;
    }
}
