package com.limelight.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.limelight.utils.AppTheme

/** Applies the app palette before subclasses inflate views, on every supported API. */
open class ThemedActivity : android.app.Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppTheme.nightOverride(newBase)?.let { applyOverrideConfiguration(it) }
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
        super.attachBaseContext(newBase)
        AppTheme.nightOverride(newBase)?.let { applyOverrideConfiguration(it) }
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

