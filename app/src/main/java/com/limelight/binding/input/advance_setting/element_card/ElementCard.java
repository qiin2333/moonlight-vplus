package com.limelight.binding.input.advance_setting.element_card;

import android.view.View;

import com.limelight.binding.input.advance_setting.ElementBean;

import java.util.Map;

public abstract class ElementCard {

    public abstract Map<String, String> getTypeAttributes();

    public abstract View getView();
}
