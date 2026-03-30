package com.limelight.utils

import android.content.Context
import android.graphics.Bitmap
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView

import com.limelight.R

/**
 * 背景图片管理器，用于处理AppView背景图片的平滑切换
 * 支持高斯模糊背景 + 中间完整图片的显示模式
 */
class BackgroundImageManager(
    private val context: Context,
    private val blurImageView: ImageView,
    private val clearImageView: ImageView
) {
    var currentBackground: Bitmap? = null
        private set

    /**
     * 平滑地切换到新的背景图片
     * @param newBackground 新的背景图片
     */
    fun setBackgroundSmoothly(newBackground: Bitmap?) {
        if (newBackground == null) {
            return
        }

        // 如果背景图片相同，不需要切换
        if (currentBackground == newBackground) {
            return
        }

        val blurredBitmap = stackBlur(newBackground, 10)

        // 如果当前没有背景图片，直接设置
        if (currentBackground == null) {
            currentBackground = newBackground
            blurImageView.setImageBitmap(blurredBitmap)
            clearImageView.setImageBitmap(newBackground)
            val fadeIn = AnimationUtils.loadAnimation(context, R.anim.background_fadein)
            blurImageView.startAnimation(fadeIn)
            clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadein))
            return
        }

        // 执行平滑切换动画
        val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout)
        fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                currentBackground = newBackground
                blurImageView.setImageBitmap(blurredBitmap)
                clearImageView.setImageBitmap(newBackground)
                val fadeIn = AnimationUtils.loadAnimation(context, R.anim.background_fadein)
                blurImageView.startAnimation(fadeIn)
                clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadein))
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })

        blurImageView.startAnimation(fadeOutAnimation)
        clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadeout))
    }

    /**
     * 清除背景图片
     */
    fun clearBackground() {
        if (currentBackground != null) {
            val fadeOutAnimation = AnimationUtils.loadAnimation(context, R.anim.background_fadeout)
            fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}

                override fun onAnimationEnd(animation: Animation) {
                    blurImageView.setImageBitmap(null)
                    clearImageView.setImageBitmap(null)
                    currentBackground = null
                }

                override fun onAnimationRepeat(animation: Animation) {}
            })

            blurImageView.startAnimation(fadeOutAnimation)
            clearImageView.startAnimation(AnimationUtils.loadAnimation(context, R.anim.background_fadeout))
        }
    }

    companion object {
        /**
         * StackBlur 算法 - 对缩小后的图片进行模糊处理以提升性能
         */
        @JvmStatic
        fun stackBlur(original: Bitmap, radius: Int): Bitmap {
            // 缩小图片后模糊（1/3缩放保留较多细节）
            val scaleFactor = 3
            val smallWidth = (original.width / scaleFactor).coerceAtLeast(1)
            val smallHeight = (original.height / scaleFactor).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(original, smallWidth, smallHeight, true)

            val bitmap = small.copy(Bitmap.Config.ARGB_8888, true)
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
        }
    }
}
