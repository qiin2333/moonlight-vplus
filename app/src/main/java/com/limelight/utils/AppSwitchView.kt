package com.limelight.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat

/** Allows compact tracks shorter than twice the thumb diameter. */
@SuppressLint("RestrictedApi")
class AppSwitchView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SwitchCompat(context, attrs) {
    init {
        // AppCompat's measurement path dereferences both labels even when the
        // switch is used as a compact, label-free control. Keep them non-null
        // so XML instances without textOn/textOff cannot crash during layout.
        setTextOn("")
        setTextOff("")
        setEnforceSwitchWidth(false)
    }
}
