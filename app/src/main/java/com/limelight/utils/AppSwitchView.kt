package com.limelight.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat

/** Allows compact tracks shorter than twice the thumb diameter. */
@SuppressLint("RestrictedApi")
class AppSwitchView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SwitchCompat(context, attrs) {
    init { setEnforceSwitchWidth(false) }
}
