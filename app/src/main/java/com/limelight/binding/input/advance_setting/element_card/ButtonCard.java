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

public class ButtonCard extends ElementCard{

    private LinearLayout buttonCardLayout;
    private TextView value;
    private Spinner shape;

    public ButtonCard(EditController editController,Context context){
        buttonCardLayout = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.element_type0_button,null);
        value = buttonCardLayout.findViewById(R.id.button_card_value);
        value.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editController.jumpDeviceLayout((TextView) v);
            }
        });
        shape = buttonCardLayout.findViewById(R.id.button_card_shape);

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
        return buttonCardLayout;
    }
}
