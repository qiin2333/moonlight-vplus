package com.limelight.binding.input.advance_setting;

import android.view.MotionEvent;
import android.view.View;

import com.limelight.Game;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TouchContext;

public class TouchController {

    final private Game game;
    final private ControllerManager controllerManager;
    final private View touchView;

    public TouchController(Game game, ControllerManager controllerManager, View touchView) {
        this.game = game;
        this.controllerManager = controllerManager;
        this.touchView = touchView;
        touchView.setOnTouchListener(game);
    }

    public void adjustTouchSense(int sense){
        for (TouchContext aTouchContext : game.getTouchContextMap()) {
            if (aTouchContext instanceof RelativeTouchContext){
                ((RelativeTouchContext) aTouchContext).adjustMsense(sense * 0.01);
            }
        }
    }
    /**
     * false : AbsoluteTouchContext
     * true : RelativeTouchContext
     */
    public void setTouchMode(boolean enableRelativeTouch){
        game.setTouchMode(enableRelativeTouch);
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

    public void mouseMove(int deltaX, int deltaY){
        game.mouseMove(deltaX,deltaY);
    }





}
