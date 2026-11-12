package com.limelight.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.limelight.PcView

/**
 * 桌面图标变体在 activity-alias 上的启停。manifest 中 LAUNCHER intent-filter 挂在
 * .LauncherSaba / .LauncherSabaDark 两个 alias 上，本类保证任意时刻恰好一开一关；
 * 切换入口在 PcView 工具栏"主题"面板的"应用图标"组。
 */
object LauncherIcon {
    const val VARIANT_SABA = "saba"
    const val VARIANT_SABA_DARK = "saba_dark"

    /**
     * alias 的类名按 manifest namespace（com.limelight）展开，与 applicationId 无关；
     * root flavor 的 applicationId（com.limelight.root）只是包名前缀，两者不能混用。
     */
    private val aliasClassPrefix = PcView::class.java.name.removeSuffix(".PcView")

    private fun aliasComponent(context: Context, variant: String): ComponentName {
        val alias = if (variant == VARIANT_SABA_DARK) ".LauncherSabaDark" else ".LauncherSaba"
        return ComponentName(context.packageName, aliasClassPrefix + alias)
    }

    /** 当前生效的图标变体；未设置过（DEFAULT 状态）一律视为标准版。 */
    fun currentVariant(context: Context): String {
        val darkState = context.packageManager
            .getComponentEnabledSetting(aliasComponent(context, VARIANT_SABA_DARK))
        return if (darkState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            VARIANT_SABA_DARK
        } else {
            VARIANT_SABA
        }
    }

    /**
     * 切换桌面图标：先启后停，保证任意瞬间至少有一个 LAUNCHER 入口可解析；
     * DONT_KILL_APP 让桌面收到 PACKAGE_CHANGED 后自行刷新，进程保持存活。
     */
    fun applyVariant(context: Context, variant: String) {
        if (variant == currentVariant(context)) return
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        pm.setComponentEnabledSetting(
            aliasComponent(appContext, variant),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            aliasComponent(appContext, if (variant == VARIANT_SABA_DARK) VARIANT_SABA else VARIANT_SABA_DARK),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
