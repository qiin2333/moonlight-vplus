package com.limelight.utils

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.perfhud.PerfHudDensity
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.MoonwaxLoaderView
import androidx.preference.PreferenceManager
import java.util.Locale
import java.util.Random

class FullscreenProgressOverlay(
    private val activity: Activity,
    private val app: NvApp?
) {
    private val overlayView: View
    private val moonwax: MoonwaxLoaderView
    private val statusText: TextView
    private val progressText: TextView
    private val randomTip: TextView
    private val appPosterBackgroundBlur: ImageView
    private val appPosterBackgroundClear: ImageView
    private val rootView: ViewGroup
    private val tips: Array<String>
    private val random = Random()
    private val backgroundMode = AppBackgroundMode.read(activity)
    private var isShowing = false
    private var reconnectBackdrop: Bitmap? = null

    private fun isClassicPerfOverlay(activity: Activity): Boolean =
        PerfHudDensity.fromPreference(
            PreferenceManager.getDefaultSharedPreferences(activity)
                .getString(PreferenceConfiguration.PERF_OVERLAY_STYLE_STRING, null)
        ) == PerfHudDensity.CLASSIC
    private var posterRequestSerial = 0
    var computer: ComputerDetails? = null

    init {
        tips = arrayOf(
            activity.getString(R.string.tip_esc_exit),
            activity.getString(R.string.tip_double_tap_mouse),
            activity.getString(R.string.tip_long_press_controller),
            activity.getString(R.string.tip_volume_keys),
            activity.getString(R.string.tip_wallpaper_change),
            activity.getString(R.string.tip_5ghz_wifi),
            activity.getString(R.string.tip_close_apps),
            activity.getString(R.string.tip_home_saves),
            activity.getString(R.string.tip_hdr_colors),
            activity.getString(R.string.tip_touch_modes),
            activity.getString(R.string.tip_custom_keys),
            activity.getString(
                if (isClassicPerfOverlay(activity)) R.string.tip_performance_overlay_classic
                else R.string.tip_performance_overlay
            ),
            activity.getString(R.string.tip_audio_config),
            activity.getString(R.string.tip_external_display),
            activity.getString(R.string.tip_virtual_display),
            activity.getString(R.string.tip_dynamic_bitrate),
            activity.getString(R.string.tip_cards_show)
        )

        rootView = activity.findViewById(android.R.id.content)

        val inflater = LayoutInflater.from(activity)
        overlayView = inflater.inflate(R.layout.fullscreen_progress_overlay, rootView, false)

        statusText = overlayView.findViewById(R.id.statusText)
        progressText = overlayView.findViewById(R.id.progressText)
        randomTip = overlayView.findViewById(R.id.randomTip)
        appPosterBackgroundBlur = overlayView.findViewById(R.id.appPosterBackgroundBlur)
        appPosterBackgroundClear = overlayView.findViewById(R.id.appPosterBackgroundClear)
        moonwax = overlayView.findViewById(R.id.loading_moonwax)

        overlayView.visibility = View.GONE
    }

    fun show(title: String, message: String) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (!isShowing) {
                statusText.text = title.trim()
                progressText.text = message

                val tip = tips[random.nextInt(tips.size)]
                randomTip.text = tip

                if (overlayView.parent == null) {
                    rootView.addView(overlayView)
                }

                // 快速断开重连时上一轮 dismiss 的退场可能还在跑：先掐掉它，
                // 否则旧 endAction 会把刚亮起的整层再次隐藏，吃掉月亮的进场动画。
                overlayView.animate().cancel()
                overlayView.alpha = 1f
                overlayView.visibility = View.VISIBLE
                isShowing = true

                moonwax.refreshAccentColor()
                moonwax.resetForShow()
                playEntryAnimation()

                applySoftColorFallback()
                reconnectBackdrop?.let { applyPoster(it) } ?: loadAppImage()
            }
        }
    }

    /**
     * 进场：月亮先亮，标题与阶段文案依次跟上。只做 alpha/scale/translation，
     * 不触发布局，避免连接刚建立时和主线程抢时间。
     */
    private fun playEntryAnimation() {
        val density = activity.resources.displayMetrics.density
        if (!animationsEnabled()) {
            moonwax.alpha = 1f
            moonwax.scaleX = 1f
            moonwax.scaleY = 1f
            statusText.alpha = 1f
            statusText.translationY = 0f
            progressText.alpha = 1f
            progressText.translationY = 0f
            randomTip.alpha = 1f
            return
        }

        moonwax.animate().cancel()
        moonwax.alpha = 0f
        moonwax.scaleX = 0.94f
        moonwax.scaleY = 0.94f
        moonwax.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(520)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()

        statusText.animate().cancel()
        statusText.alpha = 0f
        statusText.translationY = 8f * density
        statusText.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(110)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()

        progressText.animate().cancel()
        progressText.alpha = 0f
        progressText.translationY = 8f * density
        progressText.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(180)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()

        randomTip.animate().cancel()
        randomTip.alpha = 0f
        randomTip.animate()
            .alpha(1f)
            .setStartDelay(460)
            .setDuration(520)
            .start()
    }

    fun setMessage(message: String) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (!isShowing || progressText.text == message) return@runOnUiThread

            // 连接阶段可能每几百毫秒就来一条：先落字再做一个短促的「tick」，
            // 避免淡出/淡入在快速连发时留下空白帧或滞后文案。
            progressText.animate().cancel()
            progressText.text = message
            progressText.alpha = 0.45f
            progressText.translationY = 3f * activity.resources.displayMetrics.density
            progressText.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun setStatus(status: String) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                statusText.text = status.trim()
            }
        }
    }

    /**
     * 把 moonlight-common-c 的连接阶段翻译成月相档位（1..4）。阶段可能乱序或重复到达，
     * [MoonwaxLoaderView.markStage] 只前进不后退，所以月亮不会来回闪。
     */
    fun setStageName(stage: String) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                moonwax.markStage(stageIndexFor(stage))
            }
        }
    }

    /** 首帧即将到达：满月。 */
    fun markReady() {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                moonwax.markReady()
            }
        }
    }

    private fun stageIndexFor(stage: String): Int {
        val name = stage.lowercase(Locale.ROOT)
        return when {
            name.contains("platform") ||
                name.contains("name resolution") ||
                name.contains("audio stream initialization") -> 1
            name.contains("rtsp") ||
                name.contains("control stream initialization") -> 2
            name.contains("video stream initialization") ||
                name.contains("input stream initialization") ||
                name.contains("control stream establishment") -> 3
            else -> 4
        }
    }

    private fun animationsEnabled(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }

    fun setAppPoster(poster: Bitmap?) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (poster != null) {
                reconnectBackdrop = poster
                applyPoster(poster)
            } else if (reconnectBackdrop == null) {
                applyMissingPoster()
            }
        }
    }

    fun setAppPoster(poster: Drawable?) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            when {
                poster == null -> applyMissingPoster()
                else -> applyDrawablePoster(poster)
            }
        }
    }

    fun setProgress(progress: Int) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                val fraction = (progress / 100f).coerceIn(0f, 1f)
                moonwax.setProgress(fraction)
            }
        }
    }

    fun setIndeterminate(indeterminate: Boolean) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                moonwax.setIndeterminate(indeterminate)
            }
        }
    }

    fun dismiss() {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                isShowing = false
                posterRequestSerial++
                moonwax.animate().cancel()
                statusText.animate().cancel()
                progressText.animate().cancel()
                randomTip.animate().cancel()
                overlayView.animate().cancel()
                overlayView.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        if (isShowing) return@withEndAction // 退场期间已重新 show()，交还给新的一轮
                        overlayView.visibility = View.GONE
                        overlayView.alpha = 1f
                        appPosterBackgroundBlur.tag = null
                        appPosterBackgroundClear.tag = null
                        appPosterBackgroundBlur.setImageDrawable(null)
                        appPosterBackgroundClear.setImageDrawable(null)
                        reconnectBackdrop = null
                        if (overlayView.parent != null) {
                            rootView.removeView(overlayView)
                        }
                    }
                    .start()
            }
        }
    }

    fun isShowing(): Boolean = isShowing

    private fun loadAppImage() {
        val curApp = app ?: return
        val curComputer = computer ?: return
        val cached = AppIconCache.instance.getFullIcon(curComputer, curApp)
        if (cached != null) {
            if (reconnectBackdrop == null) applyPoster(cached)
            return
        }

        // 内存 cache miss（冷启动 / 快捷方式 / Trampoline 入口）：fallback 到磁盘缓存
        val uuid = curComputer.uuid ?: return
        val appId = curApp.appId
        val appCtx = activity.applicationContext
        val dm = appCtx.resources.displayMetrics
        val maxDim = kotlin.math.max(dm.widthPixels, dm.heightPixels).coerceAtMost(1920)
        Thread({
            val bitmap = try {
                com.limelight.grid.assets.DiskAssetLoader(appCtx)
                    .loadFullBitmapFromCache(uuid, appId, maxDim)
            } catch (t: Throwable) {
                null
            } ?: return@Thread
            AppIconCache.instance.putFullIcon(curComputer, curApp, bitmap)
            activity.runOnUiThread {
                if (isShowing && reconnectBackdrop == null) applyPoster(bitmap)
            }
        }, "OverlayPosterLoader").start()
    }

    private fun applyPoster(bitmap: Bitmap) {
        when (backgroundMode) {
            AppBackgroundMode.Artwork -> applyArtwork(bitmap)
            AppBackgroundMode.Acrylic -> applyAcrylic(bitmap)
            AppBackgroundMode.SoftColor -> applySoftColor(bitmap)
        }
    }

    /** 应用封面：保留连接页原有的模糊背景 + 完整封面双层显示。 */
    private fun applyArtwork(bitmap: Bitmap) {
        ++posterRequestSerial
        clearAcrylicMode()
        appPosterBackgroundBlur.visibility = View.VISIBLE
        appPosterBackgroundClear.visibility = View.VISIBLE
        appPosterBackgroundBlur.scaleType = ImageView.ScaleType.CENTER_CROP
        appPosterBackgroundClear.scaleType = ImageView.ScaleType.FIT_CENTER
        appPosterBackgroundBlur.tag = bitmap
        appPosterBackgroundClear.tag = bitmap
        clearRenderEffect(appPosterBackgroundClear)
        BackgroundImageManager.setBlurredBitmap(
            appPosterBackgroundBlur,
            bitmap,
            BackgroundImageManager.OVERLAY_IMAGE_ALPHA
        )
        appPosterBackgroundClear.setImageBitmap(bitmap)
        appPosterBackgroundClear.imageAlpha = BackgroundImageManager.OVERLAY_IMAGE_ALPHA
    }

    /** 亚克力：全屏模糊底图 + 绘制阶段合成的中央半透明完整封面。 */
    private fun applyAcrylic(bitmap: Bitmap) {
        ++posterRequestSerial
        appPosterBackgroundBlur.visibility = View.VISIBLE
        appPosterBackgroundClear.visibility = View.VISIBLE
        appPosterBackgroundBlur.scaleType = ImageView.ScaleType.CENTER_CROP
        appPosterBackgroundClear.scaleType = ImageView.ScaleType.FIT_CENTER
        appPosterBackgroundBlur.tag = bitmap
        appPosterBackgroundClear.tag = bitmap
        BackgroundImageManager.setBlurredBitmap(
            appPosterBackgroundBlur,
            bitmap,
            BackgroundImageManager.OVERLAY_IMAGE_ALPHA
        )
        setAcrylicBitmap(bitmap)
    }

    private fun applySoftColor(bitmap: Bitmap) {
        val requestId = ++posterRequestSerial
        val fallbackColor = app?.let { SoftBackgroundColorExtractor.fallbackFor(it) }
            ?: 0xFF4D464A.toInt()
        applyColor(fallbackColor)

        Thread({
            val color = SoftBackgroundColorExtractor.fromBitmap(bitmap, fallbackColor)
            activity.runOnUiThread {
                if (isShowing && requestId == posterRequestSerial) {
                    applyColor(color)
                }
            }
        }, "OverlayColorExtractor").start()
    }

    private fun applySoftColorFallback() {
        if (backgroundMode != AppBackgroundMode.SoftColor) return
        val fallbackColor = app?.let { SoftBackgroundColorExtractor.fallbackFor(it) }
            ?: 0xFF4D464A.toInt()
        applyColor(fallbackColor)
    }

    private fun applyColor(color: Int) {
        appPosterBackgroundBlur.tag = null
        appPosterBackgroundClear.tag = null
        appPosterBackgroundClear.setImageDrawable(null)
        clearAcrylicMode()
        appPosterBackgroundBlur.visibility = View.VISIBLE
        appPosterBackgroundClear.visibility = View.GONE
        clearRenderEffect(appPosterBackgroundBlur)
        appPosterBackgroundBlur.setImageDrawable(android.graphics.drawable.ColorDrawable(color))
        appPosterBackgroundBlur.imageAlpha = 255
    }

    private fun applyDrawablePoster(drawable: Drawable) {
        if (backgroundMode == AppBackgroundMode.SoftColor) {
            applySoftColorFallback()
            return
        }

        ++posterRequestSerial
        appPosterBackgroundBlur.visibility = View.VISIBLE
        appPosterBackgroundClear.visibility = View.VISIBLE
        appPosterBackgroundBlur.scaleType = ImageView.ScaleType.CENTER_CROP
        appPosterBackgroundClear.scaleType = ImageView.ScaleType.FIT_CENTER
        appPosterBackgroundBlur.tag = drawable
        appPosterBackgroundClear.tag = drawable
        clearRenderEffect(appPosterBackgroundClear)
        BackgroundImageManager.setBlurredDrawable(
            appPosterBackgroundBlur,
            drawable,
            BackgroundImageManager.OVERLAY_IMAGE_ALPHA
        )

        if (backgroundMode == AppBackgroundMode.Acrylic) {
            setAcrylicDrawable(drawable)
        } else {
            clearAcrylicMode()
            appPosterBackgroundClear.setImageDrawable(drawable)
            appPosterBackgroundClear.imageAlpha = BackgroundImageManager.OVERLAY_IMAGE_ALPHA
        }
    }

    private fun applyMissingPoster() {
        ++posterRequestSerial
        appPosterBackgroundBlur.tag = null
        appPosterBackgroundClear.tag = null
        clearAcrylicMode()
        appPosterBackgroundBlur.setImageDrawable(null)
        appPosterBackgroundClear.setImageDrawable(null)

        if (backgroundMode == AppBackgroundMode.SoftColor) {
            applySoftColorFallback()
            return
        }

        appPosterBackgroundBlur.visibility = View.VISIBLE
        appPosterBackgroundClear.visibility = View.GONE
        appPosterBackgroundBlur.setImageResource(R.drawable.no_app_image)
    }

    private fun setAcrylicBitmap(bitmap: Bitmap) {
        val acrylicImageView = appPosterBackgroundClear as? AcrylicImageView
        if (acrylicImageView != null) {
            acrylicImageView.setAcrylicBitmap(
                bitmap,
                BackgroundImageManager.OVERLAY_IMAGE_ALPHA
            )
        } else {
            appPosterBackgroundClear.setImageBitmap(bitmap)
            appPosterBackgroundClear.imageAlpha = BackgroundImageManager.OVERLAY_IMAGE_ALPHA
        }
    }

    private fun setAcrylicDrawable(drawable: Drawable) {
        val acrylicImageView = appPosterBackgroundClear as? AcrylicImageView
        if (acrylicImageView != null) {
            acrylicImageView.setAcrylicDrawable(
                drawable,
                BackgroundImageManager.OVERLAY_IMAGE_ALPHA
            )
        } else {
            appPosterBackgroundClear.setImageDrawable(drawable)
            appPosterBackgroundClear.imageAlpha = BackgroundImageManager.OVERLAY_IMAGE_ALPHA
        }
    }

    private fun clearAcrylicMode() {
        (appPosterBackgroundClear as? AcrylicImageView)?.clearAcrylicMode()
    }

    private fun clearRenderEffect(imageView: ImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            imageView.setRenderEffect(null)
        }
    }
}
