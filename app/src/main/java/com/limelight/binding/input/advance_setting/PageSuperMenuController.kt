package com.limelight.binding.input.advance_setting

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat

import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.element.ElementController
import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController
import com.limelight.utils.AppSwitchStyle

class PageSuperMenuController(
    private val context: Context,
    private val controllerManager: ControllerManager
) {
    private val superPagesController: SuperPagesController = controllerManager.superPagesController!!
    private val pageNull: SuperPageLayout = superPagesController.pageNull
    private val superMenuPage: SuperPageLayout =
        LayoutInflater.from(context).inflate(R.layout.page_super_menu, null) as SuperPageLayout
    private val listLayout: LinearLayout = superMenuPage.findViewById(R.id.page_super_menu_list)

    init {
        pageNull.setPageReturnListener(object : SuperPageLayout.ReturnListener {
            override fun returnCallBack() {
                superPagesController.openNewPage(superMenuPage)
            }
        })
        superMenuPage.setPageReturnListener(object : SuperPageLayout.ReturnListener {
            override fun returnCallBack() {
                superPagesController.openNewPage(pageNull)
                pageNull.setPageReturnListener(object : SuperPageLayout.ReturnListener {
                    override fun returnCallBack() {
                        superPagesController.openNewPage(superMenuPage)
                    }
                })
            }
        })
        superMenuPage.findViewById<View>(R.id.page_super_menu_config_page).setOnClickListener {
            controllerManager.pageConfigController?.open()
        }
        superMenuPage.findViewById<View>(R.id.page_super_menu_change_profile).setOnClickListener {
            controllerManager.pageConfigController?.open()
        }
        superMenuPage.findViewById<View>(R.id.page_super_menu_edit_mode).setOnClickListener {
            controllerManager.elementController?.changeMode(ElementController.Mode.Edit)
            controllerManager.elementController?.open()
            controllerManager.superPagesController?.let { pages ->
                Handler(Looper.getMainLooper()).post {
                    if (pages.pageNow === pages.pageNull) pages.returnOperation()
                }
            }
        }
        superMenuPage.findViewById<View>(R.id.page_super_menu_disconnect).setOnClickListener {
            (context as Game).disconnect()
        }

        // 添加切换到普通模式的点击事件
        superMenuPage.findViewById<View>(R.id.page_super_menu_toggle_normal_mode).setOnClickListener {
            (context as Game).setcurrentBackKeyMenu(Game.BackKeyMenuMode.GAME_MENU)
            superPagesController.returnOperation()
            Toast.makeText(context, context.getString(R.string.toast_back_key_menu_switch_1), Toast.LENGTH_SHORT).show()
        }
        bindQuickControls()
    }

    fun open() {
        refreshSummary()
        bindQuickControls()
        superPagesController.openNewPage(pageNull)
        pageNull.setPageReturnListener(object : SuperPageLayout.ReturnListener {
            override fun returnCallBack() {
                superPagesController.openNewPage(superMenuPage)
            }
        })
        superMenuPage.setPageReturnListener(object : SuperPageLayout.ReturnListener {
            override fun returnCallBack() {
                superPagesController.openNewPage(pageNull)
                pageNull.setPageReturnListener(object : SuperPageLayout.ReturnListener {
                    override fun returnCallBack() {
                        superPagesController.openNewPage(superMenuPage)
                    }
                })
            }
        })
    }

    fun addItem(itemPageSuperMenu: ItemPageSuperMenu) {
        listLayout.addView(itemPageSuperMenu.getView(), listLayout.childCount - 1)
    }

    private fun refreshSummary() {
        val configController = controllerManager.pageConfigController ?: return
        val profileName = configController.getCurrentConfigName()
        val elementCount = configController.getCurrentElementCount()
        superMenuPage.findViewById<TextView>(R.id.page_super_menu_profile_name).text = profileName
        superMenuPage.findViewById<TextView>(R.id.page_super_menu_profile_summary).text =
            context.getString(R.string.crown_menu_profile_summary, profileName, elementCount)
        superMenuPage.findViewById<TextView>(R.id.crown_page_subtitle).apply {
            text = context.getString(R.string.crown_menu_profile_summary, profileName, elementCount)
            visibility = View.VISIBLE
        }
        val thumbnail = superMenuPage.findViewById<CrownThumbnailView>(R.id.page_super_menu_profile_thumbnail)
        thumbnail.bind(runCatching {
            val configId = configController.currentConfigId
            val payload = controllerManager.superConfigDatabaseHelper?.exportConfig(configId)
            payload?.let { CrownProfileShareManager.parsePreview(it) }
        }.getOrNull())
    }

    private fun bindQuickControls() {
        val configController = controllerManager.pageConfigController ?: return
        val touchSwitch = superMenuPage.findViewById<SwitchCompat>(R.id.page_super_menu_touch_input_switch)
        AppSwitchStyle.apply(touchSwitch)
        touchSwitch.setOnCheckedChangeListener(null)
        touchSwitch.isChecked = configController.isTouchEnabled()
        touchSwitch.setOnCheckedChangeListener { _, _ -> configController.toggleTouchEnabled() }

        val vibrationSwitch = superMenuPage.findViewById<SwitchCompat>(R.id.page_super_menu_vibration_switch)
        AppSwitchStyle.apply(vibrationSwitch)
        vibrationSwitch.setOnCheckedChangeListener(null)
        vibrationSwitch.isChecked = configController.isVibrationEnabled()
        vibrationSwitch.setOnCheckedChangeListener { _, checked -> configController.setVibrationEnabled(checked) }
    }
}
