/**
 * Created by Karim Mreisi.
 */
package com.limelight.binding.input.virtual_controller

import android.content.Context

class LeftTrigger(controller: VirtualController, layer: Int, context: Context) :
    DigitalButton(controller, EID_LT, layer, context) {

    init {
        addDigitalButtonListener(object : DigitalButtonListener {
            override fun onClick() {
                val inputContext = controller.controllerInputContext
                inputContext.leftTrigger = 0xFF.toByte()
                controller.sendControllerInputContext()
            }

            override fun onLongClick() {}

            override fun onRelease() {
                val inputContext = controller.controllerInputContext
                inputContext.leftTrigger = 0x00.toByte()
                controller.sendControllerInputContext()
            }
        })
    }
}
