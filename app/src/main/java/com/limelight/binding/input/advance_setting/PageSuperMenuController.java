package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.element.ElementController;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController;

public class PageSuperMenuController {

    private Context context;
    private ControllerManager controllerManager;
    private SuperPagesController superPagesController;
    private SuperPageLayout pageNull;
    private SuperPageLayout superMenuPage;
    private LinearLayout listLayout;

    public PageSuperMenuController(Context context, ControllerManager controllerManager) {
        this.context = context;
        this.controllerManager = controllerManager;
        superMenuPage = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_super_menu,null);
        listLayout = superMenuPage.findViewById(R.id.page_super_menu_list);
        superPagesController = controllerManager.getSuperPagesController();
        pageNull = superPagesController.getPageNull();
        pageNull.setPageReturnListener(new SuperPageLayout.ReturnListener() {
            @Override
            public void returnCallBack() {
                superPagesController.openNewPage(superMenuPage);
            }
        });
        superMenuPage.setPageReturnListener(new SuperPageLayout.ReturnListener() {
            @Override
            public void returnCallBack() {
                superPagesController.openNewPage(pageNull);
                pageNull.setPageReturnListener(new SuperPageLayout.ReturnListener() {
                    @Override
                    public void returnCallBack() {
                        superPagesController.openNewPage(superMenuPage);
                    }
                });
            }
        });
        superMenuPage.findViewById(R.id.page_super_menu_config_page).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controllerManager.getPageConfigController().open();
            }
        });

        superMenuPage.findViewById(R.id.page_super_menu_edit_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controllerManager.getElementController().changeMode(ElementController.Mode.Edit);
                controllerManager.getElementController().open();
            }
        });
        superMenuPage.findViewById(R.id.page_super_menu_disconnect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((Game)context).disconnect();
            }
        });

    }

    public void open(){
        superPagesController.openNewPage(pageNull);
        pageNull.setPageReturnListener(new SuperPageLayout.ReturnListener() {
            @Override
            public void returnCallBack() {
                superPagesController.openNewPage(superMenuPage);
            }
        });
        superMenuPage.setPageReturnListener(new SuperPageLayout.ReturnListener() {
            @Override
            public void returnCallBack() {
                superPagesController.openNewPage(pageNull);
                pageNull.setPageReturnListener(new SuperPageLayout.ReturnListener() {
                    @Override
                    public void returnCallBack() {
                        superPagesController.openNewPage(superMenuPage);
                    }
                });
            }
        });
    }

    public void addItem(ItemPageSuperMenu itemPageSuperMenu){
        listLayout.addView(itemPageSuperMenu.getView(),listLayout.getChildCount() - 1);
    }


}
