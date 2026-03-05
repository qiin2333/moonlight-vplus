package com.limelight.binding.input.advance_setting

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

import com.limelight.Game
import com.limelight.binding.input.touch.RelativeTouchContext

class TouchController(
    private val game: Game,
    private val controllerManager: ControllerManager,
    private val touchView: View
) {
    private var xFactor = 0.0
    private var yFactor = 0.0

    // --- 新增：一个可复用的、用于屏蔽所有触摸的监听器 ---
    private val blockingListener = View.OnTouchListener { _, _ ->
        // 消费掉事件，什么也不做。这就像一个"触摸护盾"。
        true
    }

    init {
        // 将初始状态设置为"激活"
        touchView.setOnTouchListener(game)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            touchView.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_BUTTON or
                        InputDevice.SOURCE_CLASS_JOYSTICK or
                        InputDevice.SOURCE_CLASS_POINTER or
                        InputDevice.SOURCE_CLASS_POSITION or
                        InputDevice.SOURCE_CLASS_TRACKBALL
            )
        }
    }

    fun adjustTouchSense(sense: Int) {
        for (touchContext in game.relativeTouchContextMap) {
            (touchContext as RelativeTouchContext).adjustMsense(sense * 0.01)
        }
    }

    fun setTouchMode(enableRelativeTouch: Boolean) {
        game.setTouchMode(enableRelativeTouch)
    }

    fun setEnhancedTouch(enableRelativeTouch: Boolean) {
        game.setEnhancedTouch(enableRelativeTouch)
    }

    fun mouseMove(deltaX: Float, deltaY: Float, sense: Double) {
        val preDeltaX = deltaX
        val preDeltaY = deltaY
        xFactor = Game.REFERENCE_HORIZ_RES / touchView.width.toDouble() * sense
        yFactor = Game.REFERENCE_VERT_RES / touchView.height.toDouble() * sense
        var adjustedDeltaX = Math.round(Math.abs(deltaX).toDouble() * xFactor).toInt()
        var adjustedDeltaY = Math.round(Math.abs(deltaY).toDouble() * yFactor).toInt()
        if (preDeltaX < 0) {
            adjustedDeltaX = -adjustedDeltaX
        }
        if (preDeltaY < 0) {
            adjustedDeltaY = -adjustedDeltaY
        }
        game.mouseMove(adjustedDeltaX, adjustedDeltaY)
    }

    /**
     * 控制虚拟手柄的触摸是"激活"状态还是"屏蔽"状态。
     * @param enable true 为激活 (由 Game 处理触摸), false 为屏蔽 (触摸被消费掉)。
     */
    fun enableTouch(enable: Boolean) {
        if (enable) {
            touchView.setOnTouchListener(game)
        } else {
            touchView.setOnTouchListener(blockingListener)
        }
    }

    /**
     * 控制触摸事件是否应该完全"绕过"虚拟按键层。
     */
    fun setTouchBypass(bypass: Boolean) {
        if (bypass) {
            touchView.setOnTouchListener(null)
        } else {
            touchView.setOnTouchListener(game)
        }
    }
}
