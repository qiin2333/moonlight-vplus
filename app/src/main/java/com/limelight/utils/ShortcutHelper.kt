package com.limelight.utils

import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.graphics.drawable.Icon
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import java.util.Collections
import java.util.LinkedList

class ShortcutHelper(private val context: Activity) {

    private val sm: ShortcutManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        context.getSystemService(ShortcutManager::class.java)
    } else {
        null
    }
    private val tvChannelHelper = TvChannelHelper(context)

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun reapShortcutsForDynamicAdd() {
        val dynamicShortcuts = sm!!.dynamicShortcuts.toMutableList()
        while (dynamicShortcuts.isNotEmpty() && dynamicShortcuts.size >= sm.maxShortcutCountPerActivity) {
            var maxRankShortcut = dynamicShortcuts[0]
            for (scut in dynamicShortcuts) {
                if (maxRankShortcut.rank < scut.rank) {
                    maxRankShortcut = scut
                }
            }
            sm.removeDynamicShortcuts(Collections.singletonList(maxRankShortcut.id))
        }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun getAllShortcuts(): List<ShortcutInfo> {
        val list = LinkedList<ShortcutInfo>()
        list.addAll(sm!!.dynamicShortcuts)
        list.addAll(sm.pinnedShortcuts)
        return list
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun getInfoForId(id: String): ShortcutInfo? {
        return getAllShortcuts().firstOrNull { it.id == id }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun isExistingDynamicShortcut(id: String): Boolean {
        return sm!!.dynamicShortcuts.any { it.id == id }
    }

    fun reportComputerShortcutUsed(computer: ComputerDetails) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            if (getInfoForId(computer.uuid!!) != null) {
                sm!!.reportShortcutUsed(computer.uuid!!)
            }
        }
    }

    fun reportGameLaunched(computer: ComputerDetails, app: NvApp) {
        tvChannelHelper.createTvChannel(computer)
        tvChannelHelper.addGameToChannel(computer, app)
    }

    fun createAppViewShortcut(computer: ComputerDetails, forceAdd: Boolean, newlyPaired: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val sinfo = ShortcutInfo.Builder(context, computer.uuid!!)
                .setIntent(ServerHelper.createPcShortcutIntent(context, computer))
                .setShortLabel(computer.name!!)
                .setLongLabel(computer.name!!)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_pc_scut))
                .build()

            val existingSinfo = getInfoForId(computer.uuid!!)
            if (existingSinfo != null) {
                sm!!.updateShortcuts(Collections.singletonList(sinfo))
                sm.enableShortcuts(Collections.singletonList(computer.uuid!!))
            }

            if (!isExistingDynamicShortcut(computer.uuid!!)) {
                if (forceAdd) {
                    reapShortcutsForDynamicAdd()
                }

                if (sm!!.dynamicShortcuts.size < sm.maxShortcutCountPerActivity) {
                    sm.addDynamicShortcuts(Collections.singletonList(sinfo))
                }
            }
        }

        if (newlyPaired) {
            tvChannelHelper.createTvChannel(computer)
            tvChannelHelper.requestChannelOnHomeScreen(computer)
        }
    }

    fun createAppViewShortcutForOnlineHost(details: ComputerDetails) {
        createAppViewShortcut(details, forceAdd = false, newlyPaired = false)
    }

    private fun getShortcutIdForGame(computer: ComputerDetails, app: NvApp): String {
        return computer.uuid!! + app.appId
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun createPinnedGameShortcut(computer: ComputerDetails, app: NvApp, iconBits: Bitmap?): Boolean {
        if (sm!!.isRequestPinShortcutSupported) {
            val appIcon = if (iconBits != null) {
                val adaptiveSquare = prepareAdaptiveSquareBitmap(iconBits)
                Icon.createWithAdaptiveBitmap(adaptiveSquare)
            } else {
                Icon.createWithResource(context, R.mipmap.ic_pc_scut)
            }

            val sInfo = ShortcutInfo.Builder(context, getShortcutIdForGame(computer, app))
                .setIntent(ServerHelper.createAppShortcutIntent(context, computer, app))
                .setShortLabel(app.appName + " (" + computer.name!! + ")")
                .setIcon(appIcon)
                .build()

            return sm.requestPinShortcut(sInfo, null)
        }
        return false
    }

    fun disableComputerShortcut(computer: ComputerDetails, reason: CharSequence) {
        tvChannelHelper.deleteChannel(computer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            if (getInfoForId(computer.uuid!!) != null) {
                sm!!.disableShortcuts(Collections.singletonList(computer.uuid!!), reason)
            }

            val shortcuts = getAllShortcuts()
            val appShortcutIds = LinkedList<String>()
            for (info in shortcuts) {
                if (info.id.startsWith(computer.uuid!!)) {
                    appShortcutIds.add(info.id)
                }
            }
            sm!!.disableShortcuts(appShortcutIds, reason)
        }
    }

    fun disableAppShortcut(computer: ComputerDetails, app: NvApp, reason: CharSequence) {
        tvChannelHelper.deleteProgram(computer, app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val id = getShortcutIdForGame(computer, app)
            if (getInfoForId(id) != null) {
                sm!!.disableShortcuts(Collections.singletonList(id), reason)
            }
        }
    }

    fun enableAppShortcut(computer: ComputerDetails, app: NvApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val id = getShortcutIdForGame(computer, app)
            if (getInfoForId(id) != null) {
                sm!!.enableShortcuts(Collections.singletonList(id))
            }
        }
    }

    companion object {
        private fun prepareAdaptiveSquareBitmap(source: Bitmap?): Bitmap? {
            if (source == null) return null

            var src = source
            var srcWidth = src.width
            var srcHeight = src.height
            if (srcWidth <= 0 || srcHeight <= 0) return src

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
                val softwareCopy = src.copy(Bitmap.Config.ARGB_8888, false)
                if (softwareCopy != null) {
                    src = softwareCopy
                    srcWidth = src.width
                    srcHeight = src.height
                } else {
                    return src
                }
            }

            val side = maxOf(srcWidth, srcHeight)
            val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val scale = maxOf(side.toFloat() / srcWidth, side.toFloat() / srcHeight)

            val scaledWidth = Math.round(srcWidth * scale)
            val scaledHeight = Math.round(srcHeight * scale)

            val srcLeft = (scaledWidth - side) / 2
            val srcTop = (scaledHeight - side) / 2

            var actualSrcLeft = Math.round(srcLeft / scale)
            var actualSrcTop = Math.round(srcTop / scale)
            var actualSrcRight = Math.round((srcLeft + side) / scale)
            var actualSrcBottom = Math.round((srcTop + side) / scale)

            actualSrcLeft = maxOf(0, actualSrcLeft)
            actualSrcTop = maxOf(0, actualSrcTop)
            actualSrcRight = minOf(srcWidth, actualSrcRight)
            actualSrcBottom = minOf(srcHeight, actualSrcBottom)

            val srcRect = Rect(actualSrcLeft, actualSrcTop, actualSrcRight, actualSrcBottom)
            val dstRect = Rect(0, 0, side, side)
            canvas.drawBitmap(src, srcRect, dstRect, null)

            return output
        }
    }
}
