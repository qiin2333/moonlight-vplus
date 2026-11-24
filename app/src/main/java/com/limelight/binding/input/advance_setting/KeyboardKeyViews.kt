package com.limelight.binding.input.advance_setting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.limelight.R
import kotlin.math.min
import kotlin.math.roundToInt

internal class KeyboardKeyView(context: Context) : View(context) {
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }

    private var spec: KeySpec? = null
    val keySpec: KeySpec? get() = spec
    private var modifierState = 0
    private var baseTextSp = 11f

    private val primaryColor = ContextCompat.getColor(context, R.color.crown_text_primary)
    private val secondaryColor = ContextCompat.getColor(context, R.color.crown_text_secondary)
    private val onAccentColor = ContextCompat.getColor(context, R.color.crown_on_accent)
    private val accentColor = resolveAccentColor(context)
    private val density = resources.displayMetrics.density
    private val paddingPx = 3f * density

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    fun bind(newSpec: KeySpec, keyHeight: Float, pageListener: ((KeyboardPage) -> Unit)?) {
        spec = newSpec
        val heightDp = keyHeight / density
        baseTextSp = (heightDp * 0.34f).coerceIn(10f, 16f)
        contentDescription = newSpec.label.ifEmpty { newSpec.page?.name?.lowercase() ?: "" }
        updateBackground()
        val listener = pageListener
        if (newSpec.page != null && listener != null) {
            setOnClickListener { newSpec.page?.let(listener) }
        }
        invalidate()
    }

    fun setModifierState(state: Int) {
        if (modifierState == state) return
        modifierState = state
        updateBackground()
        invalidate()
    }

    private fun updateBackground() {
        val current = spec ?: return
        val res = when {
            current.kind == KeyKind.ACCENT -> R.drawable.keyboard_key_accent_selector
            current.kind == KeyKind.MODIFIER -> when (modifierState) {
                1 -> R.drawable.keyboard_modifier_single_selector
                2 -> R.drawable.keyboard_modifier_locked_selector
                else -> R.drawable.keyboard_modifier_refined_selector
            }
            else -> R.drawable.keyboard_key_refined_selector
        }
        setBackgroundResource(res)
        refreshDrawableState()
    }

    private fun textColor(current: KeySpec): Int = when {
        current.kind == KeyKind.MODIFIER -> when (modifierState) {
            1 -> accentColor
            2 -> onAccentColor
            else -> secondaryColor
        }
        current.kind == KeyKind.ACCENT -> accentColor
        current.kind == KeyKind.SPECIAL -> secondaryColor
        current.kind == KeyKind.SPACE -> secondaryColor
        else -> primaryColor
    }

    private fun secondaryTextColor(current: KeySpec): Int = when (current.kind) {
        KeyKind.MODIFIER -> when (modifierState) {
            1 -> accentColor
            2 -> onAccentColor
            else -> secondaryColor
        }
        else -> if (modifierState == 2) onAccentColor else if (modifierState == 1) accentColor else secondaryColor
    }

    private fun textSizePx(current: KeySpec): Float {
        val factor = when (current.kind) {
            KeyKind.SPECIAL -> 0.82f
            KeyKind.MODIFIER -> 0.86f
            KeyKind.ACCENT -> 0.9f
            KeyKind.SPACE -> 0.82f
            KeyKind.NORMAL -> 1f
        }
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            baseTextSp * factor,
            resources.displayMetrics
        )
    }

    private fun fitText(text: String, paint: Paint, startPx: Float, maxWidth: Float): Float {
        paint.textSize = startPx
        val measured = paint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) return startPx
        return (startPx * maxWidth / measured).coerceAtLeast(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = spec ?: return
        if (width <= 0 || height <= 0) return
        val maxWidth = (width - paddingPx * 2f).coerceAtLeast(1f)
        val tc = textColor(current)
        primaryPaint.color = tc
        val primaryText = when (current.kind) {
            KeyKind.SPACE -> "space"
            else -> current.label
        }
        if (primaryText.isNotEmpty()) {
            val size = fitText(primaryText, primaryPaint, textSizePx(current), maxWidth)
            primaryPaint.textSize = size
            val baseline = height / 2f - (primaryPaint.ascent() + primaryPaint.descent()) / 2f
            canvas.drawText(primaryText, width / 2f, baseline, primaryPaint)
        }
        val secondary = current.secondary
        if (!secondary.isNullOrEmpty()) {
            secondaryPaint.color = secondaryTextColor(current)
            val start = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                (baseTextSp * 0.58f).coerceAtLeast(6.5f),
                resources.displayMetrics
            )
            val size = fitText(secondary, secondaryPaint, start, maxWidth * 0.45f)
            secondaryPaint.textSize = size
            canvas.drawText(secondary, width - paddingPx, paddingPx + size, secondaryPaint)
        }
    }

    private companion object {
        fun resolveAccentColor(context: Context): Int {
            val value = TypedValue()
            return if (context.theme.resolveAttribute(R.attr.appAccent, value, true)) {
                value.data
            } else {
                ContextCompat.getColor(context, R.color.ui_shell_accent)
            }
        }
    }
}

internal class KeyboardRowView(context: Context) : ViewGroup(context) {
    private val keyViews = ArrayList<KeyboardKeyView>()
    private val weights = ArrayList<Float>()
    private var keyHeightPx = 0f
    private var gapPx = 0f

    fun bind(keys: List<KeySpec>, keyHeight: Float, gap: Float, pageListener: (KeyboardPage) -> Unit) {
        removeAllViews()
        keyViews.clear()
        weights.clear()
        keyHeightPx = keyHeight
        gapPx = gap
        keys.forEach { spec ->
            val key = KeyboardKeyView(context)
            key.bind(spec, keyHeight, pageListener)
            keyViews.add(key)
            weights.add(spec.weight.coerceAtLeast(0.1f))
            addView(key)
        }
    }

    fun keys(): List<KeyboardKeyView> = keyViews

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val gapTotal = gapPx * (keyViews.size - 1).coerceAtLeast(0)
        val available = (width - gapTotal).coerceAtLeast(1f)
        val totalWeight = weights.sum().coerceAtLeast(0.1f)
        var usedWidth = 0
        keyViews.forEachIndexed { index, child ->
            val childWidth = if (index == keyViews.lastIndex) {
                (width - gapTotal - usedWidth).roundToInt().coerceAtLeast(1)
            } else {
                (available * weights[index] / totalWeight).roundToInt().coerceAtLeast(1)
            }
            usedWidth += childWidth
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(keyHeightPx.roundToInt(), MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, keyHeightPx.roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0f
        keyViews.forEach { child ->
            val childWidth = child.measuredWidth.toFloat()
            child.layout(x.roundToInt(), 0, (x + childWidth).roundToInt(), keyHeightPx.roundToInt())
            x += childWidth + gapPx
        }
    }
}

internal class KeyboardNavGridView(context: Context) : ViewGroup(context) {
    private val keyViews = ArrayList<KeyboardKeyView>()
    private val cells = ArrayList<View>()
    private var keyWidthPx = 0f
    private var keyHeightPx = 0f
    private var gapPx = 0f
    private var verticalGapPx = 0f

    fun bind(cellSpecs: List<KeySpec?>, keyWidth: Float, keyHeight: Float, gap: Float) {
        removeAllViews()
        keyViews.clear()
        cells.clear()
        keyWidthPx = keyWidth
        keyHeightPx = keyHeight.roundToInt().toFloat()
        gapPx = gap
        verticalGapPx = gap.roundToInt().toFloat()
        cellSpecs.forEach { spec ->
            if (spec == null) {
                val blank = View(context)
                blank.isFocusable = false
                blank.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                cells.add(blank)
                addView(blank)
            } else {
                val key = KeyboardKeyView(context)
                key.bind(spec, keyHeight, null)
                keyViews.add(key)
                cells.add(key)
                addView(key)
            }
        }
    }

    fun keys(): List<KeyboardKeyView> = keyViews

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        cells.forEach { child ->
            child.measure(
                MeasureSpec.makeMeasureSpec(keyWidthPx.roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(keyHeightPx.roundToInt(), MeasureSpec.EXACTLY)
            )
        }
        val width = (keyWidthPx * 3f + gapPx * 2f).roundToInt()
        val height = (keyHeightPx * 5f + verticalGapPx * 4f).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        cells.forEachIndexed { index, child ->
            val column = index % 3
            val row = index / 3
            val x = (column * (keyWidthPx + gapPx)).roundToInt()
            val y = (row * (keyHeightPx + verticalGapPx)).roundToInt()
            child.layout(x, y, x + keyWidthPx.roundToInt(), y + keyHeightPx.roundToInt())
        }
    }
}


internal class KeyboardGridView(context: Context) : ViewGroup(context) {
    private val keyViews = ArrayList<KeyboardKeyView>()
    private val cells = ArrayList<KeyboardGridCell>()
    private var columns = 3
    private var rows = 5
    private var keyWidthPx = 0f
    private var keyHeightPx = 0f
    private var gapPx = 0f
    private var verticalGapPx = 0f

    fun bind(
        newColumns: Int,
        newRows: Int,
        cellSpecs: List<KeyboardGridCell>,
        keyWidth: Float,
        keyHeight: Float,
        gap: Float,
        pageListener: (KeyboardPage) -> Unit
    ) {
        removeAllViews()
        keyViews.clear()
        cells.clear()
        columns = newColumns
        rows = newRows
        keyWidthPx = keyWidth
        keyHeightPx = keyHeight.roundToInt().toFloat()
        gapPx = gap
        verticalGapPx = gap.roundToInt().toFloat()
        cellSpecs.forEach { cell ->
            cells.add(cell)
            val spec = cell.spec
            if (spec == null) {
                val blank = View(context)
                blank.isFocusable = false
                blank.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(blank)
            } else {
                val key = KeyboardKeyView(context)
                key.bind(spec, keyHeight, pageListener)
                keyViews.add(key)
                addView(key)
            }
        }
    }

    fun keys(): List<KeyboardKeyView> = keyViews

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        cells.forEachIndexed { index, cell ->
            val child = getChildAt(index)
            val childWidth = (cell.columnSpan * keyWidthPx + (cell.columnSpan - 1) * gapPx).roundToInt()
            val childHeight = (cell.rowSpan * keyHeightPx + (cell.rowSpan - 1) * verticalGapPx).roundToInt()
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
            )
        }
        val width = (columns * keyWidthPx + (columns - 1) * gapPx).roundToInt()
        val height = (rows * keyHeightPx + (rows - 1) * verticalGapPx).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        cells.forEachIndexed { index, cell ->
            val child = getChildAt(index)
            val x = ((cell.column - 1) * (keyWidthPx + gapPx)).roundToInt()
            val y = ((cell.row - 1) * (keyHeightPx + verticalGapPx)).roundToInt()
            val width = (cell.columnSpan * keyWidthPx + (cell.columnSpan - 1) * gapPx).roundToInt()
            val height = (cell.rowSpan * keyHeightPx + (cell.rowSpan - 1) * verticalGapPx).roundToInt()
            child.layout(x, y, x + width, y + height)
        }
    }
}

class KeyboardPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {
    private var metrics: KeyboardMetrics? = null
    private val blockViews = ArrayList<View>()
    private val blockWidths = ArrayList<Int>()
    private var fieldWidthPx = 0f
    private var fieldHeightPx = 0f

    internal fun bind(spec: KeyboardPageSpec, newMetrics: KeyboardMetrics, onPage: (KeyboardPage) -> Unit) {
        metrics = newMetrics
        removeAllViews()
        blockViews.clear()
        blockWidths.clear()
        val gap = newMetrics.gapPx
        val blockGap = newMetrics.blockGapPx
        val blockCount = spec.blocks.size
        val internalGaps = (spec.units - blockCount) * gap + (blockCount - 1) * blockGap
        val available = (newMetrics.contentWidthPx - internalGaps).coerceAtLeast(1f)
        val pageKeyWidth = min(newMetrics.keyWidthPx, available / spec.units).coerceAtLeast(1f)
        val rowHeightPx = newMetrics.keyHeightPx.roundToInt()
        val gapInt = gap.roundToInt()
        fieldHeightPx = (spec.rows * rowHeightPx + (spec.rows - 1) * gapInt).toFloat()
        var totalWidth = 0f

        spec.blocks.forEach { block ->
            val units = when (block) {
                is KeyboardBlockSpec.Rows -> block.units
                is KeyboardBlockSpec.Grid -> block.units
            }
            val blockWidth = units * pageKeyWidth + (units - 1) * gap
            when (block) {
                is KeyboardBlockSpec.Rows -> {
                    val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    block.rows.forEachIndexed { index, rowSpec ->
                        val rowView = KeyboardRowView(context)
                        rowView.bind(rowSpec.keys, rowHeightPx.toFloat(), gap, onPage)
                        val params = LinearLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            rowHeightPx
                        )
                        if (index < block.rows.size - 1) params.bottomMargin = gapInt
                        container.addView(rowView, params)
                    }
                    addView(container, LayoutParams(blockWidth.roundToInt(), fieldHeightPx.roundToInt()))
                    blockViews.add(container)
                }
                is KeyboardBlockSpec.Grid -> {
                    val grid = KeyboardGridView(context)
                    grid.bind(
                        block.columns,
                        block.rows,
                        block.cells,
                        pageKeyWidth,
                        rowHeightPx.toFloat(),
                        gap,
                        onPage
                    )
                    addView(grid, LayoutParams(blockWidth.roundToInt(), fieldHeightPx.roundToInt()))
                    blockViews.add(grid)
                }
            }
            blockWidths.add(blockWidth.roundToInt())
            totalWidth += blockWidth
        }
        fieldWidthPx = totalWidth + (blockCount - 1) * blockGap
    }

    internal fun keys(): List<KeyboardKeyView> {
        val result = ArrayList<KeyboardKeyView>()
        blockViews.forEach { block ->
            when (block) {
                is LinearLayout -> {
                    for (i in 0 until block.childCount) {
                        (block.getChildAt(i) as? KeyboardRowView)?.let { result.addAll(it.keys()) }
                    }
                }
                is KeyboardGridView -> result.addAll(block.keys())
            }
        }
        return result
    }

    internal fun findKey(code: Int): KeyboardKeyView? = keys().firstOrNull { it.keySpec?.code == code }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = fieldHeightPx.roundToInt()
        blockViews.forEachIndexed { index, block ->
            block.measure(
                MeasureSpec.makeMeasureSpec(blockWidths[index], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val blockGap = metrics?.blockGapPx ?: 0f
        var x = ((width - fieldWidthPx) / 2f).roundToInt().coerceAtLeast(0)
        blockViews.forEachIndexed { index, block ->
            block.layout(x, 0, x + blockWidths[index], fieldHeightPx.roundToInt())
            x += blockWidths[index] + blockGap.roundToInt()
        }
    }
}

class KeyboardMainPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {
    private var metrics: KeyboardMetrics? = null
    private var alphaBlock: LinearLayout? = null
    private var navGrid: KeyboardNavGridView? = null
    private var alphaWidthPx = 0f
    private var navWidthPx = 0f
    private var fieldWidthPx = 0f
    private var fieldHeightPx = 0f

    internal fun bind(newTier: KeyboardTier, newMetrics: KeyboardMetrics, onPage: (KeyboardPage) -> Unit) {
        metrics = newMetrics
        removeAllViews()
        alphaBlock = null
        navGrid = null
        val rows = KeyboardRows.rows(newTier)
        alphaWidthPx = newMetrics.alphaColumns * newMetrics.keyWidthPx +
            (newMetrics.alphaColumns - 1) * newMetrics.gapPx
        navWidthPx = if (newMetrics.navColumns > 0) {
            newMetrics.navColumns * newMetrics.keyWidthPx +
                (newMetrics.navColumns - 1) * newMetrics.gapPx
        } else {
            0f
        }
        fieldWidthPx = alphaWidthPx + if (navWidthPx > 0f) newMetrics.blockGapPx + navWidthPx else 0f
        val rowHeightPx = newMetrics.keyHeightPx.roundToInt()
        val gapInt = newMetrics.gapPx.roundToInt()
        fieldHeightPx = (rows.size * rowHeightPx + (rows.size - 1) * gapInt).toFloat()

        val block = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { index, rowSpec ->
            val rowView = KeyboardRowView(context)
            rowView.bind(rowSpec.keys, rowHeightPx.toFloat(), newMetrics.gapPx, onPage)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rowHeightPx
            )
            if (index < rows.size - 1) params.bottomMargin = gapInt
            block.addView(rowView, params)
        }
        addView(block, ViewGroup.LayoutParams(alphaWidthPx.roundToInt(), fieldHeightPx.roundToInt()))
        alphaBlock = block

        if (newMetrics.navColumns > 0) {
            val grid = KeyboardNavGridView(context)
            grid.bind(KeyboardRows.navCells(), newMetrics.keyWidthPx, newMetrics.keyHeightPx, newMetrics.gapPx)
            addView(grid, ViewGroup.LayoutParams(navWidthPx.roundToInt(), fieldHeightPx.roundToInt()))
            navGrid = grid
        }
        requestLayout()
    }

    internal fun keys(): List<KeyboardKeyView> {
        val result = ArrayList<KeyboardKeyView>()
        alphaBlock?.let { block ->
            for (i in 0 until block.childCount) {
                (block.getChildAt(i) as? KeyboardRowView)?.let { result.addAll(it.keys()) }
            }
        }
        navGrid?.let { result.addAll(it.keys()) }
        return result
    }

    internal fun findKey(code: Int): KeyboardKeyView? = keys().firstOrNull { it.keySpec?.code == code }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = fieldHeightPx.roundToInt()
        alphaBlock?.measure(
            MeasureSpec.makeMeasureSpec(alphaWidthPx.roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        navGrid?.measure(
            MeasureSpec.makeMeasureSpec(navWidthPx.roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = ((width - fieldWidthPx) / 2f).roundToInt().coerceAtLeast(0)
        alphaBlock?.layout(left, 0, left + alphaWidthPx.roundToInt(), fieldHeightPx.roundToInt())
        navGrid?.let {
            val navLeft = left + alphaWidthPx.roundToInt() + (metrics?.blockGapPx ?: 0f).roundToInt()
            it.layout(navLeft, 0, navLeft + navWidthPx.roundToInt(), fieldHeightPx.roundToInt())
        }
    }
}

class KeyboardGripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crown_text_secondary)
        alpha = 180
    }

    init {
        contentDescription = context.getString(R.string.keyboard_drag_handle)
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 1.15f * resources.displayMetrics.density
        val gap = 4.2f * resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height / 2f
        for (column in 0..1) {
            for (row in 0..2) {
                val x = centerX + (column - 0.5f) * gap
                val y = centerY + (row - 1f) * gap
                canvas.drawCircle(x, y, radius, dotPaint)
            }
        }
    }
}