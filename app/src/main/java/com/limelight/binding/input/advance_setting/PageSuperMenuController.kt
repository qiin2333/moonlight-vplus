package com.limelight.binding.input.advance_setting

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.element.ElementController
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController

class PageSuperMenuController(
    private val context: Context,
    private val controllerManager: ControllerManager
) {
    private val superPagesController: SuperPagesController = controllerManager.superPagesController
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
            controllerManager.pageConfigController.open()
        }
        superMenuPage.findViewById<View>(R.id.page_super_menu_edit_mode).setOnClickListener {
            controllerManager.elementController.changeMode(ElementController.Mode.Edit)
            controllerManager.elementController.open()
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
    }

    fun open() {
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
}
