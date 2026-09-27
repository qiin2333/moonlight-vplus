/**
 * Created by Karim Mreisi.
 */
package com.limelight.binding.input.virtual_controller

import android.content.Context
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_LS
import com.limelight.nvstream.input.ControllerPacket

class LeftAnalogStick(controller: VirtualController, context: Context) :
    AnalogStick(controller, context, EID_LS) {

    init {
        addAnalogStickListener(object : AnalogStickListener {
            override fun onMovement(x: Float, y: Float) {
                val inputContext = controller.controllerInputContext
                inputContext.leftStickX = (x * 0x7FFE).toInt().toShort()
                inputContext.leftStickY = (y * 0x7FFE).toInt().toShort()
                controller.sendControllerInputContext()
            }

            override fun onClick() {}

            override fun onDoubleClick() {
                controller.setButtonState(this, ControllerPacket.LS_CLK_FLAG, true)
            }

            override fun onRevoke() {
                controller.setButtonState(this, ControllerPacket.LS_CLK_FLAG, false)
            }
        })
    }
}
