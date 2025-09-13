package com.limelight.binding.input.advance_setting;

import android.os.Build;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.Game;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TouchContext;

public class TouchController{

    final private Game game;
    final private ControllerManager controllerManager;
    final private View touchView;
    private double xFactor;
    private double yFactor;

    public TouchController(Game game, ControllerManager controllerManager, View touchView) {
        this.game = game;
        this.controllerManager = controllerManager;
        this.touchView = touchView;
        touchView.setOnTouchListener(game);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Request unbuffered input event dispatching for all input classes we handle here.
            // Without this, input events are buffered to be delivered in lock-step with VBlank,
            // artificially increasing input latency while streaming.
            touchView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                            InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                            InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                            InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                            InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
        }


    }

    public void adjustTouchSense(int sense){
        for (TouchContext aTouchContext : game.getRelativeTouchContextMap()) {
            ((RelativeTouchContext) aTouchContext).adjustMsense(sense * 0.01);
        }
    }
    /**
     * false : AbsoluteTouchContext
     * true : RelativeTouchContext
     */
    public void setTouchMode(boolean enableRelativeTouch){
        game.setTouchMode(enableRelativeTouch);
    }

    public void setEnhancedTouch(boolean enableRelativeTouch){
        game.setEnhancedTouch(enableRelativeTouch);
    }

    public void enableTouch(boolean enable){
        if (enable) {
            touchView.setOnTouchListener(game);
        } else {
            touchView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return true;
                }
            });
        }
    }

    public void mouseMove(float deltaX, float deltaY, double sense){
        float preDeltaX = deltaX;
        float preDeltaY = deltaY;
        xFactor = Game.REFERENCE_HORIZ_RES / (double)touchView.getWidth() * sense;
        yFactor = Game.REFERENCE_VERT_RES / (double)touchView.getHeight() * sense;

        // Scale the deltas based on the factors passed to our constructor
        deltaX = (int) Math.round((double) Math.abs(deltaX) * xFactor);
        deltaY = (int) Math.round((double) Math.abs(deltaY) * yFactor);

        // Fix up the signs
        if (preDeltaX < 0) {
            deltaX = -deltaX;
        }
        if (preDeltaY < 0) {
            deltaY = -deltaY;
        }
        game.mouseMove((int) deltaX,(int) deltaY);
    }


}
