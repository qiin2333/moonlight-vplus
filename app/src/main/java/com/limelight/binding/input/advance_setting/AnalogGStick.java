package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.KeyEvent;

import com.limelight.binding.input.virtual_controller.VirtualController;

public class AnalogGStick extends AnalogStick{


    public AnalogGStick(ElementController controller, ElementBean elementBean, Context context){
        super(controller, elementBean, context);
        ElementController.SendEventHandler sendStickEventHandler = controller.getSendEventHandler(elementBean.getTypeAttributes().get("value"));
        ElementController.SendEventHandler sendButtonEventHandler = controller.getSendEventHandler(elementBean.getTypeAttributes().get("middle_value"));
        addAnalogStickListener(new AnalogStick.AnalogStickListener() {
            @Override
            public void onMovement(float x, float y) {
                sendStickEventHandler.sendEvent((int) (x * 0x7FFE),(int) (y * 0x7FFE));
            }

            @Override
            public void onClick() {
            }

            @Override
            public void onDoubleClick() {
                sendButtonEventHandler.sendEvent(true);
            }

            @Override
            public void onRevoke() {
                sendButtonEventHandler.sendEvent(false);
            }
        });

    }

}
