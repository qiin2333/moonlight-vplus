package com.limelight.grid

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.limelight.AppView
import com.limelight.LimeLog
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
        val dpi = context.resources.displayMetrics.densityDpi
        val dp = if (prefs.smallIconMode) SMALL_WIDTH_DP else LARGE_WIDTH_DP

        var scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0))
        if (scalingDivisor < 1.0) {
            scalingDivisor = 1.0
        }
        LimeLog.info("Art scaling divisor: $scalingDivisor")

        if (loader != null) {
            cancelQueuedOperations()
        }

        loader = CachedAppAssetLoader(
            context, computer, scalingDivisor,
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

    override fun populateView(parentView: View, imgView: ImageView?, spinnerView: View?, txtView: TextView?, overlayView: ImageView?, obj: AppView.AppObject) {
        loader?.populateImageView(obj, imgView!!, txtView, false) {
            try {
                val tuple = CachedAppAssetLoader.LoaderTuple(computer, obj.app)
                val scaledBitmap = loader?.getBitmapFromCache(tuple)
                if (scaledBitmap?.bitmap != null) {
                    AppIconCache.instance.putIcon(computer, obj.app, scaledBitmap.bitmap)
                    println("成功缓存app icon: ${obj.app.appName}")
                } else {
                    println("无法获取app icon进行缓存: ${obj.app.appName}")
                }
            } catch (e: Exception) {
                println("缓存app icon时发生异常: ${obj.app.appName} - ${e.message}")
            }
        }

        if (obj.isRunning) {
            overlayView?.setImageResource(R.drawable.ic_play_cute)
            overlayView?.visibility = View.VISIBLE
            setBackgroundViaManager(obj)
        } else {
            val activity = getActivity(context)
            val blurView = activity?.findViewById<ImageView>(R.id.appBackgroundImageBlur)
            if (obj.app.appName.equals("desktop", ignoreCase = true) && blurView != null && blurView.drawable == null) {
                setBackgroundViaManager(obj)
            }
            overlayView?.visibility = View.GONE
        }

        if (obj.isHidden) {
            parentView.alpha = 0.40f
        } else {
            parentView.alpha = 1.0f
        }
    }

    private fun setBackgroundViaManager(obj: AppView.AppObject) {
        val activity = getActivity(context) as? AppView ?: return
        val bgManager = activity.backgroundImageManagerInstance ?: return
        loader?.loadFullBitmap(obj.app) { bitmap -> bgManager.setBackgroundSmoothly(bitmap) }
    }

    companion object {
        private const val ART_WIDTH_PX = 300
        private const val SMALL_WIDTH_DP = 120
        private const val LARGE_WIDTH_DP = 180

        fun getLayoutIdForPreferences(prefs: PreferenceConfiguration): Int {
            return if (prefs.smallIconMode) R.layout.app_grid_item_small else R.layout.app_grid_item
        }

        fun getActivity(context: Context): Activity? {
            return when (context) {
                is Activity -> context
                is ContextWrapper -> getActivity(context.baseContext)
                else -> null
            }
        }

        private fun sortList(list: MutableList<AppView.AppObject>) {
            list.sortWith(Comparator.comparing { it.app.appName.lowercase() })
        }
    }
}
