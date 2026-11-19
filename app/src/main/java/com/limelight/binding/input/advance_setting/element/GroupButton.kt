//组按键
package com.limelight.binding.input.advance_setting.element

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.limelight.utils.AppSwitchStyle

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.PageDeviceController
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController

import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
class GroupButton(attributesMap: Map<String, Any>,
                  controller: ElementController,
                  pageDeviceController: PageDeviceController,
                  superPagesController: SuperPagesController,
                  context: Context) : Element(attributesMap, controller, context) {
    companion object {
        private const val COLUMN_INT_CHILD_VISIBILITY = Element.COLUMN_INT_ELEMENT_SENSE

        private val CHILD_VISIBLE = View.VISIBLE
        private val CHILD_INVISIBLE = View.INVISIBLE

        // 在编辑模式下，如果元素被按住超过系统定义的长按时间，就允许拖动，
        // 以避免用户想打开按键设置而不是移动按键
        private const val DRAG_EDIT_LONG_PRESS_TIMEOUT = 250L

        @JvmStatic
        fun getInitialInfo(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_GROUP_BUTTON)
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "GROUP")
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "-1")
            contentValues.put(COLUMN_INT_CHILD_VISIBILITY, View.VISIBLE)
            contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 100)
            contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 100)
            contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 100)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 100)
            contentValues.put(COLUMN_INT_ELEMENT_RADIUS, 0)
            contentValues.put(COLUMN_INT_ELEMENT_THICK, 5)
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0x00FFFFFF)
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, 0xFFFFFFFF.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, 0xFFCCCCCC.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 25)
            contentValues.put(COLUMN_INT_ELEMENT_FLAG1, 0) // hidden flag

            val extraAttrs = JsonObject()
            extraAttrs.addProperty("movableInNormalMode", false) // Default value is false
            extraAttrs.addProperty("userHasManuallySet", false)
            contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, Gson().toJson(extraAttrs))

            return contentValues
        }
    }

    /**
     * Listener interface to update registered observers.
     */
    interface GroupButtonListener {
        /**
         * onClick event will be fired on button click.
         */
        fun onClick()

        /**
         * onLongClick event will be fired on button long click.
         */
        fun onLongClick()

        /**
         * onRelease event will be fired on button unpress.
         */
        fun onRelease()
    }

    private var pageDeviceController: PageDeviceController? = null
    private var groupButton: GroupButton? = null
    private val childElementList = ArrayList<Element>()
    private var superPagesController: SuperPagesController? = null

    private var listener: GroupButtonListener? = null
    private var text: String?
    private var value: String?
    private var radius: Int
    private var thick: Int
    private var childVisibility: Int
    private var normalColor: Int
    private var pressedColor: Int
    private var backgroundColor: Int
    // New member variables for text properties
    private var normalTextColor: Int
    private var pressedTextColor: Int
    private var textSizePercent: Int

    private var lastX = 0f
    private var lastY = 0f
    private var childPositionAttributeFollow = true
    private var childOtherAttributeFollow = false
    private val initialCentralXMax: Int
    private val initialCentralXMin: Int
    private val initialCentralYMax: Int
    private val initialCentralYMin: Int

    private var groupButtonPage: SuperPageLayout? = null
    private var centralXNumberSeekbar: NumberSeekbar? = null
    private var centralYNumberSeekbar: NumberSeekbar? = null
    private var selectMode = false

    private var movable = false
    private var layoutComplete = true
    private var resizeXBorder = true
    private var resizeYBorder = true

    private var timerLongClickTimeout = 250L
    private val longClickRunnable = Runnable { onLongClickCallback() }
    private val paintBorder = Paint()
    private val paintBackground = Paint()
    private val paintText = Paint()
    private val paintEdit = Paint()
    private val rect = RectF()
    private var hidden = false
    private var movableInNormalMode = false
    private var userHasManuallySet = false
    private var isPermanentlyIndependent = false

    private var longPressDetected = false
    private val longPressRunnable: Runnable = object : Runnable {
        override fun run() {
            longPressDetected = true
            // 可以添加视觉反馈，比如改变边框颜色
            editColor = 0xff00f91a.toInt() // 绿色表示可以拖动
            invalidate()
        }
    }

    init {
        this.pageDeviceController = pageDeviceController
        this.groupButton = this
        this.superPagesController = superPagesController

        val displayMetrics = DisplayMetrics()
        (context as Game).windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        initialCentralXMax = displayMetrics.widthPixels
        initialCentralXMin = 0
        initialCentralYMax = displayMetrics.heightPixels
        initialCentralYMin = 0
        centralXMax = initialCentralXMax
        centralXMin = initialCentralXMin
        centralYMax = initialCentralYMax
        centralYMin = initialCentralYMin
        widthMax = displayMetrics.widthPixels / 2
        widthMin = 50
        heightMax = displayMetrics.heightPixels / 2
        heightMin = 50

        paintEdit.style = Paint.Style.STROKE
        paintEdit.strokeWidth = 4f
        paintEdit.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
        paintText.textAlign = Paint.Align.CENTER
        paintBorder.style = Paint.Style.STROKE
        paintBackground.style = Paint.Style.FILL

        text = attributesMap.get(COLUMN_STRING_ELEMENT_TEXT) as String?
        radius = (attributesMap.get(COLUMN_INT_ELEMENT_RADIUS) as Long).toInt()
        thick = (attributesMap.get(COLUMN_INT_ELEMENT_THICK) as Long).toInt()
        childVisibility = (attributesMap.get(COLUMN_INT_CHILD_VISIBILITY) as Long).toInt()
        normalColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR) as Long).toInt()
        pressedColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR) as Long).toInt()
        backgroundColor = (attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR) as Long).toInt()
        value = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE) as String? // 仅仅读取 value 字符串，后续的链接操作将由 linkChildElements 方法完成

        // Load new text properties with backward compatibility
        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR)) {
            normalTextColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR) as Long).toInt()
        } else {
            // Default to old behavior: use border color
            normalTextColor = normalColor
        }

        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR)) {
            pressedTextColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR) as Long).toInt()
        } else {
            // Default to old behavior: use border color
            pressedTextColor = pressedColor
        }

        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT)) {
            textSizePercent = (attributesMap.get(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT) as Long).toInt()
        } else {
            // Default based on a reasonable value, old logic was complex
            textSizePercent = 25
        }

        val hiddenFlagObj = attributesMap.get(COLUMN_INT_ELEMENT_FLAG1)
        this.hidden = hiddenFlagObj != null && (hiddenFlagObj as Long).toInt() == 1

        this.movableInNormalMode = false
        this.userHasManuallySet = false
        this.isPermanentlyIndependent = false
        if (attributesMap.containsKey(COLUMN_STRING_EXTRA_ATTRIBUTES)) {
            val extraAttrsJson = attributesMap.get(COLUMN_STRING_EXTRA_ATTRIBUTES) as String?
            if (extraAttrsJson != null && !extraAttrsJson.isEmpty()) {
                try { // 使用 try-catch 增加代码健壮性
                    val extraAttrs = JsonParser.parseString(extraAttrsJson).asJsonObject
                    if (extraAttrs.has("movableInNormalMode")) {
                        this.movableInNormalMode = extraAttrs.get("movableInNormalMode").asBoolean
                    }
                    // 加载 userHasManuallySet 状态
                    if (extraAttrs.has("userHasManuallySet")) {
                        this.userHasManuallySet = extraAttrs.get("userHasManuallySet").asBoolean
                    }
                    // 加载永久独立状态
                    if (extraAttrs.has("isPermanentlyIndependent")) {
                        this.isPermanentlyIndependent = extraAttrs.get("isPermanentlyIndependent").asBoolean
                    }
                } catch (e: Exception) {
                }
            }
        }

        listener = object : GroupButtonListener {
            override fun onClick() {
            }

            override fun onLongClick() {
            }

            override fun onRelease() {
                // 当按钮被释放时，调用公共的触发方法
                triggerAction()
            }
        }
        // 在构造函数的最后，根据当前模式设置初始可见性
        onModeChanged(controller.getMode())
    }

    fun linkChildElements(allElements: List<Element>) {
        if (value == null || value!!.isEmpty()) {
            return
        }

        // 创建一个查找表 (Map)
        val elementMap = HashMap<Long?, Element>()
        for (el in allElements) {
            elementMap[el.elementId] = el
        }

        val childElementIds = value!!.split(",")
        childElementList.clear()

        for (childElementIdString in childElementIds) {
            if (childElementIdString == "-1") continue
            try {
                val childElementId = java.lang.Long.parseLong(childElementIdString)
                // 直接从 Map 中获取，效率更高
                val child = elementMap[childElementId]
                if (child != null) {
                    childElementList.add(child)
                }
            } catch (e: NumberFormatException) {
                // 忽略无效ID
            }
        }

        // 现在所有子元素都已链接，可以安全地设置它们的初始可见性了
        setElementChildVisibility(childVisibility)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        layoutComplete = true
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onElementDraw(canvas: Canvas) {
        // Get element dimensions
        val elementWidth = getElementWidth()
        val elementHeight = getElementHeight()

        // Set text size based on percentage of height
        val textSize = getPercent(elementHeight.toFloat(), textSizePercent.toFloat())
        paintText.textSize = textSize
        // Set text color based on press state using new properties
        paintText.setColor(if (isPressed) pressedTextColor else normalTextColor)
        // Border
        paintBorder.strokeWidth = thick.toFloat()
        paintBorder.setColor(if (isPressed) pressedColor else normalColor)
        // Background color
        paintBackground.setColor(backgroundColor)

        val centerX = elementWidth / 2f
        // Calculate the baseline Y for vertical centering
        val fontMetrics = paintText.fontMetrics
        val baselineY = elementHeight / 2f - (fontMetrics.top + fontMetrics.bottom) / 2f

        // Drawing bounds
        rect.left = thick.toFloat() / 2
        rect.top = rect.left
        rect.right = getElementWidth() - rect.left
        rect.bottom = height - rect.top
        // Draw background
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paintBackground)
        // Draw border
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paintBorder)
        // Draw text using the calculated precise coordinates
        canvas.drawText(text!!, centerX, baselineY, paintText)

        val currentMode = elementController.getMode()

        if (currentMode === ElementController.Mode.Edit || currentMode === ElementController.Mode.Select) {
            // 准备绘制边框
            rect.left = 2f
            rect.top = rect.left
            rect.right = width - 2f
            rect.bottom = height - 2f

            if (currentMode === ElementController.Mode.Select && selectMode) {
                // 情况1: 当前是 Select 模式，并且这是正在操作的那个 GroupButton
                // 强制使用绿色边框
                paintEdit.setColor(0xff00f91a.toInt())
            } else {
                // 情况2: 是 Edit 模式，或者是在 Select 模式下的其他 GroupButton
                // (作为“可选”或“已选”项)
                // 统一使用 onModeChanged 中设置好的 editColor (红色、橙色或蓝色)
                paintEdit.setColor(editColor)
            }

            // 执行绘制
            canvas.drawRect(rect, paintEdit)
        }
    }

    private fun onClickCallback() {
        // notify listenersbuttonListener.onClick();
        listener!!.onClick()
        elementController.getHandler().removeCallbacks(longClickRunnable)
        elementController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout)
    }

    private fun onLongClickCallback() {
        // notify listeners
        listener!!.onLongClick()
        if (elementController.getMode() === ElementController.Mode.Normal && movableInNormalMode) {
            elementController.buttonVibrator()
            movable = true
            if (childPositionAttributeFollow) {
                for (element in childElementList) {
                    element.setAlpha(0.5f)
                }
            }
            invalidate()
        }
    }

    private fun onReleaseCallback() {
        // notify listeners
        listener!!.onRelease()
        // We may be called for a release without a prior click
        elementController.getHandler().removeCallbacks(longClickRunnable)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionIndex != 0) return true

        when (elementController.getMode()) {
            ElementController.Mode.Normal -> when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    elementController.buttonVibrator()
                    resizeXBorder = true
                    resizeYBorder = true
                    lastX = event.x
                    lastY = event.y
                    isPressed = true
                    onClickCallback()
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (movable) {
                        val deltaX = event.x - lastX
                        val deltaY = event.y - lastY
                        //小位移算作点击
                        if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2) return true

                        if (layoutComplete) {
                            layoutComplete = false
                            setElementCentralX(this@GroupButton.x.toInt() + width / 2 + deltaX.toInt())
                            setElementCentralY(this@GroupButton.y.toInt() + height / 2 + deltaY.toInt())
                        }
                        updatePage()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    isPressed = false
                    if (movable) {
                        if (childPositionAttributeFollow) {
                            for (element in childElementList) {
                                element.setAlpha(1f)
                            }
                        }
                        movable = false
                        save()
                    } else {
                        onReleaseCallback()
                    }
                    invalidate()
                    return true
                }
            }

            ElementController.Mode.Edit -> when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeXBorder = true
                    resizeYBorder = true
                    lastX = event.x
                    lastY = event.y
                    movable = false // 重置移动标志
                    longPressDetected = false // 重置长按标志
                    editColor = 0xffdc143c.toInt() // 红色表示初始状态
                    invalidate()

                    // 启动长按检测
                    elementController.getHandler().removeCallbacks(longPressRunnable)
                    elementController.getHandler().postDelayed(longPressRunnable, DRAG_EDIT_LONG_PRESS_TIMEOUT)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - lastX
                    val deltaY = event.y - lastY

                    // 小位移算作点击
                    if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2) {
                        return true
                    }

                    // 只有检测到长按或关闭长按移动后才允许拖动
                    if (!elementController.isDragEditEnabled() or longPressDetected) {
                        movable = true
                        if (layoutComplete) {
                            layoutComplete = false
                            val snapResult = elementController.snapElementPosition(
                                this,
                                this@GroupButton.x.toInt() + width / 2 + deltaX.toInt(),
                                this@GroupButton.y.toInt() + height / 2 + deltaY.toInt()
                            )
                            setElementCentralX(snapResult.centralX)
                            setElementCentralY(snapResult.centralY)
                        }
                        updatePage()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    // 取消长按检测
                    elementController.getHandler().removeCallbacks(longPressRunnable)
                    elementController.clearAlignmentGuides()

                    if (movable) {
                        save()
                        movable = false
                    } else {
                        elementController.toggleInfoPage(getInfoPage())
                    }
                    editColor = 0xffdc143c.toInt()
                    invalidate()
                    return true
                }
            }

            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun save() {
        // 默认的 save() 应该是递归的，因为它在大多数情况下（拖动、设置）都需要
        saveHierarchy()
    }

    // 一个只保存自己的方法
    private fun saveSelfOnly() {
        // 保存当前 GroupButton 自身的状态 ---
        val contentValues = ContentValues()
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text)
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value)
        contentValues.put(COLUMN_INT_CHILD_VISIBILITY, childVisibility)
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth())
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight())
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX())
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius)
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick)
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer)
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor)
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor)
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor)
        contentValues.put(COLUMN_INT_ELEMENT_FLAG1, if (hidden) 1 else 0) // hidden remains in its own column
        // Save new text properties
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, normalTextColor)
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, pressedTextColor)
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, textSizePercent)

        val extraAttrs = JsonObject()
        extraAttrs.addProperty("movableInNormalMode", this.movableInNormalMode)
        extraAttrs.addProperty("userHasManuallySet", this.userHasManuallySet)
        extraAttrs.addProperty("isPermanentlyIndependent", this.isPermanentlyIndependent)
        contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, Gson().toJson(extraAttrs))

        elementController.updateElement(elementId!!, contentValues)
    }

    // 一个明确的递归保存方法
    private fun saveHierarchy() {
        // 步骤1：先保存自己
        saveSelfOnly()

        // 步骤2：然后命令所有子元素也进行保存
        for (child in childElementList) {
            // 直接调用子元素的公共 save() 方法。
            // 如果子元素是另一个 GroupButton，它会触发它自己的递归 saveHierarchy。
            // 如果是普通按钮，就执行它自己的保存逻辑。
            child.save()
        }
    }

    override fun updatePage() {
        if (groupButtonPage != null) {
            centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
            centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        }
    }

    override fun getCrownInspectorTitle(): String {
        return context.getString(R.string.layout_page_group_button_text_6db87)
    }

    override fun getInfoPage(): SuperPageLayout {
        if (groupButtonPage == null) {
            groupButtonPage = LayoutInflater.from(context).inflate(R.layout.page_group_button_clean, null) as SuperPageLayout
            centralXNumberSeekbar = groupButtonPage!!.findViewById(R.id.page_group_button_central_x)
            centralYNumberSeekbar = groupButtonPage!!.findViewById(R.id.page_group_button_central_y)
        }

        setupFullInfoPage(groupButtonPage!!)

        return groupButtonPage!!
    }

    private fun setupFullInfoPage(page: SuperPageLayout) {
        // Find views
        val widthNumberSeekbar = page.findViewById<NumberSeekbar>(R.id.page_group_button_width)
        val heightNumberSeekbar = page.findViewById<NumberSeekbar>(R.id.page_group_button_height)
        val radiusNumberSeekbar = page.findViewById<NumberSeekbar>(R.id.page_group_button_radius)
        val childPositionAttributeFollowSwitch = page.findViewById<SwitchCompat>(R.id.page_group_button_child_position_attribute_follow)
        val childOtherAttributeFollowSwitch = page.findViewById<SwitchCompat>(R.id.page_group_button_child_other_attribute_follow)
        val childVisibleSwitch = page.findViewById<SwitchCompat>(R.id.page_group_button_child_visible)
        val textElementEditText = page.findViewById<ElementEditText>(R.id.page_group_button_text)
        val thickNumberSeekbar = page.findViewById<NumberSeekbar>(R.id.page_group_button_thick)
        val layerNumberSeekbar = page.findViewById<NumberSeekbar>(R.id.page_group_button_layer)
        val normalColorElementEditText = page.findViewById<ElementEditText>(R.id.page_group_button_normal_color)
        val pressedColorElementEditText = page.findViewById<ElementEditText>(R.id.page_group_button_pressed_color)
        val backgroundColorElementEditText = page.findViewById<ElementEditText>(R.id.page_group_button_background_color)
        val deleteEditText = page.findViewById<EditText>(R.id.page_group_button_delete_edittext)
        val deleteButton = page.findViewById<Button>(R.id.page_group_button_delete)
        val textSizeNumberSeekbar = page.findViewById<NumberSeekbar>(R.id.page_group_button_text_size)
        val normalTextColorElementEditText = page.findViewById<ElementEditText>(R.id.page_group_button_normal_text_color)
        val pressedTextColorElementEditText = page.findViewById<ElementEditText>(R.id.page_group_button_pressed_text_color)
        val hiddenSwitch = page.findViewById<SwitchCompat>(R.id.page_group_button_hidden_switch)
        val movableInNormalSwitch = page.findViewById<SwitchCompat>(R.id.page_group_button_movable_in_normal_switch)
        for (control in arrayOf(childPositionAttributeFollowSwitch,
                childOtherAttributeFollowSwitch, childVisibleSwitch, hiddenSwitch, movableInNormalSwitch)) {
            AppSwitchStyle.apply(control)
        }

        // Setup listeners
        textElementEditText.setTextWithNoTextChangedCallBack(text!!)
        textElementEditText.setOnTextChangedListener { newText ->
            setElementText(newText)
            save()
        }

        childPositionAttributeFollowSwitch.isChecked = childPositionAttributeFollow
        childPositionAttributeFollowSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            childPositionAttributeFollow = isChecked
            if (!childPositionAttributeFollow) {
                centralXMax = initialCentralXMax
                centralXMin = initialCentralXMin
                centralYMax = initialCentralYMax
                centralYMin = initialCentralYMin
            }
        }

        childOtherAttributeFollowSwitch.isChecked = childOtherAttributeFollow
        childOtherAttributeFollowSwitch.setOnCheckedChangeListener { buttonView, isChecked -> childOtherAttributeFollow = isChecked }

        childVisibleSwitch.isChecked = childVisibility == CHILD_VISIBLE
        childVisibleSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            setElementChildVisibility(if (isChecked) CHILD_VISIBLE else CHILD_INVISIBLE)
            save()
        }

        centralXNumberSeekbar!!.setProgressMin(centralXMin)
        centralXNumberSeekbar!!.setProgressMax(centralXMax)
        centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
        centralXNumberSeekbar!!.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (layoutComplete) {
                    layoutComplete = false
                    setElementCentralX(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                resizeXBorder = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 只需调用自身的 save()，因为它现在是递归的，会自动处理所有子孙
                save()
            }
        })

        centralYNumberSeekbar!!.setProgressMin(centralYMin)
        centralYNumberSeekbar!!.setProgressMax(centralYMax)
        centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        centralYNumberSeekbar!!.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (layoutComplete) {
                    layoutComplete = false
                    setElementCentralY(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                resizeYBorder = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 只需调用自身的 save()，因为它现在是递归的，会自动处理所有子孙
                save()
            }
        })

        widthNumberSeekbar.setProgressMax(widthMax)
        widthNumberSeekbar.setProgressMin(widthMin)
        widthNumberSeekbar.setValueWithNoCallBack(getElementWidth())
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (layoutComplete) {
                    layoutComplete = false
                    setElementWidth(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)

                save()
            }
        })

        heightNumberSeekbar.setProgressMax(heightMax)
        heightNumberSeekbar.setProgressMin(heightMin)
        heightNumberSeekbar.setValueWithNoCallBack(getElementHeight())
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (layoutComplete) {
                    layoutComplete = false
                    setElementHeight(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)

                save()
            }
        })

        radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)
        radiusNumberSeekbar.setValueWithNoCallBack(radius)
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementRadius(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        thickNumberSeekbar.setValueWithNoCallBack(thick)
        thickNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementThick(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        layerNumberSeekbar.setValueWithNoCallBack(layer)
        layerNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                setElementLayer(layerNumberSeekbar.value)

                save()
            }
        })

        textSizeNumberSeekbar.setProgressMin(10)
        textSizeNumberSeekbar.setProgressMax(150)
        textSizeNumberSeekbar.setValueWithNoCallBack(textSizePercent)
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementTextSizePercent(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        CrownColorPickerBinder.bind(this, normalColorElementEditText, { this.normalColor }, this::setElementNormalColor)
        CrownColorPickerBinder.bind(this, pressedColorElementEditText, { this.pressedColor }, this::setElementPressedColor)
        CrownColorPickerBinder.bind(this, backgroundColorElementEditText, { this.backgroundColor }, this::setElementBackgroundColor)
        CrownColorPickerBinder.bind(this, normalTextColorElementEditText, { this.normalTextColor }, this::setElementNormalTextColor)
        CrownColorPickerBinder.bind(this, pressedTextColorElementEditText, { this.pressedTextColor }, this::setElementPressedTextColor)

        hiddenSwitch.isChecked = hidden
        hiddenSwitch.setOnCheckedChangeListener { buttonView, isChecked -> setHidden(isChecked) }

        movableInNormalSwitch.isChecked = movableInNormalMode
        movableInNormalSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            movableInNormalMode = isChecked
            save()
        }

        page.findViewById<View>(R.id.page_group_button_select_child_button).setOnClickListener {
            elementController.changeMode(ElementController.Mode.Select)
            selectMode = true
            val elementSelectedCallBack = Element.ElementSelectedCallBack { element ->
                // 如果点击的元素已经是子元素，则将其移除
                if (childElementList.contains(element)) {
                    deleteChildElement(element)
                    element.setEditColor(EDIT_COLOR_SELECT)
                } else {
                    // 准备添加新元素前，检查该元素是否为本组按键自身
                    if (element === groupButton) {
                        // 如果是自身，则提示用户不能添加，并终止操作
                        Toast.makeText(this@GroupButton.context, R.string.group_button_cannot_add_self, Toast.LENGTH_SHORT).show()
                    } else {
                        // 如果不是自身，则正常添加为子元素
                        addChildElement(element)
                        element.setEditColor(EDIT_COLOR_SELECTED)
                    }
                }
                element.invalidate()
            }
            for (element in childElementList) element.setEditColor(EDIT_COLOR_SELECTED)
            for (element in elementController.elements)
                element.setElementSelectedCallBack(elementSelectedCallBack)

            val pageNull = superPagesController!!.pageNull
            superPagesController!!.openNewPage(pageNull)
            pageNull.setPageReturnListener(object : SuperPageLayout.ReturnListener {
                override fun returnCallBack() {
                    val lastPage = pageNull.lastPage
                    elementController.open()
                    superPagesController!!.openNewPage(lastPage!!)
                    elementController.changeMode(ElementController.Mode.Edit)
                    selectMode = false
                    save()
                }
            })
        }

        val permanentIndependentSwitch = page.findViewById<SwitchCompat>(R.id.page_group_button_override_parent_switch)
        AppSwitchStyle.apply(permanentIndependentSwitch)
        // 根据永久状态变量设置开关初始值
        permanentIndependentSwitch.isChecked = isPermanentlyIndependent
        permanentIndependentSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            isPermanentlyIndependent = isChecked // 控制新的永久状态变量
            save() // 保存更改
            if (isChecked) {
                Toast.makeText(this@GroupButton.context, R.string.group_button_independent_on, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@GroupButton.context, R.string.group_button_independent_off, Toast.LENGTH_SHORT).show()
            }
        }

        deleteButton.setOnClickListener {
            if (deleteEditText.text.toString() == "DELETE") {
                val allElement = ArrayList(elementController.elements)
                for (element in childElementList) {
                    if (allElement.contains(element)) {
                        elementController.deleteElement(element)
                    }
                }
                elementController.toggleInfoPage(groupButtonPage!!)
                elementController.deleteElement(groupButton!!)
                Toast.makeText(this@GroupButton.context, R.string.delete_success, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addChildElement(newElement: Element) {
        if (!childElementList.contains(newElement)) {
            childElementList.add(newElement)
            updateValueString()
        }
    }

    private fun deleteChildElement(deleteElement: Element) {
        if (childElementList.remove(deleteElement)) {
            updateValueString()
        }
    }

    private fun updateValueString() {
        val newValue = StringBuilder("-1")
        for (element in childElementList) {
            newValue.append(",").append(element.elementId)
        }
        value = newValue.toString()
    }

    fun triggerAction() {
        // 1. 标记这个按钮被用户直接点击了
        this.userHasManuallySet = true

        // 2. 计算出这次点击的目标状态 (显示/隐藏)
        val targetVisibility = if (this.childVisibility == CHILD_VISIBLE) CHILD_INVISIBLE else CHILD_VISIBLE

        // 3. 直接对当前按键执行状态切换并保存
        // 命令发起者自身必须无条件执行
        setElementChildVisibility(targetVisibility)
        saveSelfOnly()

        // 4. 创建一个 visited 集合，并将自己加入，防止在复杂的嵌套中产生循环
        val visited = HashSet<Element>()
        visited.add(this)

        // 5. 开始递归广播，让所有子孙 GroupButton 根据它们自己的状态决定是否执行
        for (child in childElementList) {
            if (child is GroupButton) {
                (child as GroupButton).performTriggerAction(visited, targetVisibility)
            }
        }
    }

    private fun performTriggerAction(visited: HashSet<Element>, commandVisibility: Int) {
        // 如果此组按键被设置为永久独立，则它会完全忽略来自父级的任何命令。
        if (this.isPermanentlyIndependent) {
            return
        }

        if (visited.contains(this)) return
        visited.add(this)

        var shouldExecute = false

        if (commandVisibility == CHILD_INVISIBLE) {
            // 命令是“隐藏”：无条件执行，并重置手动设置标志
            shouldExecute = true
            this.userHasManuallySet = false // 重置状态，使其下次可以跟随父级的“显示”命令
        } else {
            // 命令是“显示”：只有在未被手动设置过的情况下才执行
            if (!this.userHasManuallySet) {
                shouldExecute = true
            }
        }

        if (shouldExecute) {
            setElementChildVisibility(commandVisibility)
            // 调用只保存自己的方法，避免覆盖子孙状态
            saveSelfOnly()

            for (child in childElementList) {
                if (child is GroupButton) {
                    (child as GroupButton).performTriggerAction(visited, commandVisibility)
                }
            }
        }
    }

    fun setHidden(hidden: Boolean) {
        this.hidden = hidden
        onModeChanged(elementController.getMode())
        save()
    }

    override fun onModeChanged(newMode: ElementController.Mode) {
        super.onModeChanged(newMode)

        // 首先处理可见性逻辑，确保在不同模式下显示正确
        setVisibility(if (newMode === ElementController.Mode.Normal && hidden) View.INVISIBLE else View.VISIBLE)

        // 然后处理边框颜色的逻辑
        when (newMode) {
            ElementController.Mode.Select -> {
                // 当进入“选择”模式时，将此组按键的边框颜色设置为“可选择”状态（橙色）。
                // G1稍后会把它自己的子元素（可能是G2）的颜色覆盖为“已选中”（蓝色）。
                setEditColor(EDIT_COLOR_SELECT)
            }
            ElementController.Mode.Edit -> {
                // 当返回“编辑”模式时，恢复为默认的编辑颜色（红色）。
                setEditColor(EDIT_COLOR_EDIT)
            }
            ElementController.Mode.Normal -> {
                // 在正常模式下，重置状态。
                setEditColor(EDIT_COLOR_EDIT)
            }
        }
        // 强制重绘以更新边框
        invalidate()
    }

    fun getText(): String? {
        return text
    }

    /**
     * 获取此组按键包含的所有子元素的ID列表。
     * 这个公共方法是为了让其他组件（如WheelPad）能够查询组的内容以实现预览等功能。
     *
     * @return 一个包含所有子元素ID的列表。
     */
    fun getChildIds(): List<Long> {
        val childIds = ArrayList<Long>()
        for (child in childElementList) {
            childIds.add(child.elementId!!)
        }
        return childIds
    }

    protected fun setElementText(text: String?) {
        this.text = text
        invalidate()
    }

    override fun setElementCentralX(centralX: Int) {
        if (childPositionAttributeFollow) {
            val previousX = getElementCentralX()
            super.setElementCentralX(centralX)
            val deltaX = getElementCentralX() - previousX
            for (element in childElementList) {
                element.setElementCentralX(element.getElementCentralX() + deltaX)
            }
            if (resizeXBorder) {
                var leftMargin = centralXMax
                var rightMargin = centralXMax
                val allElement = elementController.elements
                for (element in childElementList) {
                    if (!allElement.contains(element)) {
                        continue
                    }
                    val elementCentralX = element.getElementCentralX()
                    leftMargin = Math.min(elementCentralX - element.centralXMin, leftMargin)
                    rightMargin = Math.min(element.centralXMax - elementCentralX, rightMargin)
                }
                val elementCentralX = getElementCentralX()
                leftMargin = Math.min(elementCentralX - centralXMin, leftMargin)
                rightMargin = Math.min(centralXMax - elementCentralX, rightMargin)

                centralXMin = elementCentralX - leftMargin
                centralXMax = elementCentralX + rightMargin
                if (centralXNumberSeekbar != null) {
                    centralXNumberSeekbar!!.setProgressMin(centralXMin)
                    centralXNumberSeekbar!!.setProgressMax(centralXMax)
                }
                resizeXBorder = false
            }
        } else {
            super.setElementCentralX(centralX)
        }
    }

    override fun setElementCentralY(centralY: Int) {
        if (childPositionAttributeFollow) {
            val previousY = getElementCentralY()
            super.setElementCentralY(centralY)
            val deltaY = getElementCentralY() - previousY
            for (element in childElementList) {
                element.setElementCentralY(element.getElementCentralY() + deltaY)
            }
            if (resizeYBorder) {
                var bottomMargin = centralYMax
                var topMargin = centralYMax
                val allElement = elementController.elements
                for (element in childElementList) {
                    if (!allElement.contains(element)) {
                        continue
                    }
                    val elementCentralY = element.getElementCentralY()
                    topMargin = Math.min(elementCentralY - element.centralYMin, topMargin)
                    bottomMargin = Math.min(element.centralYMax - elementCentralY, bottomMargin)
                }
                val elementCentralY = getElementCentralY()
                topMargin = Math.min(elementCentralY - centralYMin, topMargin)
                bottomMargin = Math.min(centralYMax - elementCentralY, bottomMargin)

                centralYMin = elementCentralY - topMargin
                centralYMax = elementCentralY + bottomMargin
                if (centralYNumberSeekbar != null) {
                    centralYNumberSeekbar!!.setProgressMin(centralYMin)
                    centralYNumberSeekbar!!.setProgressMax(centralYMax)
                }
                resizeYBorder = false
            }
        } else {
            super.setElementCentralY(centralY)
        }
    }

    override fun setElementWidth(width: Int) {
        super.setElementWidth(width)
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementWidth(width)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON, ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON, ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON, ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> element.setElementWidth(width)
                        else -> {
                        }
                    }
                }
            }
        }
    }

    override fun setElementHeight(height: Int) {
        super.setElementHeight(height)
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementHeight(height)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON, ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON, ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON, ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> element.setElementHeight(height)
                        else -> {
                        }
                    }
                }
            }
        }
    }

    protected fun setElementChildVisibility(childVisibility: Int) {
        this.childVisibility = childVisibility
        for (element in childElementList) {
            element.setVisibility(childVisibility)
        }
    }

    protected fun setElementRadius(radius: Int) {
        this.radius = radius
        invalidate()
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementRadius(radius)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> (element as DigitalCommonButton).setElementRadius(radius)
                        ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> (element as DigitalSwitchButton).setElementRadius(radius)
                        ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> (element as DigitalCombineButton).setElementRadius(radius)
                        ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> (element as DigitalMovableButton).setElementRadius(radius)
                        else -> {
                        }
                    }
                }
            }
        }
    }

    protected fun setElementThick(thick: Int) {
        this.thick = thick
        invalidate()
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementThick(thick)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> (element as DigitalCommonButton).setElementThick(thick)
                        ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> (element as DigitalSwitchButton).setElementThick(thick)
                        ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> (element as DigitalCombineButton).setElementThick(thick)
                        ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> (element as DigitalMovableButton).setElementThick(thick)
                        ELEMENT_TYPE_DIGITAL_PAD -> (element as DigitalPad).setElementThick(thick)
                        ELEMENT_TYPE_ANALOG_STICK -> (element as AnalogStick).setElementThick(thick)
                        ELEMENT_TYPE_DIGITAL_STICK -> (element as DigitalStick).setElementThick(thick)
                        ELEMENT_TYPE_INVISIBLE_ANALOG_STICK -> (element as InvisibleAnalogStick).setElementThick(thick)
                        ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK -> (element as InvisibleDigitalStick).setElementThick(thick)
                        else -> {
                        }
                    }
                }
            }
        }
    }

    override fun setElementLayer(layer: Int) {
        super.setElementLayer(layer)
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                // 直接调用子元素的 setElementLayer，因为 GroupButton 自己的这个方法
                // 已经被重写为递归的，所以能自动形成递归链。
                element.setElementLayer(layer)
            }
        }
    }

    fun setElementNormalColor(normalColor: Int) {
        this.normalColor = normalColor
        invalidate()
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementNormalColor(normalColor)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> (element as DigitalCommonButton).setElementNormalColor(normalColor)
                        ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> (element as DigitalSwitchButton).setElementNormalColor(normalColor)
                        ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> (element as DigitalCombineButton).setElementNormalColor(normalColor)
                        ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> (element as DigitalMovableButton).setElementNormalColor(normalColor)
                        ELEMENT_TYPE_DIGITAL_PAD -> (element as DigitalPad).setElementNormalColor(normalColor)
                        ELEMENT_TYPE_ANALOG_STICK -> (element as AnalogStick).setElementNormalColor(normalColor)
                        ELEMENT_TYPE_DIGITAL_STICK -> (element as DigitalStick).setElementNormalColor(normalColor)
                        ELEMENT_TYPE_INVISIBLE_ANALOG_STICK -> (element as InvisibleAnalogStick).setElementNormalColor(normalColor)
                        ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK -> (element as InvisibleDigitalStick).setElementNormalColor(normalColor)
                        else -> {
                        }
                    }
                }
            }
        }
    }

    fun setElementPressedColor(pressedColor: Int) {
        this.pressedColor = pressedColor
        invalidate()
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementPressedColor(pressedColor)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> (element as DigitalCommonButton).setElementPressedColor(pressedColor)
                        ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> (element as DigitalSwitchButton).setElementPressedColor(pressedColor)
                        ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> (element as DigitalCombineButton).setElementPressedColor(pressedColor)
                        ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> (element as DigitalMovableButton).setElementPressedColor(pressedColor)
                        ELEMENT_TYPE_DIGITAL_PAD -> (element as DigitalPad).setElementPressedColor(pressedColor)
                        ELEMENT_TYPE_ANALOG_STICK -> (element as AnalogStick).setElementPressedColor(pressedColor)
                        ELEMENT_TYPE_DIGITAL_STICK -> (element as DigitalStick).setElementPressedColor(pressedColor)
                        ELEMENT_TYPE_INVISIBLE_ANALOG_STICK -> (element as InvisibleAnalogStick).setElementPressedColor(pressedColor)
                        ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK -> (element as InvisibleDigitalStick).setElementPressedColor(pressedColor)
                        else -> {
                        }
                    }
                }
            }
        }
    }

    fun setElementBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
        invalidate()
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementBackgroundColor(backgroundColor)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> (element as DigitalCommonButton).setElementBackgroundColor(backgroundColor)
                        ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> (element as DigitalSwitchButton).setElementBackgroundColor(backgroundColor)
                        ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> (element as DigitalCombineButton).setElementBackgroundColor(backgroundColor)
                        ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> (element as DigitalMovableButton).setElementBackgroundColor(backgroundColor)
                        ELEMENT_TYPE_DIGITAL_PAD -> (element as DigitalPad).setElementBackgroundColor(backgroundColor)
                        ELEMENT_TYPE_ANALOG_STICK -> (element as AnalogStick).setElementBackgroundColor(backgroundColor)
                        ELEMENT_TYPE_DIGITAL_STICK -> (element as DigitalStick).setElementBackgroundColor(backgroundColor)
                        ELEMENT_TYPE_INVISIBLE_ANALOG_STICK -> (element as InvisibleAnalogStick).setElementBackgroundColor(backgroundColor)
                        ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK -> (element as InvisibleDigitalStick).setElementBackgroundColor(backgroundColor)
                        else -> {
                        }
                    }
                }
            }
        }
    }

    fun setElementNormalTextColor(normalTextColor: Int) {
        this.normalTextColor = normalTextColor
        invalidate()
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementNormalTextColor(normalTextColor)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> (element as DigitalCommonButton).setElementNormalTextColor(normalTextColor)
                        ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> (element as DigitalSwitchButton).setElementNormalTextColor(normalTextColor)
                        ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> (element as DigitalCombineButton).setElementNormalTextColor(normalTextColor)
                        ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> (element as DigitalMovableButton).setElementNormalTextColor(normalTextColor)
                    }
                }
            }
        }
    }

    fun setElementPressedTextColor(pressedTextColor: Int) {
        this.pressedTextColor = pressedTextColor
        invalidate()
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementPressedTextColor(pressedTextColor)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> (element as DigitalCommonButton).setElementPressedTextColor(pressedTextColor)
                        ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> (element as DigitalSwitchButton).setElementPressedTextColor(pressedTextColor)
                        ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> (element as DigitalCombineButton).setElementPressedTextColor(pressedTextColor)
                        ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> (element as DigitalMovableButton).setElementPressedTextColor(pressedTextColor)
                    }
                }
            }
        }
    }

    protected fun setElementTextSizePercent(textSizePercent: Int) {
        this.textSizePercent = textSizePercent
        invalidate()
        if (childOtherAttributeFollow) {
            for (element in childElementList) {
                if (element is GroupButton) {
                    (element as GroupButton).setElementTextSizePercent(textSizePercent)
                } else {
                    when (element.elementType) {
                        ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> (element as DigitalCommonButton).setElementTextSizePercent(textSizePercent)
                        ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> (element as DigitalSwitchButton).setElementTextSizePercent(textSizePercent)
                        ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> (element as DigitalCombineButton).setElementTextSizePercent(textSizePercent)
                        ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> (element as DigitalMovableButton).setElementTextSizePercent(textSizePercent)
                    }
                }
            }
        }
    }
}
