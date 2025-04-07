package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

public class ItemPageSuperMenu {
    private LinearLayout item;

    public ItemPageSuperMenu(String text, View.OnClickListener onClickListener,Context context){
        item = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.item_page_super_menu,null);
        ((TextView)item.findViewById(R.id.item_page_super_menu_text)).setText(text);
        item.setOnClickListener(onClickListener);
    }

    public View getView(){
        return item;
    }

}
