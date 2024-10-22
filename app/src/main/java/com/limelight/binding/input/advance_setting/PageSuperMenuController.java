package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.GameMenu;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

public class PageSuperMenuController {

    private Context context;
    private ControllerManager controllerManager;
    private SuperPageLayout superMenuPage;
    private LinearLayout listLayout;
    private SuperPageLayout nextOpenPage;

    public PageSuperMenuController(Context context, ControllerManager controllerManager) {
        this.context = context;
        this.controllerManager = controllerManager;
        superMenuPage = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_super_menu,null);
        listLayout = superMenuPage.findViewById(R.id.page_super_menu_list);
        nextOpenPage = superMenuPage;

        superMenuPage.findViewById(R.id.page_super_menu_config_page).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controllerManager.getSuperPagesController().close();
                controllerManager.getPageConfigController().open();
            }
        });

        superMenuPage.findViewById(R.id.page_super_menu_edit_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controllerManager.getSuperPagesController().close();
                controllerManager.getElementController().entryEditMode();
                nextOpenPage = controllerManager.getElementController().getPageEdit();
                open();
            }
        });
        superMenuPage.findViewById(R.id.page_super_menu_disconnect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((Game)context).disconnect();
            }
        });

        superMenuPage.findViewById(R.id.page_super_menu_keyboard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controllerManager.getKeyboardUIController().toggle();
                controllerManager.getSuperPagesController().close();
            }
        });

    }

    public void exitElementEditMode(){
        controllerManager.getSuperPagesController().close();
        nextOpenPage = superMenuPage;
    }

    public void open(){
        if (controllerManager.getSuperPagesController().getLastPage() != null){
            controllerManager.getSuperPagesController().close();
            return;
        }

        controllerManager.getSuperPagesController().open(nextOpenPage);
    }

    public void addItem(ItemPageSuperMenu itemPageSuperMenu){
        listLayout.addView(itemPageSuperMenu.getView(),listLayout.getChildCount() - 1);
    }






}
