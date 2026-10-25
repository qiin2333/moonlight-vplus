package com.limelight.grid

import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.limelight.AppView
import com.limelight.R
import com.limelight.grid.assets.CachedAppAssetLoader
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.grid.assets.MemoryAssetLoader
import com.limelight.grid.assets.NetworkAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.AppIconCache

class AppGridAdapter(
    context: Context,
    prefs: PreferenceConfiguration,
    private val computer: ComputerDetails,
    private val uniqueId: String,
    private val showHiddenApps: Boolean
) : GenericGridAdapter<AppView.AppObject>(context, getLayoutIdForPreferences(prefs)) {

    private var loader: CachedAppAssetLoader? = null
    private var hiddenAppIds: MutableSet<Int> = HashSet()
    private val allApps = ArrayList<AppView.AppObject>()

    init {
        updateLayoutWithPreferences(context, prefs)
    }

    fun updateHiddenApps(newHiddenAppIds: Set<Int>, hideImmediately: Boolean) {
        hiddenAppIds.clear()
        hiddenAppIds.addAll(newHiddenAppIds)

        if (hideImmediately) {
            itemList.clear()
            for (app in allApps) {
                app.isHidden = app.app.appId in hiddenAppIds
                if (!app.isHidden || showHiddenApps) {
                    itemList.add(app)
                }
            }
        } else {
            for (app in allApps) {
                app.isHidden = app.app.appId in hiddenAppIds
            }
        }

        notifyDataSetChanged()
    }

    fun updateLayoutWithPreferences(context: Context, prefs: PreferenceConfiguration) {
        val itemWidth = getItemWidthForPreferences(context, prefs)

        val scalingDivisor = (ART_WIDTH_PX.toDouble() / itemWidth).coerceAtLeast(1.0)

        if (loader != null) {
            cancelQueuedOperations()
        }

        loader = CachedAppAssetLoader(
            context,
            computer,
            scalingDivisor,
            NetworkAssetLoader(context, uniqueId),
            MemoryAssetLoader(),
            DiskAssetLoader(context),
            BitmapFactory.decodeResource(context.resources, R.drawable.no_app_image)
        )

        setLayoutId(getLayoutIdForPreferences(prefs))
    }

    fun cancelQueuedOperations() {
        loader?.cancelForegroundLoads()
        loader?.cancelBackgroundLoads()
        loader?.freeCacheMemory()
    }

    fun getLoader(): CachedAppAssetLoader? = loader

    fun addApp(app: AppView.AppObject) {
        app.isHidden = app.app.appId in hiddenAppIds
        allApps.add(app)

        if (showHiddenApps || !app.isHidden) {
            loader?.queueCacheLoad(app.app)
            itemList.add(app)
        }
    }

    fun removeApp(app: AppView.AppObject) {
        itemList.remove(app)
        allApps.remove(app)
    }

    fun rebuildAppList(newApps: List<AppView.AppObject>) {
        allApps.clear()
        itemList.clear()

        for (app in newApps) {
            app.isHidden = app.app.appId in hiddenAppIds
            allApps.add(app)

            if (showHiddenApps || !app.isHidden) {
                loader?.queueCacheLoad(app.app)
                itemList.add(app)
            }
        }
    }

    override fun clear() {
        super.clear()
        allApps.clear()
    }

    override fun getItemId(i: Int): Long {
        return if (i in 0 until itemList.size) {
            itemList[i].app.appId.toLong()
        } else {
            super.getItemId(i)
        }
    }

    override fun populateView(
        parentView: View,
        imgView: ImageView?,
        spinnerView: View?,
        txtView: TextView?,
        overlayView: ImageView?,
        obj: AppView.AppObject
    ) {
        loader?.populateImageView(obj, imgView!!, txtView, false) { bitmap ->
            AppIconCache.instance.putIcon(computer, obj.app, bitmap)
        }

        if (obj.isRunning) {
            overlayView?.setImageResource(R.drawable.ic_play_cute)
            overlayView?.visibility = View.VISIBLE
        } else {
            overlayView?.visibility = View.GONE
        }

        parentView.alpha = if (obj.isHidden) 0.40f else 1.0f
    }

    companion object {
        private const val ART_WIDTH_PX = 300

        fun getItemWidthForPreferences(context: Context, prefs: PreferenceConfiguration): Int {
            val widthRes = if (prefs.smallIconMode) {
                R.dimen.appview_small_card_width
            } else {
                R.dimen.appview_large_card_width
            }
            return context.resources.getDimensionPixelSize(widthRes)
        }

        fun getLayoutIdForPreferences(prefs: PreferenceConfiguration): Int {
            return if (prefs.smallIconMode) R.layout.app_grid_item_small else R.layout.app_grid_item
        }

        private fun sortList(list: MutableList<AppView.AppObject>) {
            list.sortWith(Comparator.comparing { it.app.appName.lowercase() })
        }
    }
}
