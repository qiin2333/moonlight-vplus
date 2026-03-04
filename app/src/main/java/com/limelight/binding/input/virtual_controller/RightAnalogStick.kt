/**
 * Created by Karim Mreisi.
 */
package com.limelight.binding.input.virtual_controller

import android.content.Context
import com.limelight.nvstream.input.ControllerPacket

class RightAnalogStick(controller: VirtualController, context: Context) :
    AnalogStick(controller, context, EID_RS) {

    init {
        addAnalogStickListener(object : AnalogStickListener {
            override fun onMovement(x: Float, y: Float) {
                val inputContext = controller.controllerInputContext
                inputContext.rightStickX = (x * 0x7FFE).toInt().toShort()
                inputContext.rightStickY = (y * 0x7FFE).toInt().toShort()
                controller.sendControllerInputContext()
            }

            override fun onClick() {}

            override fun onDoubleClick() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap = (inputContext.inputMap.toInt() or ControllerPacket.RS_CLK_FLAG).toShort()
                controller.sendControllerInputContext()
            }

            override fun onRevoke() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap = (inputContext.inputMap.toInt() and ControllerPacket.RS_CLK_FLAG.inv()).toShort()
                controller.sendControllerInputContext()
            }
        })
    }
}
