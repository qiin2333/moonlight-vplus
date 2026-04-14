@file:Suppress("DEPRECATION")
package com.limelight.utils

import android.app.Activity
import android.app.AlertDialog
import android.app.GameManager
import android.app.GameState
import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.limelight.Game
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration
import java.util.Locale

object UiHelper {

    private const val TV_VERTICAL_PADDING_DP = 15
    private const val TV_HORIZONTAL_PADDING_DP = 15

    private var sGameManagerAvailable: Boolean? = null

    private fun isGameManagerAvailable(context: Context): Boolean {
        sGameManagerAvailable?.let { return it }
        return try {
            val gameManager = context.getSystemService(GameManager::class.java)
            (gameManager != null).also { sGameManagerAvailable = it }
        } catch (_: Exception) {
            false.also { sGameManagerAvailable = false }
        }
    }

    private fun setGameModeStatus(context: Context, streaming: Boolean, interruptible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isGameManagerAvailable(context)) return

            try {
                val gameManager = context.getSystemService(GameManager::class.java)
                if (streaming) {
                    gameManager.setGameState(
                        GameState(
                            false,
                            if (interruptible) GameState.MODE_GAMEPLAY_INTERRUPTIBLE
                            else GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE
                        )
                    )
                } else {
                    gameManager.setGameState(GameState(false, GameState.MODE_NONE))
                }
            } catch (_: Exception) {
                sGameManagerAvailable = false
            }
        }
    }

    fun notifyStreamConnecting(context: Context) = setGameModeStatus(context, true, true)

    fun notifyStreamConnected(context: Context) = setGameModeStatus(context, true, false)

    fun notifyStreamEnteringPiP(context: Context) = setGameModeStatus(context, true, true)

    fun notifyStreamExitingPiP(context: Context) = setGameModeStatus(context, true, false)

    fun notifyStreamEnded(context: Context) = setGameModeStatus(context, false, false)

    fun setLocale(activity: Activity) {
        val locale = PreferenceConfiguration.readPreferences(activity).language
        if (locale != PreferenceConfiguration.DEFAULT_LANGUAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = activity.getSystemService(LocaleManager::class.java)
                localeManager.applicationLocales = LocaleList.forLanguageTags(locale)
                PreferenceConfiguration.completeLanguagePreferenceMigration(activity)
            } else {
                val config = Configuration(activity.resources.configuration)
                config.locale = if (locale.contains("-")) {
                    Locale(locale.substring(0, locale.indexOf('-')), locale.substring(locale.indexOf('-') + 1))
                } else {
                    Locale(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            }
        }
    }

    fun applyStatusBarPadding(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.setOnApplyWindowInsetsListener { v, windowInsets ->
                v.setPadding(
                    v.paddingLeft,
                    v.paddingTop,
                    v.paddingRight,
                    windowInsets.tappableElementInsets.bottom
                )
                windowInsets
            }
            view.requestApplyInsets()
        }
    }

    fun notifyNewRootView(activity: Activity) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val modeMgr = activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

        setGameModeStatus(activity, false, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (modeMgr.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val scale = activity.resources.displayMetrics.density
                val verticalPaddingPixels = (TV_VERTICAL_PADDING_DP * scale + 0.5f).toInt()
                val horizontalPaddingPixels = (TV_HORIZONTAL_PADDING_DP * scale + 0.5f).toInt()
                rootView.setPadding(
                    horizontalPaddingPixels, verticalPaddingPixels,
                    horizontalPaddingPixels, verticalPaddingPixels
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { _, windowInsets ->
                val tappableInsets = windowInsets.tappableElementInsets
                if (tappableInsets.bottom != 0) {
                    @Suppress("DEPRECATION")
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                }
                windowInsets
            }

            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    fun showDecoderCrashDialog(activity: Activity) {
        val prefs = activity.getSharedPreferences("DecoderTombstone", 0)
        val crashCount = prefs.getInt("CrashCount", 0)
        val lastNotifiedCrashCount = prefs.getInt("LastNotifiedCrashCount", 0)

        if (crashCount != 0 && crashCount != lastNotifiedCrashCount) {
            if (crashCount % 3 == 0) {
                PreferenceConfiguration.resetStreamingSettings(activity)
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_reset),
                    activity.resources.getString(R.string.message_decoding_reset)
                ) {
                    prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
                }
            } else {
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_error),
                    activity.resources.getString(R.string.message_decoding_error)
                ) {
                    prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
                }
            }
        }
    }

    fun displayQuitConfirmationDialog(parent: Activity, onYes: Runnable?, onNo: Runnable?) {
        AlertDialog.Builder(parent, R.style.AppDialogStyle)
            .setMessage(parent.resources.getString(R.string.applist_quit_confirmation))
            .setPositiveButton(parent.resources.getString(R.string.yes)) { _, _ -> onYes?.run() }
            .setNegativeButton(parent.resources.getString(R.string.no)) { _, _ -> onNo?.run() }
            .show()
    }

    fun displayDeletePcConfirmationDialog(
        parent: Activity, computer: ComputerDetails,
        onYes: Runnable?, onNo: Runnable?
    ) {
        AlertDialog.Builder(parent, R.style.AppDialogStyle)
            .setMessage(parent.resources.getString(R.string.delete_pc_msg))
            .setTitle(computer.name)
            .setPositiveButton(parent.resources.getString(R.string.yes)) { _, _ -> onYes?.run() }
            .setNegativeButton(parent.resources.getString(R.string.no)) { _, _ -> onNo?.run() }
            .show()
    }

    fun getBatteryLevel(context: Context): Int {
        try {
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } else {
                val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    (batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100) /
                            batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                } else 0
            }

            if (level < 0 || level > 100) {
                LimeLog.warning("Invalid battery level: $level")
                return 0
            }
            return level
        } catch (e: Exception) {
            LimeLog.warning("Error getting battery level: ${e.message}")
            return 0
        }
    }

    fun isCharging(context: Context): Boolean {
        return try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryStatus != null) {
                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            } else false
        } catch (e: Exception) {
            LimeLog.warning("Error checking charging status: ${e.message}")
            false
        }
    }

    fun isColorOS(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        val brand = Build.BRAND.lowercase()

        val colorOSBrands = arrayOf("oppo", "oneplus", "realme")
        return colorOSBrands.any { b ->
            manufacturer.contains(b) || model.contains(b) || brand.contains(b)
        }
    }

    fun getDeviceRefreshRate(activity: Activity): Float {
        return try {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        } catch (e: Exception) {
            LimeLog.warning("Failed to get device refresh rate: ${e.message}")
            60.0f
        }
    }
}
