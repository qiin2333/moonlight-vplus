package com.limelight.preferences

import android.content.Context
import android.content.res.TypedArray
import androidx.preference.CheckBoxPreference
import android.util.AttributeSet

class SmallIconCheckboxPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.checkBoxPreferenceStyle
) : CheckBoxPreference(context, attrs, defStyleAttr) {

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return PreferenceConfiguration.getDefaultSmallMode(context)
    }
}
