package com.limelight.preferences

import android.content.Context
import android.content.DialogInterface
import android.preference.DialogPreference
import android.util.AttributeSet
import android.widget.Toast

import com.limelight.R
import com.limelight.binding.input.virtual_controller.VirtualControllerConfigurationLoader.OSC_PREFERENCE

class ConfirmDeleteOscPreference : DialogPreference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) :
        super(context, attrs)

    constructor(context: Context) :
        super(context)

    override fun onClick(dialog: DialogInterface, which: Int) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            context.getSharedPreferences(OSC_PREFERENCE, Context.MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(context, R.string.toast_reset_osc_success, Toast.LENGTH_SHORT).show()
        }
    }
}
