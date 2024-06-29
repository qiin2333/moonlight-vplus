package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.KeyEvent;

public class AnalogKStick extends AnalogStick {

    private boolean upIsPressed = false;
    private boolean downIsPressed = false;
    private boolean leftIsPressed = false;
    private boolean rightIsPressed = false;
    public AnalogKStick(ElementController controller, ElementBean elementBean, Context context){
        super(controller, elementBean, context);

        ElementController.SendEventHandler topSender = controller.getSendEventHandler(elementBean.getTypeAttributes().get("top_value"));
        ElementController.SendEventHandler downSender = controller.getSendEventHandler(elementBean.getTypeAttributes().get("down_value"));
        ElementController.SendEventHandler leftSender = controller.getSendEventHandler(elementBean.getTypeAttributes().get("left_value"));
        ElementController.SendEventHandler rightSender = controller.getSendEventHandler(elementBean.getTypeAttributes().get("right_value"));
        ElementController.SendEventHandler middleSender = controller.getSendEventHandler(elementBean.getTypeAttributes().get("middle_value"));

        addAnalogStickListener(new AnalogStick.AnalogStickListener() {
            @Override
            public void onMovement(float x, float y) {
                if (x < -0.4 && !leftIsPressed){
                    leftSender.sendEvent(true);
                    leftIsPressed = true;
                } else if (x > -0.4 && leftIsPressed) {
                    leftSender.sendEvent(false);
                    leftIsPressed = false;
                }
                if (x > 0.4 && !rightIsPressed){
                    rightSender.sendEvent(true);
                    rightIsPressed = true;
                } else if (x < 0.4 && rightIsPressed) {
                    rightSender.sendEvent(false);
                    rightIsPressed = false;
                }
                if (y < -0.4 && !downIsPressed){
                    downSender.sendEvent(true);
                    downIsPressed = true;
                } else if (y > -0.4 && downIsPressed) {
                    downSender.sendEvent(false);
                    downIsPressed = false;
                }
                if (y > 0.4 && !upIsPressed){
                    topSender.sendEvent(true);
                    upIsPressed = true;
                } else if(y < 0.4 && upIsPressed){
                    topSender.sendEvent(false);
                    upIsPressed = false;
                }

            }

            @Override
            public void onClick() {
            }

            @Override
            public void onDoubleClick() {
                middleSender.sendEvent(true);
            }

            @Override
            public void onRevoke() {
                middleSender.sendEvent(false);
            }
        });

    }



}
