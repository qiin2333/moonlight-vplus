package com.limelight.perfhud

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.limelight.utils.AppDialogStyler
import com.limelight.perfhud.PerfHudPalette as P

/**
 * HUD 长按弹出的说明卡片：深色玻璃底、状态色光圈图标、右上角实时数值，
 * 贴着 HUD 弹出，只轻压暗串流画面，和 HUD 本体是同一套视觉语言。
 */
class PerfHudSheet(private val activity: Activity) {

    class Action(val label: String, val primary: Boolean = false, val onClick: (() -> Unit)? = null)

    class Spec(
        val title: String,
        val icon: String? = null,
        val accent: Int = P.MOON,
        val value: String? = null,
        val valueColor: Int? = null,
        val subtitle: String? = null,
        val blocks: List<SheetBlock> = emptyList(),
        val header: List<View> = emptyList(),
        val actions: List<Action> = emptyList()
    )

    private var current: Dialog? = null
    private val density = activity.resources.displayMetrics.density

    fun dismiss() {
        current?.dismiss()
        current = null
    }

    fun show(spec: Spec, anchor: View?) {
        dismiss()
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = buildCard(spec, dialog)
        val shadowPad = dp(12f)
        val root = FrameLayout(activity).apply {
            clipToPadding = false
            setPadding(shadowPad, shadowPad, shadowPad, shadowPad)
            addView(card)
        }
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        AppDialogStyler.installDismissKeys(dialog)

        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setWindowAnimations(0)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setDimAmount(DIM_AMOUNT)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility

        val fromAbove = place(window, card, shadowPad, anchor)

        dialog.setOnDismissListener { if (current === dialog) current = null }
        current = dialog
        dialog.show()
        animateIn(card, fromAbove)
    }

    /** 尽量贴着 HUD 放：上下哪边空间大放哪边，都不够就放侧边，没有锚点则居中。返回卡片是否从上方滑入。 */
    private fun place(window: Window, card: CardLayout, pad: Int, anchor: View?): Boolean {
        val decor = activity.window.decorView
        val screenW = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val screenH = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val margin = dp(12f)
        val gap = dp(8f)
        val cardW = minOf(dp(MAX_WIDTH_DP), screenW - 2 * margin)

        val lp = window.attributes
        lp.width = cardW + 2 * pad
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val visible = anchor != null && anchor.isShown && anchor.width > 0
        if (!visible) {
            card.maxHeight = screenH - 2 * margin
            lp.gravity = Gravity.CENTER
            window.attributes = lp
            return true
        }

        val loc = IntArray(2)
        anchor!!.getLocationOnScreen(loc)
        val aLeft = loc[0]
        val aTop = loc[1]
        val aRight = aLeft + anchor.width
        val aBottom = aTop + anchor.height

        val below = screenH - aBottom - gap - margin
        val above = aTop - gap - margin
        val minUseful = dp(MIN_USEFUL_HEIGHT_DP)
        var x: Int
        var y: Int
        val fromAbove: Boolean
        if (maxOf(below, above) >= minUseful) {
            val placeBelow = below >= above
            card.maxHeight = if (placeBelow) below else above
            val h = measure(card, cardW)
            x = (aLeft + aRight) / 2 - cardW / 2
            y = if (placeBelow) aBottom + gap else aTop - gap - h
            fromAbove = placeBelow
        } else {
            card.maxHeight = screenH - 2 * margin
            val h = measure(card, cardW)
            val right = screenW - aRight - gap - margin
            x = if (right >= cardW) aRight + gap else aLeft - gap - cardW
            y = (aTop + aBottom) / 2 - h / 2
            fromAbove = true
        }
        x = x.coerceIn(margin, maxOf(margin, screenW - margin - cardW))
        y = y.coerceIn(margin, maxOf(margin, screenH - margin - card.measuredHeight))

        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x - pad
        lp.y = y - pad
        window.attributes = lp
        return fromAbove
    }

    private fun measure(card: View, width: Int): Int {
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return card.measuredHeight
    }

    private fun animateIn(card: View, fromAbove: Boolean) {
        card.alpha = 0f
        card.scaleX = 0.97f
        card.scaleY = 0.97f
        card.translationY = (if (fromAbove) -1 else 1) * dp(8f).toFloat()
        card.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    // ---- 卡片结构 ----

    private fun buildCard(spec: Spec, dialog: Dialog): CardLayout {
        val card = CardLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(P.withAlpha(P.BG_RGB, 0xF2))
                cornerRadius = dp(20f).toFloat()
                setStroke(dp(1f), P.DIVIDER)
            }
            elevation = dp(14f).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        card.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        card.addView(View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(spec.accent, P.withAlpha(spec.accent, 0x40), Color.TRANSPARENT)
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2f)))

        card.addView(buildHeader(spec))
        card.addView(hairline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply {
            leftMargin = dp(18f); rightMargin = dp(18f)
        })

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(6f))
        }
        spec.header.forEach { body.addView(it) }
        renderBlocks(body, spec.blocks, spec.accent)
        card.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(buildActions(spec, dialog))
        return card
    }

    private fun buildHeader(spec: Spec): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18f), dp(14f), dp(18f), dp(12f))

        if (spec.icon != null) {
            addView(TextView(activity).apply {
                text = spec.icon
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(P.withAlpha(spec.accent, 0x30))
                    setStroke(dp(1.5f), P.withAlpha(spec.accent, 0xCC))
                }
            }, LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { rightMargin = dp(12f) })
        }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(spec.title, P.TXT, 17f, bold = true))
            if (!spec.subtitle.isNullOrBlank()) {
                addView(text(spec.subtitle, P.DIM, 12.5f).apply {
                    setPadding(0, dp(3f), 0, 0)
                    maxLines = 2
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!spec.value.isNullOrBlank()) {
            addView(text(spec.value, spec.valueColor ?: spec.accent, 18f, bold = true).apply {
                fontFeatureSettings = "tnum"
                setPadding(dp(10f), 0, 0, 0)
                maxLines = 1
            })
        }
    }

    private fun buildActions(spec: Spec, dialog: Dialog): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f), dp(6f), dp(14f), dp(14f))
        val (primary, secondary) = spec.actions.partition { it.primary }
        secondary.forEach { addView(pill(it, dialog, spec.accent), pillParams()) }
        addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))
        primary.forEach { addView(pill(it, dialog, spec.accent), pillParams()) }
    }

    private fun pillParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)).apply {
        leftMargin = dp(4f); rightMargin = dp(4f)
    }

    private fun pill(action: Action, dialog: Dialog, accent: Int): View = text(
        action.label,
        if (action.primary) accent else P.TXT,
        14f,
        bold = action.primary
    ).apply {
        gravity = Gravity.CENTER
        minWidth = dp(76f)
        setPadding(dp(18f), 0, dp(18f), 0)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            if (action.primary) {
                setColor(P.withAlpha(accent, 0x2E))
                setStroke(dp(1f), P.withAlpha(accent, 0x80))
            } else {
                setColor(P.TILE)
                setStroke(dp(1f), P.HAIRLINE)
            }
        }
        background = ripple(shape)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            dialog.dismiss()
            action.onClick?.invoke()
        }
    }

    // ---- 正文块 ----

    private fun renderBlocks(parent: LinearLayout, blocks: List<SheetBlock>, accent: Int) {
        var table: LinearLayout? = null
        for (block in blocks) {
            if (block !is SheetBlock.Row) table = null
            when (block) {
                is SheetBlock.Row -> {
                    val t = table ?: newTable().also {
                        table = it
                        parent.addView(it, topMargin(parent, 4f))
                    }
                    if (t.childCount > 0) t.addView(hairline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)))
                    t.addView(row(block))
                }
                is SheetBlock.Paragraph -> parent.addView(
                    text(block.text, P.BODY, 14f).apply { setLineSpacing(0f, 1.15f) },
                    topMargin(parent, 2f)
                )
                is SheetBlock.Heading -> parent.addView(heading(block, accent), topMargin(parent, 10f))
                is SheetBlock.Bullet -> parent.addView(bullet(block.text, accent), topMargin(parent, 3f))
                is SheetBlock.Tip -> parent.addView(text(block.text, P.DIM, 12.5f).apply {
                    setLineSpacing(0f, 1.12f)
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12f).toFloat()
                        setColor(P.withAlpha(accent, 0x14))
                        setStroke(dp(1f), P.withAlpha(accent, 0x2A))
                    }
                }, topMargin(parent, 8f))
                SheetBlock.Divider -> parent.addView(hairline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                    topMargin = dp(8f); bottomMargin = dp(4f)
                })
                SheetBlock.Gap -> parent.addView(Space(activity), LinearLayout.LayoutParams(1, dp(8f)))
            }
        }
    }

    private fun heading(h: SheetBlock.Heading, accent: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(text(h.text, accent, 13f, bold = true).apply { letterSpacing = 0.03f })
        if (!h.caption.isNullOrBlank()) {
            addView(text(h.caption, P.TXT, 13f).apply { setPadding(dp(8f), 0, 0, 0) })
        }
    }

    private fun newTable() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12f), dp(2f), dp(12f), dp(2f))
        background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(P.TILE)
        }
    }

    private fun row(r: SheetBlock.Row): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(34f)
        if (r.swatch != null) {
            addView(View(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2f).toFloat()
                    setColor(r.swatch)
                }
            }, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(10f) })
        }
        addView(text(r.key, P.DIM, 13.5f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(r.value, r.valueColor ?: P.TXT, 14f, bold = true).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            setPadding(dp(12f), 0, 0, 0)
        })
    }

    private fun bullet(value: String, accent: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(P.withAlpha(accent, 0xB0))
            }
        }, LinearLayout.LayoutParams(dp(5f), dp(5f)).apply {
            topMargin = dp(8f); leftMargin = dp(2f); rightMargin = dp(10f)
        })
        addView(text(value, P.DIM, 13.5f).apply { setLineSpacing(0f, 1.1f) })
    }

    // ---- 小工具 ----

    private fun text(value: CharSequence, color: Int, sp: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun hairline() = View(activity).apply { setBackgroundColor(P.HAIRLINE) }

    private fun topMargin(parent: ViewGroup, value: Float) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (parent.childCount > 0) topMargin = dp(value)
        }

    private fun ripple(content: Drawable) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    /** 带最大高度的卡片：超出时压缩带权重的正文 ScrollView，头部和按钮始终可见。 */
    class CardLayout(context: android.content.Context) : LinearLayout(context) {
        var maxHeight: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        }
    }

    /** 延迟链分段条：按耗时比例画网络 / 主机 / 解码 / 渲染四段，配色与 HUD 一致。 */
    class ChainBarView(context: android.content.Context, private val parts: List<Pair<Float, Int>>) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val d = context.resources.displayMetrics.density

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (10 * d).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val total = parts.sumOf { it.first.toDouble() }.toFloat()
            val r = height / 2f
            paint.color = P.TRACK
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            if (total <= 0f) return
            val gap = 2 * d
            var x = 0f
            val visible = parts.filter { it.first > 0f }
            visible.forEachIndexed { i, (value, color) ->
                val w = width * value / total
                paint.color = color
                rect.set(x, 0f, (x + w - if (i < visible.lastIndex) gap else 0f).coerceAtLeast(x + d), height.toFloat())
                canvas.drawRoundRect(rect, r, r, paint)
                x += w
            }
        }
    }

    private companion object {
        const val MAX_WIDTH_DP = 360f
        const val MIN_USEFUL_HEIGHT_DP = 220f
        const val DIM_AMOUNT = 0.32f
    }
}
