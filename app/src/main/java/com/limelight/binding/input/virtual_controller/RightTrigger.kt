/**
 * Created by Karim Mreisi.
 */
package com.limelight.binding.input.virtual_controller

import android.content.Context

class RightTrigger(controller: VirtualController, layer: Int, context: Context) :
    DigitalButton(controller, EID_RT, layer, context) {

    init {
        addDigitalButtonListener(object : DigitalButtonListener {
            override fun onClick() {
                val inputContext = controller.controllerInputContext
                inputContext.rightTrigger = 0xFF.toByte()
                controller.sendControllerInputContext()
            }

            override fun onLongClick() {}

            override fun onRelease() {
                val inputContext = controller.controllerInputContext
                inputContext.rightTrigger = 0x00.toByte()
                controller.sendControllerInputContext()
            }
        })
    }
}
