package com.limelight.binding.input.advance_setting

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

import com.limelight.R

class ItemPageSuperMenu(text: String, onClickListener: View.OnClickListener, context: Context) {
    private val item: LinearLayout =
        LayoutInflater.from(context).inflate(R.layout.item_page_super_menu, null) as LinearLayout

    init {
        item.findViewById<TextView>(R.id.item_page_super_menu_text).text = text
        item.setOnClickListener(onClickListener)
    }

    fun getView(): View = item
}
