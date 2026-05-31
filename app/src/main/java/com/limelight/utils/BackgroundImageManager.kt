package com.limelight.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.graphics.drawable.ColorDrawable
import java.util.concurrent.Executors

import com.limelight.R

/**
 * 背景图片管理器，用于处理AppView背景图片的平滑切换。
 * - 横屏：模糊层(blurImageView) + 清晰层(clearImageView) 双层视觉。
 * - 竖屏 / 单层模式：blurImageView 传 null + blurOnly=true，使用 clearImageView 作为
 *   唯一画布并对其应用模糊效果，铺满屏幕。
 * 仅通过 imageAlpha 调整透明度，不再为每张图复制 Bitmap。
 */
class BackgroundImageManager(
    private val context: Context,
    private val blurImageView: ImageView?,
    private val clearImageView: ImageView,
    private val blurOnly: Boolean = false
) {
    var currentBackground: Bitmap? = null
        private set
    private var currentSolidColor: Int? = null
    val hasBackground: Boolean
        get() = currentBackground != null || currentSolidColor != null

    /**
     * 平滑地切换到新的背景图片
     * @param newBackground 新的背景图片
     */
    fun setBackgroundSmoothly(newBackground: Bitmap?) {
        if (newBackground == null || newBackground.isRecycled) {
            return
        }

        // 如果背景图片相同，不需要切换
        if (currentBackground == newBackground) {
            return
        }

        // 如果当前没有背景图片，直接设置
        if (currentBackground == null) {
            currentBackground = newBackground
            currentSolidColor = null
            applyBitmap(newBackground)
            val fadeIn = AnimationUtils.loadAnimation(context, R.anim.background_fadein)
            blurImageView?.startAnimation(fadeIn)
            clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadein))
            return
        }

        // 执行平滑切换动画
        val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout)
        fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                currentBackground = newBackground
                currentSolidColor = null
                applyBitmap(newBackground)
                val fadeIn = AnimationUtils.loadAnimation(context, R.anim.background_fadein)
                blurImageView?.startAnimation(fadeIn)
                clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadein))
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })

        (blurImageView ?: clearImageView).startAnimation(fadeOutAnimation)
        if (blurImageView != null) {
            clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadeout))
        }
    }

    fun setBackgroundColorSmoothly(color: Int) {
        if (currentSolidColor == color) {
            return
        }

        if (!hasBackground) {
            currentBackground = null
            currentSolidColor = color
            applyColor(color)
            val fadeIn = AnimationUtils.loadAnimation(context, R.anim.background_fadein)
            blurImageView?.startAnimation(fadeIn)
            clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadein))
            return
        }

        val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout)
        fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                currentBackground = null
                currentSolidColor = color
                applyColor(color)
                val fadeIn = AnimationUtils.loadAnimation(context, R.anim.background_fadein)
                blurImageView?.startAnimation(fadeIn)
                clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadein))
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })

        (blurImageView ?: clearImageView).startAnimation(fadeOutAnimation)
        if (blurImageView != null) {
            clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadeout))
        }
    }

    /** 同一张 Bitmap 同时驱动模糊层与清晰层；仅通过 imageAlpha 调整透明度，零额外 Bitmap 分配。 */
    private fun applyBitmap(bitmap: Bitmap) {
        // 横屏双层：blur 层用更大半径与更低 alpha，让中间清晰大图更突出
        blurImageView?.let {
            setBlurredBitmap(it, bitmap, BLUR_IMAGE_ALPHA_PAIRED, RENDER_EFFECT_RADIUS_PAIRED, BLUR_RADIUS_PAIRED)
        }
        if (blurOnly) {
            // 单层模式：clearImageView 作为唯一画布并应用默认强度模糊
            setBlurredBitmap(clearImageView, bitmap, BLUR_IMAGE_ALPHA)
        } else {
            clearImageView.setImageBitmap(bitmap)
            clearImageView.imageAlpha = CLEAR_IMAGE_ALPHA
        }
    }

    private fun applyColor(color: Int) {
        blurImageView?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.setRenderEffect(null)
            }
            it.setImageDrawable(ColorDrawable(color))
            it.imageAlpha = 255
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearImageView.setRenderEffect(null)
        }
        clearImageView.setImageDrawable(ColorDrawable(color))
        clearImageView.imageAlpha = 255
    }

    /**
     * 清除背景图片
     */
    fun clearBackground() {
        if (hasBackground) {
            val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout)
            fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}

                override fun onAnimationEnd(animation: Animation) {
                    blurImageView?.setImageBitmap(null)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        blurImageView?.setRenderEffect(null)
                    }
                    clearImageView.setImageBitmap(null)
                    currentBackground = null
                    currentSolidColor = null
                }

                override fun onAnimationRepeat(animation: Animation) {}
            })

            (blurImageView ?: clearImageView).startAnimation(fadeOutAnimation)
            if (blurImageView != null) {
                clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadeout))
            }
        }
    }

    companion object {
        private const val CLEAR_IMAGE_ALPHA = 160 // ~63%
        private const val BLUR_IMAGE_ALPHA = 160  // ~63%
        // 横屏双层下的 blur 背景：更朗弱且更胧胧朝朝，让中间清晰大图更突出
        private const val BLUR_IMAGE_ALPHA_PAIRED = 100      // ~39%
        private const val RENDER_EFFECT_RADIUS_PAIRED = 60f  // 原 25f
        private const val BLUR_RADIUS_PAIRED = 22            // 原 10
        const val OVERLAY_IMAGE_ALPHA = 160       // ~63%
        private const val BLUR_RADIUS = 10
        private const val RENDER_EFFECT_RADIUS = 25f
        private const val BG_COLOR = 0xFF4D464A.toInt()

        private val blurExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * 设置模糊图片到ImageView
         * Android 12+: GPU加速的RenderEffect，零额外内存分配
         * 低版本: 后台线程StackBlur
         */
        fun setBlurredBitmap(
            imageView: ImageView,
            bitmap: Bitmap,
            alpha: Int,
            renderEffectRadius: Float = RENDER_EFFECT_RADIUS,
            stackBlurRadius: Int = BLUR_RADIUS
        ) {
            if (bitmap.isRecycled) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                imageView.setImageBitmap(bitmap)
                imageView.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        renderEffectRadius, renderEffectRadius,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                )
                imageView.imageAlpha = alpha
            } else {
                imageView.tag = bitmap
                blurExecutor.execute {
                    if (bitmap.isRecycled) return@execute
                    val blurred = stackBlur(bitmap, stackBlurRadius)
                    mainHandler.post {
                        if (imageView.tag === bitmap) {
                            imageView.setImageBitmap(blurred)
                            imageView.imageAlpha = alpha
                        }
                    }
                }
            }
        }

        /**
         * 设置模糊Drawable到ImageView
         */
        fun setBlurredDrawable(imageView: ImageView, drawable: android.graphics.drawable.Drawable, alpha: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                imageView.setImageDrawable(drawable)
                imageView.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        RENDER_EFFECT_RADIUS, RENDER_EFFECT_RADIUS,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                )
                imageView.imageAlpha = alpha
            } else if (drawable is android.graphics.drawable.BitmapDrawable) {
                val bmp = drawable.bitmap
                if (bmp != null && !bmp.isRecycled) {
                    setBlurredBitmap(imageView, bmp, alpha)
                    return
                }
                imageView.setImageDrawable(drawable)
                imageView.imageAlpha = alpha
            } else {
                imageView.setImageDrawable(drawable)
                imageView.imageAlpha = alpha
            }
        }

        /**
         * StackBlur 算法 - 对缩小后的图片进行模糊处理以提升性能
         */
        fun stackBlur(original: Bitmap, radius: Int): Bitmap {
            if (original.isRecycled) return original
            try {
                val src = if (Build.VERSION.SDK_INT >= 26 &&
                    original.config == Bitmap.Config.HARDWARE) {
                    original.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    original
                }
                val scaleFactor = 3
                val smallWidth = (src.width / scaleFactor).coerceAtLeast(1)
                val smallHeight = (src.height / scaleFactor).coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(src, smallWidth, smallHeight, true)

            val bitmap = small.copy(Bitmap.Config.ARGB_8888, true)
            // 回收中间Bitmap
            if (small !== src && small !== original) small.recycle()
            if (src !== original) src.recycle()

            val w = bitmap.width
            val h = bitmap.height
            val pix = IntArray(w * h)
            bitmap.getPixels(pix, 0, w, 0, 0, w, h)

            val wm = w - 1
            val hm = h - 1
            val wh = w * h
            val div = radius + radius + 1

            val r = IntArray(wh)
            val g = IntArray(wh)
            val b = IntArray(wh)
            var rsum: Int; var gsum: Int; var bsum: Int
            var rinsum: Int; var ginsum: Int; var binsum: Int
            var routsum: Int; var goutsum: Int; var boutsum: Int
            var p: Int; var yp: Int; var yi: Int

            val vmin = IntArray(w.coerceAtLeast(h))
            val divsum = (div + 1) shr 1
            val dv = IntArray(256 * divsum * divsum)
            for (i in dv.indices) {
                dv[i] = i / (divsum * divsum)
            }

            var stackpointer: Int
            var stackstart: Int
            var sir: IntArray
            var rbs: Int

            val stack = Array(div) { IntArray(3) }

            var r1 = radius + 1

            yi = 0
            for (y in 0 until h) {
                rsum = 0; gsum = 0; bsum = 0
                rinsum = 0; ginsum = 0; binsum = 0
                routsum = 0; goutsum = 0; boutsum = 0
                for (i in -radius..radius) {
                    p = pix[yi + i.coerceIn(0, wm)]
                    sir = stack[i + radius]
                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = (p and 0x0000ff)
                    rbs = r1 - kotlin.math.abs(i)
                    rsum += sir[0] * rbs
                    gsum += sir[1] * rbs
                    bsum += sir[2] * rbs
                    if (i > 0) {
                        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    } else {
                        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    }
                }
                stackpointer = radius
                for (x in 0 until w) {
                    r[yi] = dv[rsum]
                    g[yi] = dv[gsum]
                    b[yi] = dv[bsum]

                    rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                    stackstart = stackpointer - radius + div
                    sir = stack[stackstart % div]
                    routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                    if (y == 0) vmin[x] = (x + radius + 1).coerceAtMost(wm)
                    p = pix[vmin[x] + y * w]  // Fixed: use y*w offset
                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = (p and 0x0000ff)
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    rsum += rinsum; gsum += ginsum; bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    sir = stack[stackpointer % div]
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                    yi++
                }
            }

            for (x in 0 until w) {
                rsum = 0; gsum = 0; bsum = 0
                rinsum = 0; ginsum = 0; binsum = 0
                routsum = 0; goutsum = 0; boutsum = 0
                yp = -radius * w
                for (i in -radius..radius) {
                    yi = 0.coerceAtLeast(yp) + x
                    sir = stack[i + radius]
                    sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                    rbs = r1 - kotlin.math.abs(i)
                    rsum += r[yi] * rbs
                    gsum += g[yi] * rbs
                    bsum += b[yi] * rbs
                    if (i > 0) {
                        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    } else {
                        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    }
                    if (i < hm) yp += w
                }
                yi = x
                stackpointer = radius
                for (y in 0 until h) {
                    pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                    rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                    stackstart = stackpointer - radius + div
                    sir = stack[stackstart % div]
                    routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                    if (x == 0) vmin[y] = (y + r1).coerceAtMost(hm) * w
                    p = x + vmin[y]
                    sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    rsum += rinsum; gsum += ginsum; bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    sir = stack[stackpointer]
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                    yi += w
                }
            }

            bitmap.setPixels(pix, 0, w, 0, 0, w, h)
            return bitmap
            } catch (e: Throwable) {
                return original
            }
        }
    }
}
