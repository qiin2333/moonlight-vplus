package com.limelight.binding.input.advance_setting

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import com.limelight.R
import com.limelight.Game
import com.limelight.binding.input.advance_setting.config.PageConfigController
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
    private val keyboardPickerController: KeyboardKeyPickerController
    private var deviceCallBack: DeviceCallBack? = null
    private var returnFocus: View? = null

    init {
        val onClickListener = View.OnClickListener { v ->
            // 确保回调不为空，并且点击的是TextView，避免意外的类型转换错误
            if (deviceCallBack != null && v is TextView) {
                if (v.tag == "DCS") {
                    chooseDirectConfig(v)
                } else {
                    deviceCallBack!!.OnKeyClick(v)
                    close()
                }
            }
        }
        setListenersForDevice(devicePage, onClickListener)
        keyboardPickerController = KeyboardKeyPickerController(
            root = keyboardDrawing,
            onKeySelected = { key ->
                deviceCallBack?.OnKeyClick(key)
                close()
            },
            externalViews = listOf(devicePage.findViewById(R.id.direct_config_action))
        )

        devicePage.findViewById<View>(R.id.device_cancel).setOnClickListener {
            close()
        }
    }

    fun open(deviceCallBack: DeviceCallBack, keyboardVisible: Int, mouseVisible: Int, gamepadVisible: Int) {
        returnFocus = (context as? Game)?.currentFocus
        this.deviceCallBack = deviceCallBack
        keyboardDrawing.visibility = keyboardVisible
        mouseDrawing.visibility = mouseVisible
        gamepadDrawing.visibility = gamepadVisible
        if (keyboardVisible == View.VISIBLE) {
            keyboardPickerController.showPage(
                KeyboardKeyPickerController.Page.MAIN,
                requestContentFocus = false
            )
            keyboardPickerController.requestInitialFocus()
        }
        controllerManager.superPagesController?.openNewPage(devicePage)
    }

    private fun setListenersForDevice(viewGroup: ViewGroup, listener: View.OnClickListener) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child === keyboardDrawing) continue
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
        if (value?.startsWith(DirectConfigAction.PREFIX) == true) {
            val id = DirectConfigAction.parse(value)
            val database = controllerManager.superConfigDatabaseHelper!!
            val name = id?.takeIf { it in database.queryAllConfigIds() }?.let {
                database.queryConfigAttribute(it, PageConfigController.COLUMN_STRING_CONFIG_NAME, null) as? String
            }
            return when {
                name == null -> context.getString(R.string.crown_direct_config_unavailable)
                id == controllerManager.pageConfigController!!.currentConfigId ->
                    context.getString(R.string.crown_direct_config_self)
                else -> context.getString(R.string.crown_direct_config_target, name)
            }
        }
        // 1. 预处理无效的输入值
        if (value.isNullOrEmpty() || value == "null") {
            return context.getString(R.string.empty_value) // 返回一个明确的"未设置"状态
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

    private fun chooseDirectConfig(entry: TextView) {
        val game = context as? Game ?: return
        val database = controllerManager.superConfigDatabaseHelper!!
        val configController = controllerManager.pageConfigController!!
        val sourceId = configController.currentConfigId
        val ids = configController.directSwitchTargetIds
        if (ids.isEmpty()) {
            Toast.makeText(context, R.string.crown_direct_config_no_other, Toast.LENGTH_SHORT).show()
            return
        }
        val names = ids.map {
            database.queryConfigAttribute(it, PageConfigController.COLUMN_STRING_CONFIG_NAME, "") as String
        }
        val callback = deviceCallBack ?: return
        game.showCrownConfigPicker(names, onSelected = { index ->
            if (deviceCallBack === callback && controllerManager.superPagesController?.pageNow === devicePage &&
                configController.currentConfigId == sourceId && index in ids.indices &&
                ids[index] in configController.directSwitchTargetIds) {
                val key = TextView(context).apply {
                    tag = DirectConfigAction.encode(ids[index])
                    text = getKeyNameByValue(tag.toString())
                }
                callback.OnKeyClick(key)
                close()
            }
        }, onDismiss = {
            entry.post {
                // Selection closes this page before Dialog's asynchronous dismiss callback runs.
                if (deviceCallBack === callback && controllerManager.superPagesController?.pageNow === devicePage &&
                    entry.isShown) entry.requestFocus()
            }
        })
    }

    fun close() {
        devicePage.lastPage?.let { controllerManager.superPagesController?.openNewPage(it) }
        returnFocus?.let { view -> view.post { if (view.isShown) view.requestFocus() } }
    }
}
