package com.limelight.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.util.Log
import com.limelight.PcView

/** Stable launcher identity and upgrade repair for the retired icon aliases. */
object LauncherEntry {
    private const val TAG = "LauncherEntry"

    fun component(context: Context): ComponentName = ComponentName(context, PcView::class.java)

    fun migrateAsync(context: Context, onFinished: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        Thread({
            try {
                migrate(appContext)
            } finally {
                onFinished?.invoke()
            }
        }, "launcher-migration").start()
    }

    // Serialize activity-start and package-replaced retries. PackageManager and
    // ShortcutManager calls can take seconds, so callers use the background entry point.
    @Synchronized
    internal fun migrate(context: Context) {
        // Class names use the manifest namespace, not the root/debug applicationId.
        val classPrefix = PcView::class.java.name.removeSuffix(".PcView")
        val legacyAliases = listOf(".LauncherSaba", ".LauncherSabaDark").map {
            ComponentName(context.packageName, classPrefix + it)
        }
        val pm = context.packageManager
        for (alias in legacyAliases) {
            try {
                if (pm.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    // Both aliases now default to enabled without any launcher filter.
                    // This preserves old explicit intents without publishing extra icons.
                    pm.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to restore legacy launcher alias $alias", e)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return

        try {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val launcher = component(context)
            val shortcuts = (manager.dynamicShortcuts + manager.pinnedShortcuts)
                .distinctBy { it.id }
                .filter { !it.isImmutable && (it.activity in legacyAliases || it.activity == launcher) }
            val updates = shortcuts.filter { it.activity != launcher }.map {
                // Preserve the shortcut's actual intent, label, and icon.
                ShortcutInfo.Builder(context, it.id).setActivity(launcher).build()
            }
            if (updates.isNotEmpty() && !manager.updateShortcuts(updates)) {
                // Rate limited: do not enable shortcuts still bound to the old entry.
                // A later foreground launch will retry the migration.
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Removing the alias's MAIN filter can disable retained pinned records.
                // updateShortcuts() does not re-enable them. Never revive BY_APP records
                // disabled because the computer/game was deleted or is no longer paired.
                val restoreIds = shortcuts.filter {
                    it.disabledReason == ShortcutInfo.DISABLED_REASON_APP_CHANGED
                }.map { it.id }
                if (restoreIds.isNotEmpty()) manager.enableShortcuts(restoreIds)
            }
            // API 25-27 cannot report why a shortcut was disabled. Preserve that state;
            // ShortcutHelper republishes/enables known computers on their next update.
            // Records already deleted by the system or launcher cannot be migrated here.
        } catch (e: RuntimeException) {
            // Locked users, stale launcher records, and OEM failures must not prevent
            // the app from starting. The fixed PcView entry does not depend on repair.
            Log.w(TAG, "Unable to migrate launcher shortcuts", e)
        }
    }
}
