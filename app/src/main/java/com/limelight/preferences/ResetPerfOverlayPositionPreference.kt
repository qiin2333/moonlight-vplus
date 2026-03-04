package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import android.preference.Preference
import android.util.AttributeSet
import android.widget.Toast

class ResetPerfOverlayPositionPreference : Preference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) :
        super(context, attrs)

    constructor(context: Context) :
        super(context)

    override fun onClick() {
        super.onClick()

        // 清除自定义位置设置
        val prefs = context.getSharedPreferences("performance_overlay", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        Toast.makeText(context, "性能统计位置已重置", Toast.LENGTH_SHORT).show()
    }
}
