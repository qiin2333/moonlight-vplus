package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import com.limelight.R;

public class KeyboardUIController {

    private FrameLayout keyboardLayout;
    private ControllerManager controllerManager;
    private SeekBar opacitySeekbar;
    private LinearLayout keyboard;

    public KeyboardUIController(FrameLayout keyboardLayout, ControllerManager controllerManager, Context context){
        this.keyboardLayout = keyboardLayout;
        this.controllerManager = controllerManager;
        opacitySeekbar = keyboardLayout.findViewById(R.id.float_keyboard_seekbar);
        keyboard = keyboardLayout.findViewById(R.id.keyboard_drawing);

        opacitySeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float alpha = (float) (progress * 0.1);
                keyboardLayout.setAlpha(alpha);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                String keyString = (String) v.getTag();
                int keyCode = Integer.parseInt(keyString.substring(1));
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // 处理按下事件
                        v.setBackgroundResource(R.drawable.confirm_square_border);
                        controllerManager.getElementController().sendKeyEvent(true,(short) keyCode);
                        return true;
                    case MotionEvent.ACTION_UP:
                        // 处理释放事件
                        v.setBackgroundResource(R.drawable.square_border);
                        controllerManager.getElementController().sendKeyEvent(false,(short) keyCode);
                        return true;
                }
                return false;
            }
        };
        for (int i = 0; i < keyboard.getChildCount(); i++){
            LinearLayout keyboardRow = (LinearLayout) keyboard.getChildAt(i);
            for (int j = 0; j < keyboardRow.getChildCount(); j++){
                keyboardRow.getChildAt(j).setOnTouchListener(touchListener);
            }
        }
    }

    public void toggle() {
        if (keyboardLayout.getVisibility() == View.VISIBLE){
            keyboardLayout.setVisibility(View.INVISIBLE);
        } else {
            keyboardLayout.setVisibility(View.VISIBLE);
        }


    }

}
