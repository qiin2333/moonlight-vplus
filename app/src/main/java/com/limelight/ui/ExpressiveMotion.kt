package com.limelight.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Shared, event-driven motion policy for Views and Compose controls. */
internal object ExpressiveMotion {
    const val STIFFNESS = 800f
    const val RELEASE_DAMPING = 0.65f
    const val PRESS_DAMPING = 1f

    fun enabled(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    fun spring(initialValue: Float, onUpdate: (Float) -> Unit): SpringAnimation =
        SpringAnimation(FloatValueHolder(initialValue)).apply {
            spring = SpringForce(initialValue).apply {
                stiffness = STIFFNESS
                dampingRatio = RELEASE_DAMPING
            }
            minimumVisibleChange = 0.001f
            addUpdateListener { _, value, _ -> onUpdate(value) }
        }
}
