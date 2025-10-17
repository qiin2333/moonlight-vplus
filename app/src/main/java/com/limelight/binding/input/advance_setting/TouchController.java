package com.limelight.binding.input.advance_setting;

import android.os.Build;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.Game;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TouchContext;

public class TouchController {

    final private Game game;
    final private ControllerManager controllerManager;
    final private View touchView;
    private double xFactor;
    private double yFactor;

    // --- 新增：一个可复用的、用于屏蔽所有触摸的监听器 ---
    // 这比每次都创建一个新的匿名内部类效率更高。
    private final View.OnTouchListener blockingListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            // 消费掉事件，什么也不做。这就像一个“触摸护盾”。
            return true;
        }
    };

    public TouchController(Game game, ControllerManager controllerManager, View touchView) {
        this.game = game;
        this.controllerManager = controllerManager;
        this.touchView = touchView;

        // 将初始状态设置为“激活”
        touchView.setOnTouchListener(game);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            touchView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON |
                            InputDevice.SOURCE_CLASS_JOYSTICK |
                            InputDevice.SOURCE_CLASS_POINTER |
                            InputDevice.SOURCE_CLASS_POSITION |
                            InputDevice.SOURCE_CLASS_TRACKBALL
            );
        }
    }

    // --- 以下的旧方法保持不变 ---
    public void adjustTouchSense(int sense) {
        for (TouchContext aTouchContext : game.getRelativeTouchContextMap()) {
            ((RelativeTouchContext) aTouchContext).adjustMsense(sense * 0.01);
        }
    }

    public void setTouchMode(boolean enableRelativeTouch) {
        game.setTouchMode(enableRelativeTouch);
    }

    public void setEnhancedTouch(boolean enableRelativeTouch) {
        game.setEnhancedTouch(enableRelativeTouch);
    }

    public void mouseMove(float deltaX, float deltaY, double sense) {
        float preDeltaX = deltaX;
        float preDeltaY = deltaY;
        xFactor = Game.REFERENCE_HORIZ_RES / (double) touchView.getWidth() * sense;
        yFactor = Game.REFERENCE_VERT_RES / (double) touchView.getHeight() * sense;
        deltaX = (int) Math.round((double) Math.abs(deltaX) * xFactor);
        deltaY = (int) Math.round((double) Math.abs(deltaY) * yFactor);
        if (preDeltaX < 0) {
            deltaX = -deltaX;
        }
        if (preDeltaY < 0) {
            deltaY = -deltaY;
        }
        game.mouseMove((int) deltaX, (int) deltaY);
    }

    // --- 修改及新增的触摸状态控制方法 ---

    /**
     * 控制虚拟手柄的触摸是“激活”状态还是“屏蔽”状态。
     * @param enable true 为激活 (由 Game 处理触摸), false 为屏蔽 (触摸被消费掉)。
     */
    public void enableTouch(boolean enable) {
        if (enable) {
            // 状态 1: 激活 - 触摸事件由 Game Activity 处理，用于虚拟按键。
            touchView.setOnTouchListener(game);
        } else {
            // 状态 2: 屏蔽 - 触摸事件被 blockingListener 拦截并消费掉。
            touchView.setOnTouchListener(blockingListener);
        }
    }

    /**
     * --- 这是新增的关键方法 ---
     * 控制触摸事件是否应该完全“绕过”虚拟按键层。
     * 这用于实现底层 StreamView 的手势缩放等功能。
     * @param bypass true 表示移除监听器，让事件“穿透”过去。
     *               false 表示恢复默认的监听器 (即“激活”状态)。
     */
    public void setTouchBypass(boolean bypass) {
        if (bypass) {
            // 状态 3: 绕过 - 不设置任何监听器，这样触摸事件就会传递给
            // 下层的视图 (例如，用于手势操作的 StreamView)。
            touchView.setOnTouchListener(null);
        } else {
            // 当关闭“绕过”模式时，恢复到默认的“激活”状态。
            touchView.setOnTouchListener(game);
        }
    }
}