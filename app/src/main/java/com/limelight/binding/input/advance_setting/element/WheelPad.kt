//轮盘按键
package com.limelight.binding.input.advance_setting.element

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.widget.SwitchCompat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.PageDeviceController
import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import com.limelight.utils.AppDialogStyler

import java.util.ArrayList
import java.util.Arrays

class WheelPad(attributesMap: Map<String, Any>,
               controller: ElementController,
               pageDeviceController: PageDeviceController,
               context: Context) : Element(attributesMap, controller, context) {
    companion object {
        private const val EXTRA_SEGMENT_ALIGNMENT = "segmentAlignment"
        private const val SEGMENT_ALIGNMENT_CENTER = "center"
        private const val SEGMENT_ALIGNMENT_EDGE = "edge"

        @JvmStatic
        fun getInitialInfo(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_WHEEL_PAD)
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "k51,k32,k47,k29,k45,k33,k46,k31")
            contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 400)
            contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 400)
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "")
            contentValues.put(COLUMN_INT_ELEMENT_LAYER, 40)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 250)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 250)
            contentValues.put(COLUMN_INT_ELEMENT_THICK, 5)
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0xAA444444.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_MODE, 8)
            contentValues.put(COLUMN_INT_ELEMENT_SENSE, 30)
            contentValues.put(COLUMN_INT_ELEMENT_FLAG1, 1)

            val extraAttrs = JsonObject()
            // **为所有新的字体属性设置默认值**
            extraAttrs.addProperty("normalTextColor", 0xFFFFFFFFL.toInt()) // White
            extraAttrs.addProperty("pressedTextColor", 0xFFFFFFFFL.toInt()) // White
            extraAttrs.addProperty("centerTextColor", 0xFFFFFFFFL.toInt()) // White
            extraAttrs.addProperty("textSizePercent", 35) // 35%
            extraAttrs.addProperty("centerTextSizePercent", 60) // 60%
            extraAttrs.addProperty("triggerTextSizePercent", 40)
            extraAttrs.addProperty("previewGroupChildren", true)
            extraAttrs.addProperty(EXTRA_SEGMENT_ALIGNMENT, SEGMENT_ALIGNMENT_CENTER)
            // 将 JsonObject 转换为字符串，并存入通用的 "extra_attributes" 列
            contentValues.put("extra_attributes", Gson().toJson(extraAttrs))

            return contentValues
        }
    }

    private val pageDeviceController: PageDeviceController = pageDeviceController
    private val wheelPad: WheelPad

    private var diameter: Int
    private var segmentCount: Int
    private var innerRadiusPercent: Int
    private var isPopupMode: Boolean
    private var centerText: String
    private var segmentValues: MutableList<String>
    private var segmentNames: MutableList<String>
    private var normalColor: Int
    private var pressedColor: Int
    private var backgroundColor: Int
    private var thick: Int
    private var normalTextColor: Int = 0
    private var pressedTextColor: Int = 0
    private var centerTextColor: Int = 0  // 中心文字颜色
    private var textSizePercent: Int = 0  // 分区文字大小百分比
    private var centerTextSizePercent: Int = 0 // 选择预览文字大小百分比

    private var triggerTextSizePercent: Int = 0 // 触发器文字大小百分比

    private val screenWidth: Int
    private val screenHeight: Int

    private val paintBorder = Paint()
    private val paintSegmentFill = Paint()
    private val paintSegmentFillPressed = Paint()
    private val paintBackground = Paint()
    private val paintText = Paint()
    private val paintEdit = Paint()
    private val paintCenterText = Paint()
    private val paintGlow = Paint() // 用于激活分区的发光效果
    private val rect = RectF()
    private val textPath = Path()

    private var activeIndex = -1
    private var lastActiveIndex = -1
    private var isWheelActive = false
    private var popupAtScreenCenter: Boolean
    private var previewGroupChildren = false
    private var alignSegmentEdge = false
    // 用于追踪当前悬停的组按键，以实现子按键预览
    private var hoveredGroupButton: GroupButton? = null

    private var wheelPadPage: SuperPageLayout? = null
    private var centralXNumberSeekbar: NumberSeekbar? = null
    private var centralYNumberSeekbar: NumberSeekbar? = null
    private var valuesContainer: LinearLayout? = null

    init {
        this.wheelPad = this

        val displayMetrics = DisplayMetrics()
        (context as Game).windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        this.screenWidth = displayMetrics.widthPixels
        this.screenHeight = displayMetrics.heightPixels

        centralXMax = displayMetrics.widthPixels
        centralXMin = 0
        centralYMax = displayMetrics.heightPixels
        centralYMin = 0
        widthMax = displayMetrics.widthPixels
        widthMin = 150
        heightMax = displayMetrics.heightPixels
        heightMin = 150

        paintBorder.style = Paint.Style.STROKE
        paintBorder.isAntiAlias = true
        paintSegmentFill.style = Paint.Style.FILL
        paintSegmentFill.isAntiAlias = true
        paintSegmentFillPressed.style = Paint.Style.FILL
        paintSegmentFillPressed.isAntiAlias = true
        paintBackground.style = Paint.Style.FILL
        paintBackground.isAntiAlias = true
        paintText.setColor(0xFFFFFFFF.toInt())
        paintText.textSize = 30f
        paintText.textAlign = Paint.Align.CENTER
        paintText.isAntiAlias = true
        paintEdit.style = Paint.Style.STROKE
        paintEdit.strokeWidth = 4f
        paintEdit.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
        paintCenterText.setColor(0xFFFFFFFF.toInt())
        paintCenterText.textSize = 60f
        paintCenterText.textAlign = Paint.Align.CENTER
        paintCenterText.isAntiAlias = true
        paintCenterText.isFakeBoldText = true

        // 初始化发光效果画笔
        paintGlow.style = Paint.Style.FILL
        paintGlow.isAntiAlias = true

        this.diameter = (attributesMap.get(COLUMN_INT_ELEMENT_WIDTH) as Long).toInt()

        val textObj = attributesMap.get(COLUMN_STRING_ELEMENT_TEXT)
        this.centerText = if (textObj != null) textObj as String else ""
        this.isPopupMode = !this.centerText.isEmpty()

        thick = (attributesMap.get(COLUMN_INT_ELEMENT_THICK) as Long).toInt()
        normalColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR) as Long).toInt()
        pressedColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR) as Long).toInt()
        backgroundColor = (attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR) as Long).toInt()
        segmentCount = (attributesMap.get(COLUMN_INT_ELEMENT_MODE) as Long).toInt()
        innerRadiusPercent = (attributesMap.get(COLUMN_INT_ELEMENT_SENSE) as Long).toInt()

        // Load the popup behavior flag. Default to true (new behavior) for old configs.
        val popupFlagObj = attributesMap.get(COLUMN_INT_ELEMENT_FLAG1)
        this.popupAtScreenCenter = popupFlagObj == null || (popupFlagObj as Long).toInt() == 1

        if (attributesMap.containsKey("extra_attributes")) {
            val extraAttrsJsonString = attributesMap.get("extra_attributes") as String?
            if (extraAttrsJsonString != null && !extraAttrsJsonString.isEmpty()) {
                try {
                    val extraAttrs = Gson().fromJson(extraAttrsJsonString, JsonObject::class.java)

                    // 从 extraAttrs 中安全地读取值
                    if (extraAttrs.has("normalTextColor")) {
                        this.normalTextColor = extraAttrs.get("normalTextColor").asInt
                    } else {
                        this.normalTextColor = 0xFFFFFFFF.toInt()
                    }

                    if (extraAttrs.has("centerTextColor")) {
                        this.centerTextColor = extraAttrs.get("centerTextColor").asInt
                    } else {
                        this.centerTextColor = 0xFFFFFFFF.toInt()
                    }

                    if (extraAttrs.has("pressedTextColor")) {
                        this.pressedTextColor = extraAttrs.get("pressedTextColor").asInt
                    } else {
                        this.pressedTextColor = 0xFFFFFFFF.toInt()
                    }

                    if (extraAttrs.has("textSizePercent")) {
                        this.textSizePercent = extraAttrs.get("textSizePercent").asInt
                    } else {
                        this.textSizePercent = 35
                    }

                    if (extraAttrs.has("centerTextSizePercent")) {
                        this.centerTextSizePercent = extraAttrs.get("centerTextSizePercent").asInt
                    } else {
                        this.centerTextSizePercent = 60
                    }
                    if (extraAttrs.has("triggerTextSizePercent")) {
                        this.triggerTextSizePercent = extraAttrs.get("triggerTextSizePercent").asInt
                    } else {
                        // 提供一个合理的默认值，通常应该比中心预览文字小
                        this.triggerTextSizePercent = 40
                    }
                    if (extraAttrs.has("previewGroupChildren")) {
                        this.previewGroupChildren = extraAttrs.get("previewGroupChildren").asBoolean
                    } else {
                        this.previewGroupChildren = true // 默认为开启
                    }
                    if (extraAttrs.has(EXTRA_SEGMENT_ALIGNMENT)) {
                        this.alignSegmentEdge = SEGMENT_ALIGNMENT_EDGE == extraAttrs.get(EXTRA_SEGMENT_ALIGNMENT).asString
                    } else {
                        this.alignSegmentEdge = false
                    }
                } catch (e: Exception) {
                    // JSON 解析失败，使用默认值
                    initializeDefaultFontAttributes()
                }
            } else {
                // 字段存在但为空，使用默认值
                initializeDefaultFontAttributes()
            }
        } else {
            // 字段不存在（旧数据），使用默认值
            initializeDefaultFontAttributes()
        }

        val valuesString = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE) as String?
        segmentValues = ArrayList()
        segmentNames = ArrayList()
        val segments = valuesString!!.split(",")
        for (segment in segments) {
            if (segment.isEmpty()) continue
            val parts = segment.split("|", limit = 2)
            segmentValues.add(parts[0])
            if (parts.size > 1) {
                segmentNames.add(parts[1])
            } else {
                segmentNames.add("")
            }
        }
    }

    private fun initializeDefaultFontAttributes() {
        this.normalTextColor = 0xFFFFFFFF.toInt()
        this.pressedTextColor = 0xFFFFFFFF.toInt()
        this.centerTextColor = 0xFFFFFFFF.toInt()
        this.textSizePercent = 35
        this.centerTextSizePercent = 60
        this.triggerTextSizePercent = 40
        this.previewGroupChildren = true
        this.alignSegmentEdge = false
    }

    fun isBeingEdited(): Boolean {
        val currentPage = elementController.currentEditingPage
        return wheelPadPage != null && currentPage === wheelPadPage
    }

    private fun getHandlersForValueNow(value: String?): List<ElementController.SendEventHandler> {
        val handlers = ArrayList<ElementController.SendEventHandler>()
        if (value == null || value.isEmpty() || value == "null") {
            return handlers
        }

        val keyValues = value.split("+")
        for (singleKeyValue in keyValues) {
            val handler = elementController.getSendEventHandler(singleKeyValue.trim())
            if (handler != null) {
                handlers.add(handler)
            }
        }
        return handlers
    }

    override fun onElementDraw(canvas: Canvas) {
        val currentMode = elementController.getMode()
        val isTheOneBeingEdited = isBeingEdited()
        // 屏幕中心预览
        if (isPopupMode && popupAtScreenCenter) {
            drawInactivePopupCenter(canvas)
            val shouldDrawCentralPreview = (currentMode === ElementController.Mode.Normal && isWheelActive) ||
                (currentMode === ElementController.Mode.Edit && isTheOneBeingEdited)

            if (shouldDrawCentralPreview) {
                canvas.save()
                val translateX = screenWidth / 2.0f - getElementCentralX()
                val translateY = screenHeight / 2.0f - getElementCentralY()
                canvas.translate(translateX, translateY)
                drawFullWheel(canvas)

                // 在绘制完轮盘后，如果悬停在组按键上，则绘制其子按键预览
                // 如果开启了预览功能，则绘制子按键
                if (previewGroupChildren) {
                    drawHoveredGroupButtonChildren(canvas, translateX, translateY)
                }

                canvas.restore()
            }
        } else {
            val shouldDrawTriggerInsteadOfFullWheel = isPopupMode && !popupAtScreenCenter &&
                currentMode === ElementController.Mode.Normal && !isWheelActive
            if (shouldDrawTriggerInsteadOfFullWheel) {
                drawInactivePopupCenter(canvas)
            } else {
                // 原地预览
                drawFullWheel(canvas)
                // 检查是否为激活的 "原地弹出模式"，如果是，则添加组按键预览
                val isActiveOnSitePopup = isPopupMode && !popupAtScreenCenter &&
                    currentMode === ElementController.Mode.Normal && isWheelActive

                if (isActiveOnSitePopup) {
                    // 因为是原地绘制，没有对画布进行平移，所以平移量为0
                    // 如果开启了预览功能，并且是激活的原地弹出模式，则绘制
                    if (previewGroupChildren && isActiveOnSitePopup) {
                        drawHoveredGroupButtonChildren(canvas, 0f, 0f)
                    }
                }
            }
        }

        if (currentMode === ElementController.Mode.Edit || currentMode === ElementController.Mode.Select) {
            rect.left = 2f
            rect.top = rect.left
            rect.right = width - 2f
            rect.bottom = height - 2f
            paintEdit.setColor(editColor)
            canvas.drawRect(rect, paintEdit)
        }
    }

    /**
     * 在轮盘预览模式下，绘制当前悬停的组按键的子按键。
     * @param canvas 画布。对于屏幕中心模式，其坐标系已平移；对于原地模式，坐标系为WheelPad的视图坐标系。
     * @param wheelTranslateX 轮盘绘制时在X轴上的平移量 (原地弹出时为0)。
     * @param wheelTranslateY 轮盘绘制时在Y轴上的平移量 (原地弹出时为0)。
     */
    private fun drawHoveredGroupButtonChildren(canvas: Canvas, wheelTranslateX: Float, wheelTranslateY: Float) {
        if (hoveredGroupButton == null) {
            return
        }

        val childIds = hoveredGroupButton!!.getChildIds()
        if (childIds.isEmpty()) {
            return
        }

        for (childId in childIds) {
            val child = elementController.findElementById(childId)
            if (child != null) {
                canvas.save()

                // 计算子元素相对于当前画布坐标系原点的位置。
                // 1. child.getLeft() 是子元素在屏幕上的绝对X坐标。
                // 2. this.getLeft() 是WheelPad视图在屏幕上的绝对X坐标。
                // 3. wheelTranslateX 是为了将轮盘居中而额外平移的量。
                // 最终，(this.getLeft() + wheelTranslateX) 就是当前画布原点在屏幕上的绝对X坐标。
                // 两者相减，得到子元素应该在当前画布的哪个相对位置绘制。
                val childDrawX = child.left - (this@WheelPad.left + wheelTranslateX)
                val childDrawY = child.top - (this@WheelPad.top + wheelTranslateY)

                // 将画布平移到子元素应该被绘制的相对位置。
                canvas.translate(childDrawX, childDrawY)

                // 调用子元素的绘制方法。
                // onElementDraw 期望画布原点已经位于元素的左上角，我们刚刚通过translate实现了这一点。
                child.onElementDraw(canvas)

                canvas.restore()
            }
        }
    }

    private fun drawInactivePopupCenter(canvas: Canvas) {
        val centerX = width / 2.0f
        val centerY = height / 2.0f
        var fillOuterRadius = width / 2.0f - thick
        if (fillOuterRadius < 0) fillOuterRadius = 0f
        val fillInnerRadius = fillOuterRadius * (innerRadiusPercent / 100.0f)
        val borderInnerDrawRadius = fillInnerRadius + thick / 2.0f

        paintBorder.setColor(normalColor)
        paintBorder.strokeWidth = thick.toFloat()
        // 柔和内圈高亮轮廓
        paintBorder.setShadowLayer(thick * 0.6f, 0f, 0f, (normalColor and 0x00FFFFFF) or 0x33000000)

        paintSegmentFill.setColor(0x80000000.toInt())
        canvas.drawCircle(centerX, centerY, fillInnerRadius, paintSegmentFill)

        rect.set(centerX - borderInnerDrawRadius, centerY - borderInnerDrawRadius, centerX + borderInnerDrawRadius, centerY + borderInnerDrawRadius)
        canvas.drawOval(rect, paintBorder)
        // 清除阴影以免影响后续绘制
        paintBorder.setShadowLayer(0f, 0f, 0f, 0)

        // 应用中心文字的颜色和大小 ---
        paintCenterText.setColor(this.centerTextColor)
        // 触发器文字大小改为基于内圈直径计算，随中心分区大小联动
        val triggerTextSize = fillInnerRadius * 2 * (this.triggerTextSizePercent / 100.0f)
        paintCenterText.textSize = triggerTextSize
        paintCenterText.setShadowLayer(Math.max(2f, thick * 0.3f), 0f, Math.max(1f, thick * 0.2f), 0x88000000.toInt())

        val textY = centerY - ((paintCenterText.descent() + paintCenterText.ascent()) / 2)
        canvas.drawText(centerText, centerX, textY, paintCenterText)
    }

    private fun drawFullWheel(canvas: Canvas) {
        val currentMode = elementController.getMode()
        val centerX = width / 2.0f
        val centerY = height / 2.0f

        var fillOuterRadius = width / 2.0f - thick
        if (fillOuterRadius < 0) fillOuterRadius = 0f
        val fillInnerRadius = fillOuterRadius * (innerRadiusPercent / 100.0f)
        var borderOuterDrawRadius = width / 2.0f - thick / 2.0f
        if (borderOuterDrawRadius < 0) borderOuterDrawRadius = 0f
        val borderInnerDrawRadius = fillInnerRadius + thick / 2.0f

        paintBorder.setColor(normalColor)
        paintBorder.strokeWidth = thick.toFloat()
        paintBackground.setColor(0xFF000000.toInt())

        paintSegmentFill.setColor(backgroundColor)
        paintSegmentFillPressed.setColor(pressedColor)

        paintText.setColor(this.normalTextColor)
        // 字体大小基于内外半径的差值（环带厚度）来计算，这样更直观
        val ringThickness = fillOuterRadius - fillInnerRadius
        val segmentTextSize = ringThickness * (this.textSizePercent / 100.0f)
        paintText.textSize = segmentTextSize

        val sweepAngle = WheelPadGeometry.sweepAngleDegrees(segmentCount)

        rect.set(centerX - fillOuterRadius, centerY - fillOuterRadius, centerX + fillOuterRadius, centerY + fillOuterRadius)
        for (i in 0 until segmentCount) {
            val startAngle = WheelPadGeometry.segmentStartAngleDegrees(i, segmentCount, alignSegmentEdge)
            val currentFillPaint = if (i == activeIndex) paintSegmentFillPressed else paintSegmentFill
            canvas.drawArc(rect, startAngle, sweepAngle, true, currentFillPaint)

            // 为激活分区添加发光效果
            if (i == activeIndex) {
                paintGlow.setColor(Color.argb(60, Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor)))
                paintGlow.setShadowLayer(fillOuterRadius * 0.15f, 0f, 0f, pressedColor)
                canvas.drawArc(rect, startAngle, sweepAngle, true, paintGlow)
                paintGlow.setShadowLayer(0f, 0f, 0f, 0) // 清除阴影
            }
        }

        canvas.drawCircle(centerX, centerY, fillInnerRadius, paintBackground)

        val textRadius = fillInnerRadius + (fillOuterRadius - fillInnerRadius) / 2.0f
        for (i in 0 until segmentCount) {
            val startAngle = WheelPadGeometry.segmentStartAngleDegrees(i, segmentCount, alignSegmentEdge)
            val textAngle = startAngle + sweepAngle / 2.0f // 分区中心角度（以上方为-90度起点）

            if (i < segmentValues.size) {
                val displayName: String
                if (i < segmentNames.size && segmentNames[i] != null && !segmentNames[i].isEmpty()) {
                    displayName = segmentNames[i]
                } else {
                    displayName = getDisplayStringForValue(segmentValues[i])
                }

                // 根据激活状态切换文字颜色与粗体
                if (i == activeIndex) {
                    paintText.setColor(this.pressedTextColor)
                    paintText.isFakeBoldText = true
                } else {
                    paintText.setColor(this.normalTextColor)
                    paintText.isFakeBoldText = false
                }

                // 计算文字中心点坐标（保持文字水平，不旋转）
                val textRad = Math.toRadians(textAngle.toDouble())
                val textCenterX = centerX + (textRadius * Math.cos(textRad)).toFloat()
                val textCenterY = centerY + (textRadius * Math.sin(textRad)).toFloat()

                // 将中心点转换为基线位置，使用字体度量保证垂直居中
                val fm = paintText.fontMetrics
                val baselineY = textCenterY - (fm.ascent + fm.descent) / 2.0f

                canvas.drawText(displayName, textCenterX, baselineY, paintText)
            }
        }

        rect.set(centerX - borderOuterDrawRadius, centerY - borderOuterDrawRadius, centerX + borderOuterDrawRadius, centerY + borderOuterDrawRadius)
        canvas.drawOval(rect, paintBorder)
        rect.set(centerX - borderInnerDrawRadius, centerY - borderInnerDrawRadius, centerX + borderInnerDrawRadius, centerY + borderInnerDrawRadius)
        canvas.drawOval(rect, paintBorder)

        for (i in 0 until segmentCount) {
            val angle = WheelPadGeometry.segmentStartAngleDegrees(i, segmentCount, alignSegmentEdge)
            val angleRad = Math.toRadians(angle.toDouble())
            val startX = centerX + (fillInnerRadius * Math.cos(angleRad)).toFloat()
            val startY = centerY + (fillInnerRadius * Math.sin(angleRad)).toFloat()
            val endX = centerX + (fillOuterRadius * Math.cos(angleRad)).toFloat()
            val endY = centerY + (fillOuterRadius * Math.sin(angleRad)).toFloat()
            canvas.drawLine(startX, startY, endX, endY, paintBorder)
        }

        // 在中心模式时，为外圈描边添加柔和阴影以增强层次
        if (isPopupMode && popupAtScreenCenter) {
            paintBorder.setShadowLayer(Math.max(2f, thick * 0.6f), 0f, 0f, 0x55000000)
        }

        val showCenterTextInNormalMode = isPopupMode && activeIndex != -1 && currentMode === ElementController.Mode.Normal
        val showCenterTextInEditMode = currentMode === ElementController.Mode.Edit && isBeingEdited()

        if (showCenterTextInNormalMode || showCenterTextInEditMode) {
            val displayName: String

            if (showCenterTextInNormalMode) {
                // 正常模式下，显示当前选中的分区文本
                if (activeIndex < segmentValues.size) {
                    if (activeIndex < segmentNames.size && segmentNames[activeIndex] != null && !segmentNames[activeIndex].isEmpty()) {
                        displayName = segmentNames[activeIndex]
                    } else {
                        displayName = getDisplayStringForValue(segmentValues[activeIndex])
                    }
                } else {
                    displayName = context.getString(R.string.crown_preview) // 备用
                }
            } else {
                // 编辑模式下，显示固定的示例文本
                displayName = context.getString(R.string.crown_preview)
            }

            // 应用中心文字的颜色和大小 (这部分逻辑不变)
            paintCenterText.setColor(this.centerTextColor)
            val centerTextSize = fillInnerRadius * 2 * (this.centerTextSizePercent / 100.0f)
            paintCenterText.textSize = centerTextSize
            paintCenterText.setShadowLayer(Math.max(2f, centerTextSize * 0.06f), 0f, Math.max(1f, centerTextSize * 0.04f), 0x88000000.toInt())

            val textY = centerY - ((paintCenterText.descent() + paintCenterText.ascent()) / 2)
            canvas.drawText(displayName, centerX, textY, paintCenterText)
        }

        // 清除描边阴影，避免影响外部绘制
        if (isPopupMode && popupAtScreenCenter) {
            paintBorder.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        if (elementController.getMode() !== ElementController.Mode.Normal) {
            return true
        }

        if (!isPopupMode) {
            handleDirectModeTouchEvent(event)
            return true
        }

        if (isWheelActive) {
            handlePopupModeTouchEvent(event)
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            val centerX = width / 2.0f
            val centerY = height / 2.0f
            var outerRadius = width / 2.0f - thick
            if (outerRadius < 0) outerRadius = 0f
            val innerRadius = outerRadius * (innerRadiusPercent / 100.0f)
            val dx = x - centerX
            val dy = y - centerY
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble())

            if (distance <= innerRadius) {
                handlePopupModeTouchEvent(event)
                return true
            }
        }
        return false
    }

    private fun handleDirectModeTouchEvent(event: MotionEvent) {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (action == MotionEvent.ACTION_DOWN) {
                elementController.buttonVibrator()
            }
            val x = event.x
            val y = event.y
            val centerX = width / 2.0f
            val centerY = height / 2.0f
            var outerRadius = width / 2.0f - thick
            if (outerRadius < 0) outerRadius = 0f
            val innerRadius = outerRadius * (innerRadiusPercent / 100.0f)
            val dx = x - centerX
            val dy = y - centerY
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble())

            if (distance > innerRadius && distance < outerRadius) {
                activeIndex = WheelPadGeometry.segmentIndexForVector(dx, dy, segmentCount, alignSegmentEdge)
            } else {
                activeIndex = -1
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeIndex = -1
        }
        updateSendingState()
        invalidate()
    }

    private fun handlePopupModeTouchEvent(event: MotionEvent) {
        val x = event.x
        val y = event.y
        val centerX = width / 2.0f
        val centerY = height / 2.0f

        var outerRadius = width / 2.0f - thick
        if (outerRadius < 0) outerRadius = 0f
        val innerRadius = outerRadius * (innerRadiusPercent / 100.0f)

        val dx = x - centerX
        val dy = y - centerY
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (distance <= innerRadius) {
                isWheelActive = true
                elementController.buttonVibrator()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (isWheelActive) {
                // 清除上一帧的悬停状态
                hoveredGroupButton = null

                if (distance > innerRadius) {
                    activeIndex = WheelPadGeometry.segmentIndexForVector(dx, dy, segmentCount, alignSegmentEdge)

                    // 检查当前分区是否为组按键，如果是，则获取其实例用于预览
                    if (activeIndex != -1 && activeIndex < segmentValues.size) {
                        val value = segmentValues[activeIndex]
                        if (value.startsWith("gb")) {
                            try {
                                val groupId = value.substring(2).toLong()
                                val element = elementController.findElementById(groupId)
                                if (element is GroupButton) {
                                    hoveredGroupButton = element as GroupButton
                                }
                            } catch (e: Exception) {
                                // 如果ID解析失败或找不到元素，确保悬停状态被清除
                                hoveredGroupButton = null
                            }
                        }
                    }
                } else {
                    activeIndex = -1
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (isWheelActive) {
                if (activeIndex != -1 && activeIndex < segmentValues.size) {
                    val value = segmentValues[activeIndex]
                    // 即时获取 handlers
                    val handlers = getHandlersForValueNow(value)

                    // 按下
                    for (handler in handlers) {
                        handler.sendEvent(true)
                    }

                    // 加入 30 毫秒延迟后再发送释放事件，防止按键时间过短被游戏忽略
                    if (!handlers.isEmpty()) {
                        elementController.getHandler().postDelayed({
                            for (i in handlers.indices.reversed()) {
                                handlers[i].sendEvent(false)
                            }
                        }, 30L) // 30 毫秒的持续时间
                    }
                }
                isWheelActive = false
                activeIndex = -1
                // 手指抬起，清除组按键预览
                hoveredGroupButton = null
                invalidate()
            }
        }
    }

    private fun updateSendingState() {
        if (activeIndex != lastActiveIndex) {
            // --- 释放上一个 ---
            if (lastActiveIndex != -1 && lastActiveIndex < segmentValues.size) {
                val lastValue = segmentValues[lastActiveIndex]
                // 即时获取 handlers
                val lastHandlers = getHandlersForValueNow(lastValue)
                for (i in lastHandlers.indices.reversed()) {
                    lastHandlers[i].sendEvent(false)
                }
            }

            // --- 按下新的 ---
            if (activeIndex != -1 && activeIndex < segmentValues.size) {
                val activeValue = segmentValues[activeIndex]
                // 即时获取 handlers
                val activeHandlers = getHandlersForValueNow(activeValue)
                for (handler in activeHandlers) {
                    handler.sendEvent(true)
                }
            }
            lastActiveIndex = activeIndex
        }
    }

    override fun getInfoPage(): SuperPageLayout {
        if (wheelPadPage == null) {
            wheelPadPage = LayoutInflater.from(context).inflate(R.layout.page_wheel_pad_clean, null) as SuperPageLayout
            centralXNumberSeekbar = wheelPadPage!!.findViewById(R.id.page_wheel_pad_central_x)
            centralYNumberSeekbar = wheelPadPage!!.findViewById(R.id.page_wheel_pad_central_y)
            valuesContainer = wheelPadPage!!.findViewById(R.id.page_wheel_pad_values_container)
        }

        val wheelPadPage = wheelPadPage!!
        // Find all views
        val centerTextInput = wheelPadPage.findViewById<ElementEditText>(R.id.page_wheel_pad_center_text)
        val popupSwitch = wheelPadPage.findViewById<SwitchCompat>(R.id.page_wheel_pad_popup_at_center_switch)

        val previewGroupSwitch = wheelPadPage.findViewById<SwitchCompat>(R.id.page_wheel_pad_preview_group_switch)

        val popupOptionsContainer = wheelPadPage.findViewById<LinearLayout>(R.id.page_wheel_pad_popup_options_container)

        val sizeNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_size)
        val thickNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_thick)
        val layerNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_layer)
        val normalColorElementEditText = wheelPadPage.findViewById<ElementEditText>(R.id.page_wheel_pad_normal_color)
        val pressedColorElementEditText = wheelPadPage.findViewById<ElementEditText>(R.id.page_wheel_pad_pressed_color)
        val backgroundColorElementEditText = wheelPadPage.findViewById<ElementEditText>(R.id.page_wheel_pad_background_color)
        val copyButton = wheelPadPage.findViewById<Button>(R.id.page_wheel_pad_copy)
        val deleteButton = wheelPadPage.findViewById<Button>(R.id.page_wheel_pad_delete)
        val segmentCountNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_segment_count)
        val segmentAlignmentSpinner = wheelPadPage.findViewById<Spinner>(R.id.page_wheel_pad_segment_alignment)
        val innerRadiusNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_inner_radius)

        val textSizeNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_text_size)
        val normalTextColorElementEditText = wheelPadPage.findViewById<ElementEditText>(R.id.page_wheel_pad_normal_text_color)
        val pressedTextColorElementEditText = wheelPadPage.findViewById<ElementEditText>(R.id.page_wheel_pad_pressed_text_color)
        val centerTextSizeNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_center_text_size)
        val centerTextColorElementEditText = wheelPadPage.findViewById<ElementEditText>(R.id.page_wheel_pad_center_text_color)
        val triggerTextSizeNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_trigger_text_size)

        setupCommonControls(centerTextInput, sizeNumberSeekbar, thickNumberSeekbar, layerNumberSeekbar,
            normalColorElementEditText, pressedColorElementEditText, backgroundColorElementEditText,
            copyButton, deleteButton)

        // Setup the switch
        popupSwitch.isChecked = this.popupAtScreenCenter
        popupSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            this.popupAtScreenCenter = isChecked
            invalidate()
            save()
        }

        // 设置预览开关
        previewGroupSwitch.isChecked = this.previewGroupChildren
        previewGroupSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            this.previewGroupChildren = isChecked
            // 预览开关不需要实时 invalidate()，因为它只在轮盘激活时生效
            save()
        }

        // Set the initial visibility based on the current mode
        val isPopup = !this.centerText.isEmpty()
        popupOptionsContainer.visibility = if (isPopup) View.VISIBLE else View.GONE

        // Override the listener to control visibility of the container
        centerTextInput.setOnTextChangedListener { text ->
            setCenterText(text)
            val isPopupNow = !text.isEmpty()
            // Show/Hide the entire container based on popup mode
            popupOptionsContainer.visibility = if (isPopupNow) View.VISIBLE else View.GONE
            invalidate()
            save()
        }

        segmentCountNumberSeekbar.setValueWithNoCallBack(segmentCount)
        segmentCountNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setSegmentCount(progress)
                updateValuesContainerUI()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        val alignmentAdapter = ArrayAdapter(
            context,
            R.layout.crown_spinner_item,
            Arrays.asList(
                context.getString(R.string.wheel_alignment_center),
                context.getString(R.string.wheel_alignment_edge)
            )
        )
        alignmentAdapter.setDropDownViewResource(R.layout.crown_spinner_item)
        segmentAlignmentSpinner.adapter = alignmentAdapter
        segmentAlignmentSpinner.setSelection(if (alignSegmentEdge) 1 else 0, false)
        segmentAlignmentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val newAlignSegmentEdge = position == 1
                if (alignSegmentEdge != newAlignSegmentEdge) {
                    alignSegmentEdge = newAlignSegmentEdge
                    invalidate()
                    save()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }

        innerRadiusNumberSeekbar.setValueWithNoCallBack(innerRadiusPercent)
        innerRadiusNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setInnerRadiusPercent(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        // 分区文字大小
        textSizeNumberSeekbar.setProgressMin(10) // 10%
        textSizeNumberSeekbar.setProgressMax(100) // 100%
        textSizeNumberSeekbar.setValueWithNoCallBack(this.textSizePercent)
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                textSizePercent = progress
                invalidate()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        // 中心文字大小
        centerTextSizeNumberSeekbar.setProgressMin(10) // 10%
        centerTextSizeNumberSeekbar.setProgressMax(150) // 150%
        centerTextSizeNumberSeekbar.setValueWithNoCallBack(this.centerTextSizePercent)
        centerTextSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                centerTextSizePercent = progress
                invalidate()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        CrownColorPickerBinder.bind(this, normalTextColorElementEditText, { this.normalTextColor }) { color ->
            this.normalTextColor = color
            invalidate()
        }
        CrownColorPickerBinder.bind(this, pressedTextColorElementEditText, { this.pressedTextColor }) { color ->
            this.pressedTextColor = color
            invalidate()
        }
        CrownColorPickerBinder.bind(this, centerTextColorElementEditText, { this.centerTextColor }) { color ->
            this.centerTextColor = color
            invalidate()
        }
        // --- 设置触发器文字大小的 seekbar ---
        triggerTextSizeNumberSeekbar.setProgressMin(5)
        triggerTextSizeNumberSeekbar.setProgressMax(150)
        triggerTextSizeNumberSeekbar.setValueWithNoCallBack(this.triggerTextSizePercent)
        triggerTextSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                triggerTextSizePercent = progress
                invalidate() // 实时预览
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save() // 保存设置
            }
        })

        updateValuesContainerUI()
        return wheelPadPage
    }

    override fun save() {
        val contentValues = ContentValues()
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, this.diameter)
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, this.diameter)
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, this.centerText)
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer)
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX())
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick)
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor)
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor)
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor)
        contentValues.put(COLUMN_INT_ELEMENT_MODE, segmentCount)
        contentValues.put(COLUMN_INT_ELEMENT_SENSE, innerRadiusPercent)
        contentValues.put(COLUMN_INT_ELEMENT_FLAG1, if (popupAtScreenCenter) 1 else 0)

        contentValues.put("extra_attributes", Gson().toJson(buildExtraAttributes()))

        val combinedSegments = ArrayList<String>()
        for (i in segmentValues.indices) {
            val value = segmentValues[i]
            val name = if (i < segmentNames.size) segmentNames[i] else ""
            if (name != null && !name.isEmpty()) {
                combinedSegments.add("$value|$name")
            } else {
                combinedSegments.add(value)
            }
        }
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, combinedSegments.joinToString(","))
        elementController.updateElement(elementId!!, contentValues)
    }

    private fun setupCommonControls(centerTextInput: ElementEditText, size: NumberSeekbar, thick: NumberSeekbar, layer: NumberSeekbar,
                                    normalColor: ElementEditText, pressedColor: ElementEditText, backgroundColor: ElementEditText,
                                    copy: Button, delete: Button) {
        centerTextInput.setTextWithNoTextChangedCallBack(this.centerText)
        // NOTE: The listener is set/overridden in getInfoPage() to handle the switch logic
        centerTextInput.setOnTextChangedListener { text ->
            setCenterText(text)
            save()
        }

        centralXNumberSeekbar!!.setProgressMin(centralXMin)
        centralXNumberSeekbar!!.setProgressMax(centralXMax)
        centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
        centralXNumberSeekbar!!.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, f: Boolean) {
                setElementCentralX(p)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })
        centralYNumberSeekbar!!.setProgressMin(centralYMin)
        centralYNumberSeekbar!!.setProgressMax(centralYMax)
        centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        centralYNumberSeekbar!!.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, f: Boolean) {
                setElementCentralY(p)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })
        size.setProgressMax(Math.min(widthMax, heightMax))
        size.setProgressMin(widthMin)
        size.setValueWithNoCallBack(this.diameter)
        size.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, f: Boolean) {
                setDiameter(p)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })
        thick.setValueWithNoCallBack(this.thick)
        thick.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, f: Boolean) {
                setElementThick(p)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })
        layer.setValueWithNoCallBack(this.layer)
        layer.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, f: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                setElementLayer(layer.value)
                save()
            }
        })

        CrownColorPickerBinder.bind(this, normalColor, { this.normalColor }, this::setElementNormalColor)
        CrownColorPickerBinder.bind(this, pressedColor, { this.pressedColor }, this::setElementPressedColor)
        CrownColorPickerBinder.bind(this, backgroundColor, { this.backgroundColor }, this::setElementBackgroundColor)

        copy.setOnClickListener {
            val cv = ContentValues()
            cv.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_WHEEL_PAD)
            cv.put(COLUMN_INT_ELEMENT_WIDTH, this.diameter)
            cv.put(COLUMN_INT_ELEMENT_HEIGHT, this.diameter)
            cv.put(COLUMN_STRING_ELEMENT_TEXT, this.centerText)
            cv.put(COLUMN_INT_ELEMENT_LAYER, this.layer)
            cv.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + getElementWidth() / 2, centralXMax), centralXMin))
            cv.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
            cv.put(COLUMN_INT_ELEMENT_THICK, this.thick)
            cv.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, this.normalColor)
            cv.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, this.pressedColor)
            cv.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, this.backgroundColor)
            cv.put(COLUMN_INT_ELEMENT_MODE, this.segmentCount)
            cv.put(COLUMN_INT_ELEMENT_SENSE, this.innerRadiusPercent)
            cv.put(COLUMN_INT_ELEMENT_FLAG1, if (this.popupAtScreenCenter) 1 else 0)

            cv.put("extra_attributes", Gson().toJson(buildExtraAttributes()))

            val combinedSegments = ArrayList<String>()
            for (i in segmentValues.indices) {
                val value = segmentValues[i]
                val name = if (i < segmentNames.size) segmentNames[i] else ""
                if (name != null && !name.isEmpty()) {
                    combinedSegments.add("$value|$name")
                } else {
                    combinedSegments.add(value)
                }
            }
            cv.put(COLUMN_STRING_ELEMENT_VALUE, combinedSegments.joinToString(","))
            elementController.addElement(cv)
        }
        delete.setOnClickListener {
            elementController.toggleInfoPage(wheelPadPage)
            elementController.deleteElement(wheelPad)
        }
    }

    protected fun setDiameter(newDiameter: Int) {
        this.diameter = newDiameter
        setElementWidth(newDiameter)
        setElementHeight(newDiameter)
        invalidate()
    }

    private fun buildExtraAttributes(): JsonObject {
        val extraAttrs = JsonObject()
        extraAttrs.addProperty("normalTextColor", this.normalTextColor)
        extraAttrs.addProperty("pressedTextColor", this.pressedTextColor)
        extraAttrs.addProperty("centerTextColor", this.centerTextColor)
        extraAttrs.addProperty("textSizePercent", this.textSizePercent)
        extraAttrs.addProperty("centerTextSizePercent", this.centerTextSizePercent)
        extraAttrs.addProperty("triggerTextSizePercent", this.triggerTextSizePercent)
        extraAttrs.addProperty("previewGroupChildren", this.previewGroupChildren)
        extraAttrs.addProperty(EXTRA_SEGMENT_ALIGNMENT,
            if (this.alignSegmentEdge) SEGMENT_ALIGNMENT_EDGE else SEGMENT_ALIGNMENT_CENTER)
        return extraAttrs
    }

    protected fun setSegmentCount(count: Int) {
        var count = count
        if (count < 2) count = 2
        this.segmentCount = count
        while (segmentValues.size < count) {
            segmentValues.add("null")
            segmentNames.add("")
        }
        while (segmentValues.size > count) {
            segmentValues.removeAt(segmentValues.size - 1)
            segmentNames.removeAt(segmentNames.size - 1)
        }
        invalidate()
    }

    protected fun setSegmentValue(index: Int, value: String?) {
        if (index >= 0 && index < segmentValues.size) {
            segmentValues[index] = value!!
            invalidate()
        }
    }

    protected fun setInnerRadiusPercent(percent: Int) {
        this.innerRadiusPercent = percent
        invalidate()
    }

    protected fun setElementThick(thick: Int) {
        this.thick = thick
        invalidate()
    }

    fun setElementNormalColor(normalColor: Int) {
        this.normalColor = normalColor
        invalidate()
    }

    fun setElementPressedColor(pressedColor: Int) {
        this.pressedColor = pressedColor
        invalidate()
    }

    fun setElementBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
        invalidate()
    }

    fun setElementNormalTextColor(normalTextColor: Int) {
        this.normalTextColor = normalTextColor
        invalidate()
    }

    fun setElementPressedTextColor(pressedTextColor: Int) {
        this.pressedTextColor = pressedTextColor
        invalidate()
    }

    fun setElementCenterTextColor(centerTextColor: Int) {
        this.centerTextColor = centerTextColor
        invalidate()
    }

    protected fun setCenterText(text: String?) {
        this.centerText = text ?: ""
        this.isPopupMode = !this.centerText.isEmpty()
        invalidate()
    }

    override fun updatePage() {
        if (wheelPadPage != null) {
            centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
            centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        }
    }

    private fun getDisplayStringForValue(value: String?): String {
        if (value == null || value.isEmpty() || value == "null") {
            return context.getString(R.string.empty_value)
        }

        if (value.startsWith("gb")) {
            try {
                val elementId = value.substring(2).toLong()
                val element = elementController.findElementById(elementId)
                if (element is GroupButton) {
                    return context.getString(R.string.wheel_value_group, (element as GroupButton).getText())
                } else {
                    return context.getString(R.string.wheel_value_invalid_group)
                }
            } catch (e: NumberFormatException) {
                return context.getString(R.string.wheel_value_bad_group_id)
            }
        }

        val singleKeyValues = value.split("+")
        val keyNames = ArrayList<String>()
        for (singleKeyValue in singleKeyValues) {
            keyNames.add(pageDeviceController.getKeyNameByValue(singleKeyValue))
        }
        return keyNames.joinToString(" + ")
    }

    private fun updateKeyCombinationDisplay(keysContainer: LinearLayout, currentKeys: MutableList<String>, addButton: Button, addGroupButton: Button, dialog: AlertDialog?) {
        keysContainer.removeAllViews()
        val context = keysContainer.context
        val isGroup = !currentKeys.isEmpty() && currentKeys[0].startsWith("gb")

        // 根据是否已选择组按键，更新按钮的可见性
        if (isGroup) {
            addButton.visibility = View.GONE
            addGroupButton.visibility = View.GONE
        } else {
            addButton.visibility = View.VISIBLE
            addGroupButton.visibility = View.VISIBLE
        }

        if (currentKeys.isEmpty()) {
            val emptyText = TextView(context)
            emptyText.setText(R.string.wheel_empty_keys)
            emptyText.setPadding(0, 10, 0, 10)
            keysContainer.addView(emptyText)
        } else if (isGroup) {
            // --- 显示组按键 ---
            try {
                val elementId = currentKeys[0].substring(2).toLong()
                val element = elementController.findElementById(elementId)
                if (element is GroupButton) {
                    val groupButton = element as GroupButton
                    val groupButtonSettingsPage = groupButton.getInfoPage()
                    val groupBtnDisplay = Button(context)
                    groupBtnDisplay.text = context.getString(
                        R.string.wheel_group_button_action,
                        context.getString(R.string.wheel_value_group, groupButton.getText()))
                    groupBtnDisplay.isAllCaps = false

                    // 单击打开组按键设置页
                    groupBtnDisplay.setOnClickListener {
                        if (dialog != null) {
                            dialog.dismiss()
                        }
                        val wheelPadSettingsPage = this@WheelPad.wheelPad.getInfoPage()
                        groupButtonSettingsPage.lastPage = wheelPadSettingsPage
                        groupButtonSettingsPage.setPageReturnListener(object : SuperPageLayout.ReturnListener {
                            override fun returnCallBack() {
                                elementController.superPagesController.openNewPage(wheelPadSettingsPage)
                            }
                        })
                        elementController.getSuperPagesController().openNewPage(groupButtonSettingsPage)
                    }

                    // 长按删除
                    groupBtnDisplay.setOnLongClickListener {
                        // 清空当前分区的按键设置
                        currentKeys.clear()

                        // 更新UI以反映移除
                        updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog)

                        Toast.makeText(context, R.string.wheel_group_removed, Toast.LENGTH_SHORT).show()

                        true // 返回true表示事件已被消费
                    }
                    keysContainer.addView(groupBtnDisplay)
                } else {
                    // 如果找到了元素但类型不对，也显示错误
                    currentKeys.clear()
                    val errorText = TextView(context)
                    errorText.text = context.getString(R.string.wheel_group_wrong_type, elementId)
                    keysContainer.addView(errorText)
                    updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog)
                }
            } catch (e: Exception) {
                // 如果解析或查找失败，显示错误信息并允许重新选择
                currentKeys.clear()
                val errorText = TextView(context)
                errorText.setText(R.string.wheel_group_missing)
                keysContainer.addView(errorText)
            }
        } else {
            // --- 显示普通按键列表 ---
            for (i in currentKeys.indices) {
                val keyIndex = i
                val keyView = TextView(context)
                keyView.text = context.getString(
                    R.string.wheel_key_tap_remove,
                    pageDeviceController.getKeyNameByValue(currentKeys[keyIndex]))
                keyView.textSize = 16f
                keyView.setBackgroundResource(R.drawable.enabled_square)
                keyView.setPadding(20, 15, 20, 15)
                keyView.gravity = Gravity.CENTER

                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 5, 0, 5)
                keyView.layoutParams = params

                keyView.setOnClickListener {
                    currentKeys.removeAt(keyIndex)
                    updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog)
                }
                keysContainer.addView(keyView)
            }
        }
    }

    private fun showKeyCombinationDialog(context: Context, index: Int, valueText: TextView) {
        val builder = AlertDialog.Builder(context, R.style.AppDialogStyle)
        builder.setTitle(context.getString(R.string.wheel_edit_keys_title, index + 1))

        val dialogLayout = LinearLayout(context)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(30, 20, 30, 20)

        val keysContainer = LinearLayout(context)
        keysContainer.orientation = LinearLayout.VERTICAL

        val scrollView = ScrollView(context)
        val scrollParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        )
        scrollParams.setMargins(0, 0, 0, 20)
        scrollView.layoutParams = scrollParams
        scrollView.addView(keysContainer)

        val currentValue = segmentValues[index]
        val currentKeys = ArrayList<String>()
        if (currentValue != null && !currentValue.isEmpty() && currentValue != "null") {
            // 组按键只有一个值 "gb<ID>", 普通按键用 "+" 分隔
            if (currentValue.startsWith("gb")) {
                currentKeys.add(currentValue)
            } else {
                currentKeys.addAll(currentValue.split("+"))
            }
        }

        // --- 新增按钮布局和按钮 ---
        val buttonContainer = LinearLayout(context)
        buttonContainer.orientation = LinearLayout.HORIZONTAL
        buttonContainer.gravity = Gravity.CENTER

        val addButton = Button(context)
        addButton.setText(R.string.wheel_add_key)
        val buttonParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        buttonParams.marginEnd = 10
        addButton.layoutParams = buttonParams

        val addGroupButton = Button(context)
        addGroupButton.setText(R.string.wheel_add_group_key)
        val groupButtonParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        groupButtonParams.marginStart = 10
        addGroupButton.layoutParams = groupButtonParams

        buttonContainer.addView(addButton)
        buttonContainer.addView(addGroupButton)

        dialogLayout.addView(scrollView)
        dialogLayout.addView(buttonContainer)

        val dialog = builder.create()

        // 初始状态更新
        updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog)
        dialog.setView(dialogLayout)

        dialog.setButton(AlertDialog.BUTTON_POSITIVE, context.getString(R.string.dialog_button_ok)) { d, which ->
            val finalValue: String
            if (currentKeys.isEmpty()) {
                finalValue = "null"
            } else if (currentKeys[0].startsWith("gb")) {
                finalValue = currentKeys[0]
            } else {
                finalValue = currentKeys.joinToString("+")
            }
            setSegmentValue(index, finalValue)
            valueText.text = getDisplayStringForValue(finalValue)
            save()
        }
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, context.getString(R.string.dialog_button_cancel)) { d, which -> d.dismiss() }

        // "添加组按键" 的点击逻辑
        addGroupButton.setOnClickListener {
            // 1. 查找所有现有的 GroupButton
            val groupButtons = ArrayList<GroupButton>()
            for (el in elementController.getElements()) {
                if (el is GroupButton) {
                    groupButtons.add(el as GroupButton)
                }
            }

            // 2. 创建选项列表
            val options = ArrayList<String>()
            for (gb in groupButtons) {
                options.add("${gb.getText()} (ID: ${gb.elementId})")
            }
            options.add(context.getString(R.string.wheel_create_new_group))

            // 3. 显示选择对话框
            val groupSelectionDialog = AlertDialog.Builder(context, R.style.AppDialogStyle)
                .setTitle(R.string.wheel_select_group_title)
                .setItems(options.toTypedArray()) { selectionDialog, which ->

                    var selectedValue: String? = null
                    var newElementToEdit: Element? = null

                    if (which == options.size - 1) {
                        // --- 用户选择 "创建新的" ---
                        val cv = GroupButton.getInitialInfo()
                        val newElement = elementController.addElement(cv)
                        if (newElement != null) {
                            selectedValue = "gb" + newElement.elementId
                            newElementToEdit = newElement // 记录下来，以便后续打开其设置页
                        }
                    } else {
                        // --- 用户选择一个现有的 ---
                        val selectedGb = groupButtons[which]
                        selectedValue = "gb" + selectedGb.elementId
                    }

                    if (selectedValue != null) {
                        // 立即设置值并保存
                        setSegmentValue(index, selectedValue)
                        save()

                        // 更新主设置页面上该分区的显示文本
                        valueText.text = getDisplayStringForValue(selectedValue)

                        // 关闭“编辑按键”这个主弹窗
                        dialog.dismiss()

                        // 如果是新创建的，提示用户去配置
                        if (newElementToEdit != null) {
                            Toast.makeText(context, R.string.wheel_new_group_created, Toast.LENGTH_LONG).show()
                            elementController.toggleInfoPage(newElementToEdit.getInfoPage())
                        }
                    }
                }
                .show()
            AppDialogStyler.applySystemChoiceList(groupSelectionDialog, context)
        }

        addButton.setOnClickListener {
            dialog.hide()
            val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                currentKeys.add(key.tag.toString())
                dialog.show()
                updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog)
            }
            pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE)
        }

        dialog.show()
        AppDialogStyler.apply(dialog, context)
    }

    private fun updateValuesContainerUI() {
        if (valuesContainer == null) return
        valuesContainer!!.removeAllViews()
        for (i in 0 until segmentCount) {
            if (i >= segmentValues.size) continue
            val valueView = LayoutInflater.from(context).inflate(R.layout.item_key_value, valuesContainer, false)
            val title = valueView.findViewById<TextView>(R.id.item_key_value_title)
            val nameInput = valueView.findViewById<ElementEditText>(R.id.item_key_value_name)
            val valueText = valueView.findViewById<TextView>(R.id.item_key_value_value)

            title.text = context.getString(R.string.wheel_section_title, i + 1)
            val index = i

            if (index < segmentNames.size) {
                nameInput.setTextWithNoTextChangedCallBack(segmentNames[index])
            }
            nameInput.setOnTextChangedListener { text ->
                if (index < segmentNames.size) {
                    segmentNames[index] = text
                    invalidate()
                    save()
                }
            }

            valueText.text = getDisplayStringForValue(segmentValues[i])

            valueText.setOnClickListener {
                showKeyCombinationDialog(context, index, valueText)
            }
            valuesContainer!!.addView(valueView)
        }
    }
}
