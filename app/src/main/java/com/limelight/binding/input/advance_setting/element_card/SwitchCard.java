package com.limelight.binding.input.advance_setting.element_card;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.EditController;
import com.limelight.binding.input.advance_setting.ElementBean;
import com.limelight.binding.input.advance_setting.WindowsController;

import java.util.HashMap;
import java.util.Map;

public class SwitchCard extends ElementCard{

    private LinearLayout switchCardLayout;
    private TextView value;
    private Spinner shape;

    public SwitchCard(EditController editController,Context context){
        switchCardLayout = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.element_type1_switch,null);
        value = switchCardLayout.findViewById(R.id.switch_card_value);
        value.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editController.jumpDeviceLayout((TextView) v);
            }
        });
        shape = switchCardLayout.findViewById(R.id.switch_card_shape);

    }


    @Override
    public Map<String, String> getTypeAttributes() {
        Map<String, String> typeAttributes = new HashMap<>();
        typeAttributes.put("value",(String) value.getTag());
        typeAttributes.put("shape",shape.getSelectedItem().toString());
        return typeAttributes;
    }

    @Override
    public View getView() {
        return switchCardLayout;
    }
}
