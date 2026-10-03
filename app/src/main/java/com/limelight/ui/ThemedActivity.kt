package com.limelight.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.limelight.LimeLog
import com.limelight.utils.AppTheme
import java.util.concurrent.atomic.AtomicBoolean

private val nightOverrideFailureLogged = AtomicBoolean(false)

private fun Context.withNightOverrideSafely(): Context {
    val override = AppTheme.nightOverride(this) ?: return this
    return try {
        // Apply the configuration before Activity attaches the base context. Some
        // OEM Activity implementations read resources during attachBaseContext(),
        // which makes applyOverrideConfiguration() too late and throws.
        createConfigurationContext(override)
    } catch (error: RuntimeException) {
        if (nightOverrideFailureLogged.compareAndSet(false, true)) {
            LimeLog.warning(
                "App night configuration wrapper unavailable; using base configuration " +
                    "(${error.javaClass.simpleName})"
            )
        }
        this
    }
}

/** Applies the app palette before subclasses inflate views, on every supported API. */
open class ThemedActivity : android.app.Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withNightOverrideSafely())
    }

    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        if (baseContext != null) AppTheme.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.applyTo(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppTheme.applyTo(this)
    }
}

/** Applies the app palette before subclasses inflate views, on every supported API. */
open class ThemedComponentActivity : androidx.activity.ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withNightOverrideSafely())
    }

    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        if (baseContext != null) AppTheme.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.applyTo(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppTheme.applyTo(this)
    }
}

/** Applies the app palette before subclasses inflate views, on every supported API. */
// AppCompatDelegate already applies the stored default night mode when attaching its context.
open class ThemedAppCompatActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        if (baseContext != null) AppTheme.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.applyTo(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppTheme.applyTo(this)
    }
}

