package com.limelight.perfhud

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** HUD 本体和说明卡片共用的小图形，只负责几何与绘制，不持有状态。 */
internal object PerfHudDraw {

    /**
     * 延迟链分段条：先画底轨，再按占比画出各段。
     * @param gap 相邻段之间留的缝；为 0 时各段紧贴。
     * @param minSeg 很小的段至少画这么宽，避免完全看不见。
     */
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

    /**
     * 把帧率历史写成折线：最新的点贴右边，[slots] 个点铺满整个宽度。
     * @return 折线起点的 x；点数不足两个时返回 NaN，[path] 不可用。
     */
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
