package com.limelight.binding.input.advance_setting

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.element.ElementController
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController

class ControllerManager(layout: FrameLayout, context: Context) {
    private val advanceSettingView: FrameLayout?
    private val fatherLayout: FrameLayout?
    var pageConfigController: PageConfigController? = null
        get() {
            if (field == null) {
                field = PageConfigController(this, context)
            }
            return field
        }
        private set
    var touchController: TouchController? = null
        get() {
            if (field == null) {
                val layerElement =
                    advanceSettingView!!.findViewById<FrameLayout>(R.id.layer_2_element)
                field = TouchController(
                    (context as Game?)!!,
                    this,
                    layerElement.findViewById<View?>(R.id.element_touch_view)
                )
            }
            return field
        }
        private set
    var superPagesController: SuperPagesController? = null
        get() {
            if (field == null) {
                val superPagesBox =
                    advanceSettingView!!.findViewById<FrameLayout?>(R.id.super_pages_box)
                field = SuperPagesController(superPagesBox, context)
            }
            return field
        }
        private set
    var pageDeviceController: PageDeviceController? = null
        get() {
            if (field == null) {
                field = PageDeviceController(context, this)
            }
            return field
        }
        private set
    var superConfigDatabaseHelper: SuperConfigDatabaseHelper? = null
        get() {
            if (field == null) {
                field = SuperConfigDatabaseHelper(context)
            }
            return field
        }
        private set
    var elementController: ElementController? = null
        get() {
            if (field == null) {
                val layerElement =
                    advanceSettingView!!.findViewById<FrameLayout?>(R.id.layer_2_element)
                field = ElementController(this, layerElement, context)
            }
            return field
        }
        private set
    @JvmField
    val pageSuperMenuController: PageSuperMenuController?
    var keyboardUIController: KeyboardUIController? = null
        get() {
            if (field == null) {
                val layoutKeyboard =
                    advanceSettingView!!.findViewById<FrameLayout?>(R.id.layer_6_keyboard)
                if (layoutKeyboard != null) {
                    field = KeyboardUIController(advanceSettingView, this, context)
                }
            }
            return field
        }
    private val context: Context

    init {
        advanceSettingView = layout.findViewById<FrameLayout?>(R.id.advance_setting_view)
        this.fatherLayout = layout
        this.context = context
        pageSuperMenuController = PageSuperMenuController(context, this)
    }


    fun refreshLayout() {
        this.pageConfigController!!.initConfig()
    }

    /**
     * 隐藏王冠功能界面
     */
    fun hide() {
        if (advanceSettingView != null) {
            advanceSettingView.setVisibility(View.GONE)
        }
    }

    /**
     * 显示王冠功能界面
     */
    fun show() {
        if (advanceSettingView != null) {
            advanceSettingView.setVisibility(View.VISIBLE)
        }
    }
}
