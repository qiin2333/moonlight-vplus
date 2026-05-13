package com.limelight.utils

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import java.util.Random

class FullscreenProgressOverlay(
    private val activity: Activity,
    private val app: NvApp?
) {
    private val overlayView: View
    private val statusText: TextView
    private val progressText: TextView
    private val randomTip: TextView
    private val appPosterBackgroundBlur: ImageView
    private val appPosterBackgroundClear: ImageView
    private val progressBar: ProgressBar
    private val rootView: ViewGroup
    private val tips: Array<String>
    private val random = Random()
    private var isShowing = false
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
            activity.getString(R.string.tip_performance_overlay),
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
        progressBar = overlayView.findViewById(R.id.progressBar)

        overlayView.visibility = View.GONE
    }

    fun show(title: String, message: String) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (!isShowing) {
                statusText.text = title
                progressText.text = message

                val tip = tips[random.nextInt(tips.size)]
                randomTip.text = tip

                if (overlayView.parent == null) {
                    rootView.addView(overlayView)
                }

                overlayView.visibility = View.VISIBLE
                isShowing = true

                loadAppImage()
            }
        }
    }

    fun setMessage(message: String) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                progressText.text = message
            }
        }
    }

    fun setStatus(status: String) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                statusText.text = status
            }
        }
    }

    fun setAppPoster(poster: Bitmap?) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (poster != null) {
                BackgroundImageManager.setBlurredBitmap(appPosterBackgroundBlur, poster, BackgroundImageManager.OVERLAY_IMAGE_ALPHA)
                appPosterBackgroundClear.setImageBitmap(BackgroundImageManager.applyAlpha(poster, BackgroundImageManager.OVERLAY_IMAGE_ALPHA))
            } else {
                appPosterBackgroundBlur.setImageResource(R.drawable.no_app_image)
                appPosterBackgroundClear.setImageBitmap(null)
            }
        }
    }

    fun setAppPoster(poster: Drawable?) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (poster != null) {
                BackgroundImageManager.setBlurredDrawable(appPosterBackgroundBlur, poster, BackgroundImageManager.OVERLAY_IMAGE_ALPHA)
                if (poster is BitmapDrawable) {
                    val bmp = poster.bitmap
                    if (bmp != null) {
                        appPosterBackgroundClear.setImageBitmap(BackgroundImageManager.applyAlpha(bmp, BackgroundImageManager.OVERLAY_IMAGE_ALPHA))
                    } else {
                        appPosterBackgroundClear.setImageDrawable(poster)
                    }
                } else {
                    appPosterBackgroundClear.setImageDrawable(poster)
                }
            } else {
                appPosterBackgroundBlur.setImageResource(R.drawable.no_app_image)
                appPosterBackgroundClear.setImageBitmap(null)
            }
        }
    }

    fun setProgress(progress: Int) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                progressBar.isIndeterminate = false
                progressBar.progress = progress
            }
        }
    }

    fun setIndeterminate(indeterminate: Boolean) {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                progressBar.isIndeterminate = indeterminate
            }
        }
    }

    fun dismiss() {
        if (activity.isFinishing) return

        activity.runOnUiThread {
            if (isShowing) {
                isShowing = false
                // 这一刻视频首帧已合成上屏（由 decoderRenderer.firstFrameCallback 保证）。
                // 短暂淡出让 scrim 暗度过渡到正常画面亮度，避免亮度跳变带来的"闪一下"。
                overlayView.animate().cancel()
                overlayView.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        overlayView.visibility = View.GONE
                        overlayView.alpha = 1f
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
            applyPoster(cached)
            return
        }
        // 内存 cache miss（冷启动 / 快捷方式 / Trampoline 入口）：fallback 到磁盘缓存
        val uuid = curComputer.uuid ?: return
        val appId = curApp.appId
        val appCtx = activity.applicationContext
        Thread({
            val bitmap = try {
                com.limelight.grid.assets.DiskAssetLoader(appCtx).loadFullBitmapFromCache(uuid, appId)
            } catch (t: Throwable) { null } ?: return@Thread
            AppIconCache.instance.putFullIcon(curComputer, curApp, bitmap)
            activity.runOnUiThread {
                if (isShowing) applyPoster(bitmap)
            }
        }, "OverlayPosterLoader").start()
    }

    private fun applyPoster(bitmap: Bitmap) {
        appPosterBackgroundBlur.visibility = View.VISIBLE
        appPosterBackgroundClear.visibility = View.VISIBLE
        BackgroundImageManager.setBlurredBitmap(appPosterBackgroundBlur, bitmap, BackgroundImageManager.OVERLAY_IMAGE_ALPHA)
        appPosterBackgroundClear.setImageBitmap(BackgroundImageManager.applyAlpha(bitmap, BackgroundImageManager.OVERLAY_IMAGE_ALPHA))
    }
}
