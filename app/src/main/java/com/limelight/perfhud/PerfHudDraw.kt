package com.limelight.perfhud

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** HUD 本体和说明卡片共用的小图形。 */
internal object PerfHudDraw {

    /** 延迟链分段条；[gap] 是段间留缝，[minSeg] 让很小的段也看得见。 */
    fun chainBar(
        c: Canvas, paint: Paint, rect: RectF,
        left: Float, top: Float, width: Float, height: Float,
        segments: List<ChainSegment>, track: Int, gap: Float = 0f, minSeg: Float = 0f
    ) {
        val r = height / 2
        paint.color = track
        rect.set(left, top, left + width, top + height)
        c.drawRoundRect(rect, r, r, paint)

        val visible = segments.filter { it.ms > 0f }
        val total = visible.sumOf { it.ms.toDouble() }.toFloat()
        if (total <= 0f) return
        c.save()
        c.clipRect(left, top, left + width, top + height)
        var x = left
        visible.forEachIndexed { i, seg ->
            val segW = width * seg.ms / total
            val end = if (i < visible.lastIndex) x + segW - gap else x + segW
            paint.color = seg.color
            rect.set(x, top, maxOf(end, x + minSeg), top + height)
            c.drawRoundRect(rect, r, r, paint)
            x += segW
        }
        c.restore()
    }

    /** 帧率历史折线，最新点贴右边；返回起点 x，不足两个点时返回 NaN。 */
    fun sparkPath(
        path: Path, history: FloatRing, slots: Int,
        left: Float, top: Float, width: Float, height: Float, ceiling: Float
    ): Float {
        val n = history.size
        if (n < 2) return Float.NaN
        val bottom = top + height
        val step = width / (slots - 1)
        val startX = left + width - step * (n - 1)
        path.reset()
        for (i in 0 until n) {
            val px = startX + step * i
            val py = bottom - height * (history[i] / ceiling)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        return startX
    }
}
