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

class ControllerManager(layout: FrameLayout, private val context: Context) {

    private val advanceSettingView: FrameLayout = layout.findViewById(R.id.advance_setting_view)
    private val fatherLayout: FrameLayout = layout

    private var _pageConfigController: PageConfigController? = null
    private var _touchController: TouchController? = null
    private var _superPagesController: SuperPagesController? = null
    private var _pageDeviceController: PageDeviceController? = null
    private var _superConfigDatabaseHelper: SuperConfigDatabaseHelper? = null
    private var _elementController: ElementController? = null
    private var _keyboardUIController: KeyboardUIController? = null

    val pageSuperMenuController: PageSuperMenuController = PageSuperMenuController(context, this)

    val pageConfigController: PageConfigController
        get() {
            if (_pageConfigController == null) {
                _pageConfigController = PageConfigController(this, context)
            }
            return _pageConfigController!!
        }

    val touchController: TouchController
        get() {
            if (_touchController == null) {
                val layerElement: FrameLayout = advanceSettingView.findViewById(R.id.layer_2_element)
                _touchController = TouchController(context as Game, this, layerElement.findViewById(R.id.element_touch_view))
            }
            return _touchController!!
        }

    val superPagesController: SuperPagesController
        get() {
            if (_superPagesController == null) {
                val superPagesBox: FrameLayout = advanceSettingView.findViewById(R.id.super_pages_box)
                _superPagesController = SuperPagesController(superPagesBox, context)
            }
            return _superPagesController!!
        }

    val pageDeviceController: PageDeviceController
        get() {
            if (_pageDeviceController == null) {
                _pageDeviceController = PageDeviceController(context, this)
            }
            return _pageDeviceController!!
        }

    val superConfigDatabaseHelper: SuperConfigDatabaseHelper
        get() {
            if (_superConfigDatabaseHelper == null) {
                _superConfigDatabaseHelper = SuperConfigDatabaseHelper(context)
            }
            return _superConfigDatabaseHelper!!
        }

    val elementController: ElementController
        get() {
            if (_elementController == null) {
                val layerElement: FrameLayout = advanceSettingView.findViewById(R.id.layer_2_element)
                _elementController = ElementController(this, layerElement, context)
            }
            return _elementController!!
        }

    val keyboardUIController: KeyboardUIController
        get() {
            if (_keyboardUIController == null) {
                val layoutKeyboard: FrameLayout = advanceSettingView.findViewById(R.id.layer_6_keyboard)
                _keyboardUIController = KeyboardUIController(layoutKeyboard, this, context)
            }
            return _keyboardUIController!!
        }

    fun refreshLayout() {
        pageConfigController.initConfig()
    }

    /**
     * 隐藏王冠功能界面
     */
    fun hide() {
        advanceSettingView.visibility = View.GONE
    }

    /**
     * 显示王冠功能界面
     */
    fun show() {
        advanceSettingView.visibility = View.VISIBLE
    }
}
