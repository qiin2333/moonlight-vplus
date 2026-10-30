package com.limelight.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.limelight.R
import kotlin.math.max
import kotlin.math.roundToInt

/** Holds the last picture over a changing Surface, with a small, truthful status and a soft reveal. */
internal class ResolutionTransitionOverlay(activity: Activity, width: Int, height: Int) {
    private val parent = activity.findViewById<ViewGroup>(android.R.id.content)
    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(activity)
    private val image = ImageView(activity)
    private val status = TextView(activity)
    private var bitmap: Bitmap? = null
    private var disposed = false
    private fun dp(value: Int) = (value * root.resources.displayMetrics.density).roundToInt()

    init {
        val accent = UiHelper.accentColor(activity)
        val accentSoft = UiHelper.accentSoftColor(activity)
        // Keep focus on the stream; consume touches while its coordinate system is changing.
        root.isClickable = true
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.elevation = dp(32).toFloat()
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(activity).apply { setBackgroundColor(0x30000000) }, FrameLayout.LayoutParams(-1, -1))

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.game_menu_card_background))
                cornerRadius = activity.resources.getDimension(R.dimen.corner_radius_medium)
                // Match the game menu's 0.75dp surface border and theme-derived soft accent.
                setStroke(max(1, (0.75f * activity.resources.displayMetrics.density).roundToInt()), accentSoft)
            }
            elevation = dp(6).toFloat()
        }
        card.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        status.apply {
            text = activity.getString(R.string.resolution_switch_adjusting)
            setTextColor(ContextCompat.getColor(activity, R.color.game_menu_text_primary))
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        labels.addView(status)
        labels.addView(TextView(activity).apply {
            text = activity.getString(R.string.resolution_switch_target, width, height)
            setTextColor(ContextCompat.getColor(activity, R.color.game_menu_text_secondary))
            textSize = 12f
            fontFeatureSettings = "tnum"
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(labels)
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(48)
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
        parent.addView(root, ViewGroup.LayoutParams(-1, -1))
        // A fast switch need not flash a spinner; the retained frame is visible immediately.
        card.alpha = 0f
        card.translationY = dp(8).toFloat()
        card.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun setFrame(frame: Bitmap?) {
        if (disposed) { frame?.recycle(); return }
        bitmap = frame
        image.setImageBitmap(frame)
        if (frame != null) root.setBackgroundColor(Color.BLACK)
    }

    fun reconnecting() { status.setText(R.string.resolution_switch_reconnecting) }

    /** Transfer ownership to the next Activity; keep displaying it until this one is destroyed. */
    fun takeBackdrop(): Bitmap? = bitmap.also { bitmap = null }

    fun reveal(onComplete: () -> Unit) {
        // Allow SurfaceFlinger a few vsyncs to compose the confirmed new frame before the fade.
        handler.postDelayed({
            if (!disposed) root.animate().alpha(0f).setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { dispose(); onComplete() }.start()
        }, 64)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        handler.removeCallbacksAndMessages(null)
        root.animate().cancel()
        (root.getChildAt(root.childCount - 1))?.animate()?.cancel()
        parent.removeView(root)
        image.setImageDrawable(null)
        // Do not recycle an attached bitmap while RenderThread may still hold its display list.
        bitmap = null
    }

    companion object {
        /** Bounded in time and memory, including when a Surface stops producing during PixelCopy. */
        fun captureFrame(activity: Activity, view: SurfaceView?, onResult: (Bitmap?) -> Unit) {
            if (Build.VERSION.SDK_INT < 24 || view == null || view.width <= 0 || view.height <= 0 ||
                !view.holder.surface.isValid) { onResult(null); return }
            val info = ActivityManager.MemoryInfo()
            (activity.getSystemService(Activity.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
            if (info.lowMemory) { onResult(null); return }
            val scale = minOf(1.0, 1280.0 / max(view.width, view.height))
            val frame = try {
                Bitmap.createBitmap(max(1, (view.width * scale).toInt()), max(1, (view.height * scale).toInt()),
                    Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) { onResult(null); return }
            val handler = Handler(Looper.getMainLooper())
            var delivered = false
            val timeout = Runnable {
                if (!delivered) { delivered = true; onResult(null) }
            }
            handler.postDelayed(timeout, 250)
            try {
                PixelCopy.request(view, frame, { result ->
                    handler.removeCallbacks(timeout)
                    if (delivered) {
                        frame.recycle()
                    } else {
                        delivered = true
                        if (result == PixelCopy.SUCCESS && !activity.isFinishing && !activity.isDestroyed) {
                            onResult(frame)
                        } else {
                            frame.recycle()
                            onResult(null)
                        }
                    }
                }, handler)
            } catch (_: Exception) {
                handler.removeCallbacks(timeout)
                if (!delivered) { delivered = true; frame.recycle(); onResult(null) }
            }
        }
    }
}
