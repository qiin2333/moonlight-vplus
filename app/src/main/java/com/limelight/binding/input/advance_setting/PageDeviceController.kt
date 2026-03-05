package com.limelight.binding.input.advance_setting

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

import com.limelight.R
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

class PageDeviceController(
    private val context: Context,
    private val controllerManager: ControllerManager
) {
    fun interface DeviceCallBack {
        fun OnKeyClick(key: TextView)
    }

    private val devicePage: SuperPageLayout =
        LayoutInflater.from(context).inflate(R.layout.page_device, null) as SuperPageLayout
    private val keyboardDrawing: LinearLayout = devicePage.findViewById(R.id.keyboard_drawing)
    private val mouseDrawing: FrameLayout = devicePage.findViewById(R.id.mouse_drawing)
    private val gamepadDrawing: FrameLayout = devicePage.findViewById(R.id.gamepad_drawing)
    private var deviceCallBack: DeviceCallBack? = null

    init {
        val onClickListener = View.OnClickListener { v ->
            // 确保回调不为空，并且点击的是TextView，避免意外的类型转换错误
            if (deviceCallBack != null && v is TextView) {
                deviceCallBack!!.OnKeyClick(v)
                close()
            }
        }
        setListenersForDevice(devicePage, onClickListener)

        devicePage.findViewById<View>(R.id.device_cancel).setOnClickListener {
            close()
        }
    }

    fun open(deviceCallBack: DeviceCallBack, keyboardVisible: Int, mouseVisible: Int, gamepadVisible: Int) {
        this.deviceCallBack = deviceCallBack
        keyboardDrawing.visibility = keyboardVisible
        mouseDrawing.visibility = mouseVisible
        gamepadDrawing.visibility = gamepadVisible
        controllerManager.superPagesController.openNewPage(devicePage)
    }

    private fun setListenersForDevice(viewGroup: ViewGroup, listener: View.OnClickListener) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            // 只为带有tag的TextView设置监听器，这些是实际的按键
            if (child is TextView && child.tag != null) {
                child.setOnClickListener(listener)
            } else if (child is ViewGroup) {
                setListenersForDevice(child, listener)
            }
        }
    }

    /**
     * 根据按键的tag值（例如 "k51"）安全地获取其显示的名称（例如 "W"）。
     * @param value 要查找的按键的tag值。
     * @return 按键的显示名称，如果找不到则返回一个安全的默认值。
     */
    fun getKeyNameByValue(value: String?): String {
        // 1. 预处理无效的输入值
        if (value.isNullOrEmpty() || value == "null") {
            return "空" // 返回一个明确的"未设置"状态
        }

        // 2. 查找视图
        val foundView = devicePage.findViewWithTag<View>(value)

        // 3. 安全地检查和转换
        if (foundView is TextView) {
            return foundView.text.toString()
        }

        // 4. 如果找不到视图，或者找到的视图不是TextView，返回原始tag值
        return value
    }

    fun close() {
        controllerManager.superPagesController.openNewPage(devicePage.lastPage)
    }
}
