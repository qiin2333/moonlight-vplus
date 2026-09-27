package com.limelight.utils

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.limelight.LimeLog
import com.limelight.R
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent theme choices and their application; independent of Activity lifecycle variants. */
object AppTheme {
    private const val APP_THEME_PREFS = "AppTheme"
    private const val APP_THEME_MODE_KEY = "theme_mode"
    private val paletteFailureLogged = AtomicBoolean(false)
    private val nightModeFailureLogged = AtomicBoolean(false)

    const val THEME_MODE_SYSTEM = "system"
    const val THEME_MODE_LIGHT = "light"
    const val THEME_MODE_DARK = "dark"

    fun applyStoredAppTheme(context: Context) {
        try {
            applyAppThemeMode(context, getAppThemeMode(context))
        } catch (error: IllegalArgumentException) {
            logNightModeFallback(error)
        } catch (error: IllegalStateException) {
            logNightModeFallback(error)
        }
    }

    fun getAppThemeMode(context: Context): String {
        val storedMode = context.getSharedPreferences(APP_THEME_PREFS, Context.MODE_PRIVATE)
            .getString(APP_THEME_MODE_KEY, THEME_MODE_SYSTEM)
            ?: THEME_MODE_SYSTEM
        return normalizeThemeMode(storedMode)
    }

    /** Commit the two independent choices together before triggering a configuration change. */
    fun save(context: Context, mode: String, accent: String) {
        val normalizedMode = normalizeThemeMode(mode)
        context.getSharedPreferences(APP_THEME_PREFS, Context.MODE_PRIVATE).edit {
            putString(APP_THEME_MODE_KEY, normalizedMode)
            putString(ACCENT_MODE_KEY, if (accent == ACCENT_MODE_BG) ACCENT_MODE_BG else ACCENT_MODE_PINK)
        }
        applyAppThemeMode(context, normalizedMode)
    }

    private fun normalizeThemeMode(mode: String): String {
        return when (mode) {
            THEME_MODE_LIGHT, THEME_MODE_DARK -> mode
            else -> THEME_MODE_SYSTEM
        }
    }

    private fun applyAppThemeMode(context: Context, mode: String) {
        val appCompatMode = when (mode) {
            THEME_MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(appCompatMode)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val platformMode = when (mode) {
                THEME_MODE_LIGHT -> UiModeManager.MODE_NIGHT_NO
                THEME_MODE_DARK -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
            context.getSystemService(UiModeManager::class.java)
                ?.setApplicationNightMode(platformMode)
        }
    }

    // ---------- 强调色模式 ----------

    private const val ACCENT_MODE_KEY = "accent_mode"

    /**
     * 主题偏好推导出的"期望日夜"（同步、即时）：
     * dark → 夜；light → 昼；system → 跟随当前配置。
     * 装饰层用它而不是读系统配置——ROM 的 per-app 夜间切换是异步的，
     * 读配置会慢一拍（Flyme 上尤其明显）。
     */
    fun wantedNight(context: Context): Boolean = when (getAppThemeMode(context)) {
        THEME_MODE_DARK -> true
        THEME_MODE_LIGHT -> false
        else -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
    const val ACCENT_MODE_PINK = "pink"
    const val ACCENT_MODE_BG = "bg"

    /** 首页背景取色的 12 个色相桶对应的 overlay（由 BgAccent 选中其一）。 */
    private val BG_OVERLAY_STYLES = intArrayOf(
        R.style.YouAccentOverlayBg0, R.style.YouAccentOverlayBg1,
        R.style.YouAccentOverlayBg2, R.style.YouAccentOverlayBg3,
        R.style.YouAccentOverlayBg4, R.style.YouAccentOverlayBg5,
        R.style.YouAccentOverlayBg6, R.style.YouAccentOverlayBg7,
        R.style.YouAccentOverlayBg8, R.style.YouAccentOverlayBg9,
        R.style.YouAccentOverlayBg10, R.style.YouAccentOverlayBg11,
    )

    /** 当前强调色模式：品牌粉 / 跟随首页背景。 */
    fun getAccentMode(context: Context): String =
        context.getSharedPreferences(APP_THEME_PREFS, Context.MODE_PRIVATE)
            .getString(ACCENT_MODE_KEY, ACCENT_MODE_PINK)
            .let { if (it == ACCENT_MODE_BG) ACCENT_MODE_BG else ACCENT_MODE_PINK }

    /** Refresh live views on a bucket change, including changes made while the page was stopped. */
    fun observeAccent(context: Context, owner: LifecycleOwner, onChanged: () -> Unit) {
        val prefs = context.getSharedPreferences(APP_THEME_PREFS, Context.MODE_PRIVATE)
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver, SharedPreferences.OnSharedPreferenceChangeListener {
            private var lastBucket = activeBucket(context)

            private fun refresh() {
                val bucket = activeBucket(context)
                if (bucket == lastBucket) return
                lastBucket = bucket
                applyTo(context)
                onChanged()
            }

            override fun onStart(owner: LifecycleOwner) {
                prefs.registerOnSharedPreferenceChangeListener(this)
                refresh()
            }

            override fun onStop(owner: LifecycleOwner) {
                prefs.unregisterOnSharedPreferenceChangeListener(this)
            }

            override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) = refresh()
        })
    }

    fun activeBucket(context: Context): Int =
        if (getAccentMode(context) == ACCENT_MODE_BG) BgAccent.bucket(context) else BgAccent.NO_BUCKET

    /** All Activity and drawable contexts use the same preference-resolved night configuration. */
    fun configurationContext(context: Context): Context {
        val configuration = context.resources.configuration
        val night = if (wantedNight(context)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        if (getAppThemeMode(context) == THEME_MODE_SYSTEM) return context
        // This is a short-lived palette context, so preserve the caller's window/density overrides.
        val override = Configuration(configuration)
        override.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(override)
    }

    /** Activity overrides must contain ONLY night mode so rotation and window resizing stay live. */
    fun nightOverride(context: Context): Configuration? = when (getAppThemeMode(context)) {
        THEME_MODE_LIGHT -> Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
        THEME_MODE_DARK -> Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        else -> null
    }

    /** Optional accent overlays must never prevent an Activity from using its base theme. */
    fun applyTo(context: Context): Boolean =
        applyPaletteSafely(context.theme, activeBucket(context))

    private fun applyPaletteSafely(theme: Resources.Theme, bucket: Int): Boolean {
        return try {
            applyPalette(theme, bucket)
            true
        } catch (error: Resources.NotFoundException) {
            logPaletteFallback(error)
            false
        } catch (error: IllegalArgumentException) {
            logPaletteFallback(error)
            false
        }
    }

    private fun logPaletteFallback(error: RuntimeException) {
        if (paletteFailureLogged.compareAndSet(false, true)) {
            LimeLog.warning(
                "App theme accent overlay unavailable; using base theme " +
                    "(${error.javaClass.simpleName})"
            )
        }
    }

    private fun logNightModeFallback(error: RuntimeException) {
        if (nightModeFailureLogged.compareAndSet(false, true)) {
            LimeLog.warning(
                "App night mode unavailable; using the manifest theme " +
                    "(${error.javaClass.simpleName})"
            )
        }
    }

    private fun applyPalette(theme: android.content.res.Resources.Theme, bucket: Int) {
        // Reset first: removing a background or disabling its accent must not retain the old overlay.
        theme.applyStyle(R.style.AppAccentBrand, true)
        if (bucket in BG_OVERLAY_STYLES.indices) theme.applyStyle(BG_OVERLAY_STYLES[bucket], true)
    }

    fun applyStyle(context: Context, style: Int) {
        context.theme.applyStyle(style, true)
        applyTo(context)
    }

    fun paletteContext(context: Context, bucket: Int = activeBucket(context)): Context =
        android.view.ContextThemeWrapper(configurationContext(context), 0).also {
            it.theme.setTo(context.theme)
            applyPaletteSafely(it.theme, bucket)
        }
}
