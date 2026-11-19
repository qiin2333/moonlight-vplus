package com.limelight.binding.input.advance_setting.element

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputFilter
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

import com.limelight.R
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

abstract class Element(attributesMap: Map<String, Any>, elementController: ElementController, context: Context) : View(context) {

    companion object {
        const val COLUMN_LONG_CONFIG_ID = "config_id"
        const val COLUMN_LONG_ELEMENT_ID = "element_id"
        const val COLUMN_INT_ELEMENT_TYPE = "element_type"
        const val COLUMN_STRING_ELEMENT_VALUE = "element_value"
        const val COLUMN_STRING_ELEMENT_MIDDLE_VALUE = "element_middle_value"
        const val COLUMN_STRING_ELEMENT_UP_VALUE = "element_up_value"
        const val COLUMN_STRING_ELEMENT_DOWN_VALUE = "element_down_value"
        const val COLUMN_STRING_ELEMENT_LEFT_VALUE = "element_left_value"
        const val COLUMN_STRING_ELEMENT_RIGHT_VALUE = "element_right_value"
        const val COLUMN_STRING_ELEMENT_TEXT = "element_text"
        const val COLUMN_INT_ELEMENT_WIDTH = "element_width"
        const val COLUMN_INT_ELEMENT_HEIGHT = "element_height"
        const val COLUMN_INT_ELEMENT_LAYER = "element_layer"
        const val COLUMN_INT_ELEMENT_MODE = "element_mode"
        const val COLUMN_INT_ELEMENT_SENSE = "element_sense"
        const val COLUMN_INT_ELEMENT_CENTRAL_X = "element_central_x"
        const val COLUMN_INT_ELEMENT_CENTRAL_Y = "element_central_y"
        const val COLUMN_INT_ELEMENT_RADIUS = "element_radius"
        const val COLUMN_INT_ELEMENT_OPACITY = "element_opacity"
        const val COLUMN_INT_ELEMENT_THICK = "element_thick"
        const val COLUMN_INT_ELEMENT_NORMAL_COLOR = "element_color"
        const val COLUMN_INT_ELEMENT_PRESSED_COLOR = "element_pressed_color"
        const val COLUMN_INT_ELEMENT_BACKGROUND_COLOR = "element_background_color"
        const val COLUMN_INT_ELEMENT_FLAG1 = "element_flag1"

        const val COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR = "normalTextColor"
        const val COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR = "pressedTextColor"
        const val COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT = "textSizePercent"
        const val COLUMN_STRING_EXTRA_ATTRIBUTES = "extra_attributes"

        const val ELEMENT_TYPE_DIGITAL_COMMON_BUTTON = 0
        const val ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON = 1
        const val ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON = 2
        const val ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON = 3
        const val ELEMENT_TYPE_GROUP_BUTTON = 4
        const val ELEMENT_TYPE_DIGITAL_PAD = 20
        const val ELEMENT_TYPE_ANALOG_STICK = 30
        const val ELEMENT_TYPE_DIGITAL_STICK = 31
        const val ELEMENT_TYPE_INVISIBLE_ANALOG_STICK = 32
        const val ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK = 33
        const val ELEMENT_TYPE_SIMPLIFY_PERFORMANCE = 50

        const val EDIT_COLOR_EDIT = 0xf0dc143c.toInt()
        const val EDIT_COLOR_SELECT = 0xfffe9900.toInt()
        const val EDIT_COLOR_SELECTED = 0xff0112ff.toInt()
        const val ELEMENT_TYPE_WHEEL_PAD = 54

        // 在编辑模式下，如果元素被按住超过系统定义的长按时间，就允许拖动，
        // 以避免用户想打开按键设置而不是移动按键
        private const val DRAG_EDIT_LONG_PRESS_TIMEOUT = 250L
    }

    private var longPressDetected = false
    private val longPressRunnable: Runnable = object : Runnable {
        override fun run() {
            longPressDetected = true
            // 可以添加视觉反馈，比如改变边框颜色
            editColor = 0xff00f91a.toInt() // 绿色表示可以拖动
            invalidate()
        }
    }

    fun interface ElementSelectedCallBack {
        fun elementSelected(element: Element)
    }

    protected inner class HexInputFilter : InputFilter {
        override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
            for (i in start until end) {
                if (!Character.isDigit(source[i]) && (source[i] < 'A' || source[i] > 'F')) {
                    return ""
                }
            }
            return null
        }
    }

    @JvmField
    val elementId: Long? = attributesMap.get(Element.COLUMN_LONG_ELEMENT_ID) as Long?

    @JvmField
    val configId: Long? = attributesMap.get(Element.COLUMN_LONG_CONFIG_ID) as Long?

    @JvmField
    val elementType: Int = (attributesMap.get(Element.COLUMN_INT_ELEMENT_TYPE) as Long).toInt()

    @JvmField
    var layer: Int = (attributesMap.get(COLUMN_INT_ELEMENT_LAYER) as Long).toInt()

    @JvmField
    protected val elementController: ElementController = elementController

    private val paint = Paint()
    private val rect = RectF()
    private val editSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastLabelWidth = -1
    private var lastLabelHeight = -1
    private var sizeLabel = ""

    @JvmField
    var centralXMax = 0

    @JvmField
    var centralXMin = 0

    @JvmField
    var centralYMax = 0

    @JvmField
    var centralYMin = 0

    @JvmField
    protected var widthMax = 0

    @JvmField
    protected var widthMin = 0

    @JvmField
    protected var heightMax = 0

    @JvmField
    protected var heightMin = 0

    private var lastX = 0f
    private var lastY = 0f
    private var isClick = true

    @JvmField
    var editColor = EDIT_COLOR_EDIT

    private var elementSelectedCallBack: ElementSelectedCallBack? = null

    fun getElementCentralX(): Int {
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        return layoutParams.leftMargin + layoutParams.width / 2
    }

    fun getElementCentralY(): Int {
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        return layoutParams.topMargin + layoutParams.height / 2
    }

    fun getElementWidth(): Int {
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        return layoutParams.width
    }

    fun getElementHeight(): Int {
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        return layoutParams.height
    }

    open fun setElementCentralX(centralX: Int) {
        var centralX = centralX
        centralX = elementController.editGridHandle(centralX)
        if (centralX > centralXMax) {
            centralX = elementController.editGridHandle(centralXMax)
        }
        innerSetElementCentralX(centralX)
    }

    open fun setElementCentralY(centralY: Int) {
        var centralY = centralY
        centralY = elementController.editGridHandle(centralY)
        if (centralY > centralYMax) {
            centralY = elementController.editGridHandle(centralYMax)
        }
        innerSetElementCentralY(centralY)
    }

    //inner 方法防止setWidth、Height方法会调用子类重写的setElementCentralX、Y
    private fun innerSetElementCentralX(centralX: Int) {
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        if (centralX > centralXMax) {
            layoutParams.leftMargin = centralXMax - layoutParams.width / 2
        } else if (centralX < centralXMin) {
            layoutParams.leftMargin = centralXMin - layoutParams.width / 2
        } else {
            layoutParams.leftMargin = centralX - layoutParams.width / 2
        }
        //保存中心点坐标
        requestLayout()
    }

    private fun innerSetElementCentralY(centralY: Int) {
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        if (centralY > centralYMax) {
            layoutParams.topMargin = centralYMax - layoutParams.height / 2
        } else if (centralY < centralYMin) {
            layoutParams.topMargin = centralYMin - layoutParams.height / 2
        } else {
            layoutParams.topMargin = centralY - layoutParams.height / 2
        }
        requestLayout()
    }

    open fun setElementWidth(width: Int) {
        val centralPosX = getElementCentralX()
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        if (width > widthMax) {
            layoutParams.width = widthMax
        } else if (width < widthMin) {
            layoutParams.width = widthMin
        } else {
            layoutParams.width = width
        }
        innerSetElementCentralX(centralPosX)
    }

    open fun setElementHeight(height: Int) {
        val centralPosY = getElementCentralY()
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        if (height > heightMax) {
            layoutParams.height = heightMax
        } else if (height < heightMin) {
            layoutParams.height = heightMin
        } else {
            layoutParams.height = height
        }
        innerSetElementCentralY(centralPosY)
    }

    open fun setElementLayer(layer: Int) {
        this.layer = layer
        elementController.adjustLayer(this)
    }

    fun setEditColor(editColor: Int) {
        this.editColor = editColor
    }

    fun setElementSelectedCallBack(elementSelectedCallBack: ElementSelectedCallBack?) {
        this.elementSelectedCallBack = elementSelectedCallBack
    }

    override fun onDraw(canvas: Canvas) {
        onElementDraw(canvas)
        if (elementController.getMode() === ElementController.Mode.Edit
                && elementController.isSelectedElement(this)) {
            drawEditSelection(canvas)
        }
        super.onDraw(canvas)
    }

    private fun drawEditSelection(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val inset = 1.5f * density
        val handleRadius = 4f * density
        val accent = resources.getColor(R.color.ui_shell_accent)

        val selection = editSelectionPaint
        selection.reset()
        selection.setAntiAlias(true)
        selection.style = Paint.Style.STROKE
        selection.strokeWidth = 1.5f * density
        selection.color = accent
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, selection)

        selection.style = Paint.Style.FILL
        selection.color = accent
        canvas.drawCircle(inset, inset, handleRadius, selection)
        canvas.drawCircle(width - inset, inset, handleRadius, selection)
        canvas.drawCircle(inset, height - inset, handleRadius, selection)
        canvas.drawCircle(width - inset, height - inset, handleRadius, selection)

        if (width >= 56f * density && height >= 32f * density) {
            selection.textSize = 9f * density
            selection.textAlign = Paint.Align.CENTER
            // Rebuild the label only when the size actually changes; drags
            // invalidate per frame and measureText is not free.
            if (lastLabelWidth != width || lastLabelHeight != height) {
                lastLabelWidth = width
                lastLabelHeight = height
                sizeLabel = "$width × $height"
            }
            val textY = Math.max(12f * density, height - 5f * density)
            selection.color = 0xCC000000.toInt()
            val textWidth = selection.measureText(sizeLabel)
            canvas.drawRoundRect(
                width / 2f - textWidth / 2f - 4f * density,
                textY - 10f * density,
                width / 2f + textWidth / 2f + 4f * density,
                textY + 2f * density,
                4f * density,
                4f * density,
                selection
            )
            selection.color = resources.getColor(R.color.crown_text_primary)
            canvas.drawText(sizeLabel, width / 2f, textY, selection)
        }
    }

    /**
     * 当全局模式（正常、编辑、选择）更改时，由ElementController调用。
     * 子类可以重写此项以更新其状态。
     */
    open fun onModeChanged(newMode: ElementController.Mode) {
        // Default implementation does nothing.
    }

    /** Release input that outlives a touch before permanently removing this element. */
    open fun releaseLatchedInput() {
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 忽略多点触控中的非主手指
        if (event.actionIndex != 0) {
            return true
        }

        // 确保控制器存在
        if (elementController == null) {
            return true
        }

        when (elementController.getMode()) {
            ElementController.Mode.Normal -> {
                elementController.beginElementEvent(this, event.actionMasked)
                try {
                    return onElementTouchEvent(event)
                } finally {
                    elementController.endElementEvent(this, event.actionMasked)
                }
            }

            ElementController.Mode.Edit -> {
                // Edit 模式逻辑
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        elementController.setSelectedElement(this)
                        lastX = event.x
                        lastY = event.y
                        isClick = true
                        longPressDetected = false
                        editColor = 0xffdc143c.toInt() // 红色表示初始状态
                        invalidate()

                        // 启动长按检测
                        elementController.getHandler().removeCallbacks(longPressRunnable)
                        elementController.clearAlignmentGuides()
                        elementController.getHandler().postDelayed(longPressRunnable, DRAG_EDIT_LONG_PRESS_TIMEOUT)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val x = event.x
                        val y = event.y
                        val deltaX = x - lastX
                        val deltaY = y - lastY

                        // 小位移算作点击
                        if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2) {
                            return true
                        }

                        // 只有检测到长按或没有开启长按移动后才允许拖动
                        if (!elementController.isDragEditEnabled() or longPressDetected) {
                            isClick = false
                            val snapResult = elementController.snapElementPosition(
                                this,
                                this.x.toInt() + width / 2 + deltaX.toInt(),
                                this.y.toInt() + height / 2 + deltaY.toInt()
                            )
                            setElementCentralX(snapResult.centralX)
                            setElementCentralY(snapResult.centralY)
                            updatePage()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                        // 取消长按检测
                        elementController.getHandler().removeCallbacks(longPressRunnable)
                        elementController.clearAlignmentGuides()

                        editColor = 0xffdc143c.toInt()
                        invalidate()

                        if (isClick || !longPressDetected) {
                            val infoPage = getInfoPage()
                            infoPage.setTag(R.id.crown_auto_color_owner, this)
                            elementController.toggleInfoPage(infoPage)
                        } else {
                            save()
                        }
                        return true
                    }
                }
                return true
            }
            //组按键选择子按键时使用，可更改为ACTION_DOWN来快速（滑动）选择按键
            ElementController.Mode.Select -> {
                // 1. 触发时机ACTION_UP (手指抬起时)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // 2. 增加对回调的 null 检查，防止应用崩溃
                    val selectedCallBack = elementSelectedCallBack
                    if (selectedCallBack != null) {
                        // 3. 安全地调用回调方法
                        // 假设回调接口的方法名是 onElementSelected 或者 elementSelected
                        selectedCallBack.elementSelected(this)
                    }
                }
                // 4. 必须返回 true，表示事件已被处理
                return true
            }
        }
        return true
    }

    abstract fun getInfoPage(): SuperPageLayout

    /** Title shown on the inspector header when this element is selected. */
    open fun getCrownInspectorTitle(): String {
        return ""
    }

    abstract fun updatePage()

    abstract fun save()

    fun supportsCrownAutoColors(): Boolean {
        return CrownAutoColorApplier.supports(this)
    }

    fun applyCrownAutoColors(palette: CrownAutoColorPalette, page: SuperPageLayout?): Boolean {
        return CrownAutoColorApplier.apply(this, palette, page)
    }

    abstract fun onElementDraw(canvas: Canvas)

    abstract fun onElementTouchEvent(event: MotionEvent): Boolean

    protected fun getPercent(value: Float, percent: Float): Float {
        return value / 100 * percent
    }
}
