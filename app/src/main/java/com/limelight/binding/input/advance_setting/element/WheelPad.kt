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
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
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
        private const val EXTRA_WHEEL_DIAMETER = "wheelDiameter"
        private const val EXTRA_TRIGGER_DIAMETER = "triggerDiameter"

        private const val WHEEL_MIN_DIAMETER = 150
        private const val TRIGGER_MIN_DIAMETER = 60
        private const val TRIGGER_MAX_DIAMETER = 300
        private const val GLASS_SEGMENT_GAP_DEG = 1.6f

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
            extraAttrs.addProperty("normalTextColor", 0xFFFFFFFFL.toInt())
            extraAttrs.addProperty("pressedTextColor", 0xFFFFFFFFL.toInt())
            extraAttrs.addProperty("centerTextColor", 0xFFFFFFFFL.toInt())
            extraAttrs.addProperty("textSizePercent", 35)
            extraAttrs.addProperty("centerTextSizePercent", 60)
            extraAttrs.addProperty("triggerTextSizePercent", 40)
            extraAttrs.addProperty("previewGroupChildren", true)
            extraAttrs.addProperty(EXTRA_SEGMENT_ALIGNMENT, SEGMENT_ALIGNMENT_CENTER)
            extraAttrs.addProperty(EXTRA_WHEEL_DIAMETER, 400)
            extraAttrs.addProperty(EXTRA_TRIGGER_DIAMETER, 130)
            contentValues.put("extra_attributes", Gson().toJson(extraAttrs))

            return contentValues
        }
    }

    private val pageDeviceController: PageDeviceController = pageDeviceController
    private val wheelPad: WheelPad

    private var diameter: Int              // 轮盘自身直径
    private var triggerDiameter: Int = 0   // 屏幕中心弹出模式下触发按钮的独立直径（也作为 View 尺寸）
    private var currentViewSize: Int = -1
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
    private var centerTextColor: Int = 0
    private var textSizePercent: Int = 0
    private var centerTextSizePercent: Int = 0
    private var triggerTextSizePercent: Int = 0

    private val screenWidth: Int
    private val screenHeight: Int

    private val paintBackground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintEdit = Paint()
    private val paintCenterText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlassFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlassStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintAccentArc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintPointer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textMeasurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val tempOuterRect = RectF()
    private val tempInnerRect = RectF()
    private val segmentPath = Path()
    private val pointerPath = Path()

    // 渐变着色器缓存，避免频繁 GC
    private var cachedNormalShader: Shader? = null
    private var cachedPressedShader: Shader? = null
    private var lastShaderCx = 0f
    private var lastShaderCy = 0f
    private var lastShaderInnerR = 0f
    private var lastShaderOuterR = 0f
    private var lastShaderBgColor = 0
    private var lastShaderPressedColor = 0

    private var cachedTriggerShader: Shader? = null
    private var lastTrigCx = 0f
    private var lastTrigCy = 0f
    private var lastTrigR = 0f
    private var lastTrigBgColor = 0

    // 动画过渡状态
    private var popupAnimProgress = 1.0f
    private var segPopProgress = FloatArray(16)
    private var lastPointerAngleDeg = -90f
    private var lastAnimTimestamp = 0L

    private var activeIndex = -1
    private var lastActiveIndex = -1
    private var isWheelActive = false
    private var popupAtScreenCenter: Boolean
    private var previewGroupChildren = false
    private var alignSegmentEdge = false
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
        widthMin = TRIGGER_MIN_DIAMETER
        heightMax = displayMetrics.heightPixels
        heightMin = TRIGGER_MIN_DIAMETER

        paintBackground.style = Paint.Style.FILL
        paintText.setColor(0xFFFFFFFF.toInt())
        paintText.textSize = 30f
        paintText.textAlign = Paint.Align.CENTER
        paintEdit.style = Paint.Style.STROKE
        paintEdit.strokeWidth = 4f
        paintEdit.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
        paintCenterText.setColor(0xFFFFFFFF.toInt())
        paintCenterText.textSize = 60f
        paintCenterText.textAlign = Paint.Align.CENTER
        paintCenterText.isFakeBoldText = true

        paintGlassFill.style = Paint.Style.FILL
        paintGlassStroke.style = Paint.Style.STROKE
        paintAccentArc.style = Paint.Style.STROKE
        paintAccentArc.strokeCap = Paint.Cap.ROUND
        paintPointer.style = Paint.Style.FILL

        // 历史数据里 width 就是轮盘直径；新数据在中心弹出模式下 width 只是触发按钮直径，
        // 真正的轮盘直径由 extra_attributes.wheelDiameter 保存。
        val savedViewWidth = (attributesMap.get(COLUMN_INT_ELEMENT_WIDTH) as Long).toInt()
        this.currentViewSize = savedViewWidth
        this.diameter = clampWheelDiameter(savedViewWidth)

        val textObj = attributesMap.get(COLUMN_STRING_ELEMENT_TEXT)
        this.centerText = if (textObj != null) textObj as String else ""
        this.isPopupMode = !this.centerText.isEmpty()

        thick = (attributesMap.get(COLUMN_INT_ELEMENT_THICK) as Long).toInt()
        normalColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR) as Long).toInt()
        pressedColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR) as Long).toInt()
        backgroundColor = (attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR) as Long).toInt()
        segmentCount = Math.max(2, (attributesMap.get(COLUMN_INT_ELEMENT_MODE) as Long).toInt())
        innerRadiusPercent = (attributesMap.get(COLUMN_INT_ELEMENT_SENSE) as Long).toInt()

        val popupFlagObj = attributesMap.get(COLUMN_INT_ELEMENT_FLAG1)
        this.popupAtScreenCenter = popupFlagObj == null || (popupFlagObj as Long).toInt() == 1

        this.triggerDiameter = 0

        if (attributesMap.containsKey("extra_attributes")) {
            val extraAttrsJsonString = attributesMap.get("extra_attributes") as String?
            if (extraAttrsJsonString != null && !extraAttrsJsonString.isEmpty()) {
                try {
                    val extraAttrs = Gson().fromJson(extraAttrsJsonString, JsonObject::class.java)

                    this.normalTextColor = if (extraAttrs.has("normalTextColor"))
                        extraAttrs.get("normalTextColor").asInt else 0xFFFFFFFF.toInt()
                    this.centerTextColor = if (extraAttrs.has("centerTextColor"))
                        extraAttrs.get("centerTextColor").asInt else 0xFFFFFFFF.toInt()
                    this.pressedTextColor = if (extraAttrs.has("pressedTextColor"))
                        extraAttrs.get("pressedTextColor").asInt else 0xFFFFFFFF.toInt()
                    this.textSizePercent = if (extraAttrs.has("textSizePercent"))
                        extraAttrs.get("textSizePercent").asInt else 35
                    this.centerTextSizePercent = if (extraAttrs.has("centerTextSizePercent"))
                        extraAttrs.get("centerTextSizePercent").asInt else 60
                    this.triggerTextSizePercent = if (extraAttrs.has("triggerTextSizePercent"))
                        extraAttrs.get("triggerTextSizePercent").asInt else 40
                    this.previewGroupChildren = !extraAttrs.has("previewGroupChildren") ||
                        extraAttrs.get("previewGroupChildren").asBoolean
                    this.alignSegmentEdge = extraAttrs.has(EXTRA_SEGMENT_ALIGNMENT) &&
                        SEGMENT_ALIGNMENT_EDGE == extraAttrs.get(EXTRA_SEGMENT_ALIGNMENT).asString

                    if (extraAttrs.has(EXTRA_WHEEL_DIAMETER)) {
                        this.diameter = clampWheelDiameter(extraAttrs.get(EXTRA_WHEEL_DIAMETER).asInt)
                    }
                    if (extraAttrs.has(EXTRA_TRIGGER_DIAMETER)) {
                        this.triggerDiameter = clampTriggerDiameter(extraAttrs.get(EXTRA_TRIGGER_DIAMETER).asInt)
                    }
                } catch (e: Exception) {
                    initializeDefaultFontAttributes()
                }
            } else {
                initializeDefaultFontAttributes()
            }
        } else {
            initializeDefaultFontAttributes()
        }

        if (this.triggerDiameter <= 0) {
            this.triggerDiameter = computeDefaultTriggerDiameter()
        }

        val valuesString = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE) as String?
        segmentValues = ArrayList()
        segmentNames = ArrayList()
        if (valuesString != null && !valuesString.isEmpty()) {
            val segments = valuesString.split(",")
            for (segment in segments) {
                if (segment.isEmpty()) continue
                val parts = segment.split("|", limit = 2)
                segmentValues.add(parts[0])
                segmentNames.add(if (parts.size > 1) parts[1] else "")
            }
        }
        while (segmentValues.size < segmentCount) {
            segmentValues.add("null")
            segmentNames.add("")
        }

        ensureSegPopCapacity(segmentCount)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 构造阶段还没有 LayoutParams，尺寸同步必须等 View 真正挂载后再做。
        syncViewSizeForCurrentMode()
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

    private fun clampWheelDiameter(size: Int): Int {
        val max = Math.min(widthMax, heightMax)
        return Math.max(WHEEL_MIN_DIAMETER, Math.min(max, size))
    }

    private fun clampTriggerDiameter(size: Int): Int {
        val max = Math.min(TRIGGER_MAX_DIAMETER, Math.min(widthMax, heightMax))
        return Math.max(TRIGGER_MIN_DIAMETER, Math.min(max, size))
    }

    private fun computeDefaultTriggerDiameter(): Int {
        val outerR = Math.max(20f, (this.diameter / 2.0f) - this.thick)
        val innerR = outerR * (this.innerRadiusPercent / 100.0f)
        val visualRadius = innerR + Math.max(5f, this.thick.toFloat())
        return clampTriggerDiameter(Math.round(visualRadius * 2.0f))
    }

    private fun getExpectedViewSize(): Int {
        if (isPopupMode && popupAtScreenCenter) {
            return clampTriggerDiameter(this.triggerDiameter)
        }
        return clampWheelDiameter(this.diameter)
    }

    private fun syncViewSizeForCurrentMode() {
        if (layoutParams == null) {
            return
        }
        val targetSize = getExpectedViewSize()
        if (targetSize != currentViewSize) {
            currentViewSize = targetSize
            setElementWidth(targetSize)
            setElementHeight(targetSize)
        }
    }

    private fun getWheelFillOuterRadius(): Float {
        val wheelRadius = this.diameter / 2.0f
        val popReserve = Math.min(12f, wheelRadius * 0.055f)
        return Math.max(0f, wheelRadius - popReserve - thick)
    }

    private fun getWheelInnerRadiusPx(): Float {
        return getWheelFillOuterRadius() * (innerRadiusPercent / 100.0f)
    }

    private fun getTriggerRadius(): Float {
        if (isPopupMode && popupAtScreenCenter) {
            val inset = Math.max(4f, thick * 0.4f)
            val viewSize = if (width > 0) width.toFloat() else getExpectedViewSize().toFloat()
            return Math.max(10f, (viewSize / 2.0f) - inset)
        }
        return Math.max(10f, getWheelInnerRadiusPx())
    }

    private fun ensureSegPopCapacity(count: Int) {
        if (segPopProgress.size < count) {
            segPopProgress = segPopProgress.copyOf(Math.max(count, segPopProgress.size * 2))
        }
    }

    private fun invalidateWheel() {
        invalidate()
        // 中心弹出模式下轮盘绘制在 View 边界之外，只重绘自身不会刷新屏幕中心区域。
        (parent as? View)?.invalidate()
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
                drawFullWheel(canvas)
                val isActiveOnSitePopup = isPopupMode && !popupAtScreenCenter &&
                    currentMode === ElementController.Mode.Normal && isWheelActive
                if (previewGroupChildren && isActiveOnSitePopup) {
                    drawHoveredGroupButtonChildren(canvas, 0f, 0f)
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
                val childDrawX = child.left - (this@WheelPad.left + wheelTranslateX)
                val childDrawY = child.top - (this@WheelPad.top + wheelTranslateY)
                canvas.translate(childDrawX, childDrawY)
                child.onElementDraw(canvas)
                canvas.restore()
            }
        }
    }

    private fun drawInactivePopupCenter(canvas: Canvas) {
        val centerX = width / 2.0f
        val centerY = height / 2.0f
        val triggerRadius = getTriggerRadius()

        val bgAlpha = Math.max(45, Color.alpha(backgroundColor))
        val innerGlass = Color.argb(Math.round(bgAlpha * 0.65f),
            Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor))
        val outerGlass = Color.argb(Math.round(bgAlpha * 0.92f),
            Color.red(backgroundColor) / 2, Color.green(backgroundColor) / 2, Color.blue(backgroundColor) / 2)

        if (cachedTriggerShader == null || lastTrigCx != centerX || lastTrigCy != centerY ||
            lastTrigR != triggerRadius || lastTrigBgColor != backgroundColor) {
            cachedTriggerShader = RadialGradient(
                centerX, centerY - triggerRadius * 0.25f, Math.max(1f, triggerRadius * 1.2f),
                innerGlass, outerGlass, Shader.TileMode.CLAMP)
            lastTrigCx = centerX
            lastTrigCy = centerY
            lastTrigR = triggerRadius
            lastTrigBgColor = backgroundColor
        }

        paintGlassFill.shader = cachedTriggerShader
        paintGlassFill.setShadowLayer(Math.max(4f, thick * 1.2f), 0f, 2f, 0x66000000)
        canvas.drawCircle(centerX, centerY, triggerRadius, paintGlassFill)
        paintGlassFill.setShadowLayer(0f, 0f, 0f, 0)
        paintGlassFill.shader = null

        // 微透外边
        val glassStroke = Math.max(1.5f, thick * 0.45f)
        paintGlassStroke.strokeWidth = glassStroke
        paintGlassStroke.color = Color.argb(150,
            Color.red(normalColor), Color.green(normalColor), Color.blue(normalColor))
        canvas.drawCircle(centerX, centerY, Math.max(1f, triggerRadius - glassStroke / 2f), paintGlassStroke)

        // 内沿微弱白色高光环（玻璃质感）
        paintGlassStroke.strokeWidth = 1.2f
        paintGlassStroke.color = 0x2EFFFFFF
        canvas.drawCircle(centerX, centerY, Math.max(1f, triggerRadius - glassStroke - 2.5f), paintGlassStroke)

        paintCenterText.setColor(this.centerTextColor)
        val triggerTextSize = (triggerRadius * 2f) * (this.triggerTextSizePercent / 100.0f)
        paintCenterText.setShadowLayer(Math.max(2f, thick * 0.35f), 0f, 1.5f, 0x99000000.toInt())
        drawFittedCenterText(canvas, centerText, centerX, centerY, triggerRadius, triggerTextSize, paintCenterText)
    }

    private fun drawFullWheel(canvas: Canvas) {
        val currentMode = elementController.getMode()
        val centerX = width / 2.0f
        val centerY = height / 2.0f

        // 轮盘尺寸始终由 this.diameter 决定（与 View 触发尺寸彻底解耦）
        val wheelRadius = this.diameter / 2.0f
        val popOutMax = Math.min(12f, wheelRadius * 0.055f)
        val fillOuterRadius = Math.max(0f, wheelRadius - popOutMax - thick)
        val fillInnerRadius = fillOuterRadius * (innerRadiusPercent / 100.0f)

        // 平滑过渡动画
        val now = SystemClock.uptimeMillis()
        val dt = if (lastAnimTimestamp == 0L) 16f
                 else Math.min(32f, Math.max(1f, (now - lastAnimTimestamp).toFloat()))
        lastAnimTimestamp = now
        val step = Math.min(1f, dt * 0.022f)

        var needNextFrame = false
        val isEditPreview = currentMode === ElementController.Mode.Edit && isBeingEdited()
        val targetPopup = if (isEditPreview || !isPopupMode || isWheelActive) 1f else 0f
        if (isEditPreview) {
            popupAnimProgress = 1f
        } else if (Math.abs(targetPopup - popupAnimProgress) > 0.01f) {
            popupAnimProgress += (targetPopup - popupAnimProgress) * Math.min(1f, step * 1.4f)
            needNextFrame = true
        } else {
            popupAnimProgress = targetPopup
        }

        ensureSegPopCapacity(segmentCount)
        for (i in 0 until segmentCount) {
            val targetPop = if (i == activeIndex) 1f else 0f
            if (Math.abs(targetPop - segPopProgress[i]) > 0.01f) {
                segPopProgress[i] += (targetPop - segPopProgress[i]) * Math.min(1f, step * 1.6f)
                needNextFrame = true
            } else {
                segPopProgress[i] = targetPop
            }
        }

        val maxOuterForShader = fillOuterRadius + popOutMax
        if (cachedNormalShader == null || cachedPressedShader == null ||
            lastShaderCx != centerX || lastShaderCy != centerY ||
            lastShaderInnerR != fillInnerRadius || lastShaderOuterR != maxOuterForShader ||
            lastShaderBgColor != backgroundColor || lastShaderPressedColor != pressedColor) {

            val bgA = Color.alpha(backgroundColor)
            val bgInner = Color.argb(Math.max(20, Math.round(bgA * 0.55f)),
                Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor))
            val bgOuter = Color.argb(Math.max(30, Math.round(bgA * 0.88f)),
                Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor))
            cachedNormalShader = RadialGradient(centerX, centerY, Math.max(1f, maxOuterForShader),
                intArrayOf(bgInner, bgOuter),
                floatArrayOf(Math.min(0.95f, fillInnerRadius / Math.max(1f, maxOuterForShader)), 1f),
                Shader.TileMode.CLAMP)

            val prA = Color.alpha(pressedColor)
            val prInner = Color.argb(Math.max(35, Math.round(prA * 0.28f)),
                Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor))
            val prOuter = Color.argb(Math.max(60, Math.round(prA * 0.68f)),
                Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor))
            cachedPressedShader = RadialGradient(centerX, centerY, Math.max(1f, maxOuterForShader),
                intArrayOf(prInner, prOuter),
                floatArrayOf(Math.min(0.95f, fillInnerRadius / Math.max(1f, maxOuterForShader)), 1f),
                Shader.TileMode.CLAMP)

            lastShaderCx = centerX
            lastShaderCy = centerY
            lastShaderInnerR = fillInnerRadius
            lastShaderOuterR = maxOuterForShader
            lastShaderBgColor = backgroundColor
            lastShaderPressedColor = pressedColor
        }

        canvas.save()
        val scale = 0.86f + 0.14f * popupAnimProgress
        canvas.scale(scale, scale, centerX, centerY)

        val sweepAngle = WheelPadGeometry.sweepAngleDegrees(segmentCount)
        val halfGap = if (segmentCount >= 2) GLASS_SEGMENT_GAP_DEG / 2.0f else 0f
        val drawSweep = Math.max(1f, sweepAngle - halfGap * 2f)
        val glassStroke = Math.max(1.2f, thick * 0.35f)

        // 1. 镂空毛玻璃扇区
        for (i in 0 until segmentCount) {
            val pop = segPopProgress[i]
            val isAct = i == activeIndex
            val startAngle = WheelPadGeometry.segmentStartAngleDegrees(i, segmentCount, alignSegmentEdge) + halfGap
            val segInnerR = fillInnerRadius + 1.5f
            val segOuterR = fillOuterRadius + pop * (popOutMax * 0.65f)

            buildAnnularSectorPath(segmentPath, centerX, centerY, segInnerR, segOuterR, startAngle, drawSweep)

            paintGlassFill.shader = if (isAct) cachedPressedShader else cachedNormalShader
            canvas.drawPath(segmentPath, paintGlassFill)
            paintGlassFill.shader = null

            paintGlassStroke.strokeWidth = if (isAct) Math.max(1.8f, glassStroke * 1.4f) else glassStroke
            if (isAct) {
                paintGlassStroke.color = Color.argb(215,
                    Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor))
            } else {
                paintGlassStroke.color = 0x2EFFFFFF
            }
            canvas.drawPath(segmentPath, paintGlassStroke)

            if (pop > 0.05f) {
                val accentR = segOuterR + 2.5f
                tempOuterRect.set(centerX - accentR, centerY - accentR, centerX + accentR, centerY + accentR)
                val padDeg = Math.min(4f, drawSweep * 0.12f)
                paintAccentArc.strokeWidth = Math.max(2.5f, thick * 0.65f)
                paintAccentArc.color = Color.argb(Math.round(230 * pop), 255, 255, 255)
                paintAccentArc.setShadowLayer(8f * pop, 0f, 0f, pressedColor)
                canvas.drawArc(tempOuterRect, startAngle + padDeg,
                    Math.max(1f, drawSweep - padDeg * 2f), false, paintAccentArc)
                paintAccentArc.setShadowLayer(0f, 0f, 0f, 0)
            }
        }

        // 2. 通透半透明中心底盘
        val innerDiscR = Math.max(0f, fillInnerRadius - 1.5f)
        paintBackground.setColor(0x5A0B0F17)
        canvas.drawCircle(centerX, centerY, innerDiscR, paintBackground)

        paintGlassStroke.strokeWidth = Math.max(1.2f, glassStroke)
        paintGlassStroke.color = if (activeIndex != -1)
            Color.argb(190, Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor))
        else 0x33FFFFFF
        canvas.drawCircle(centerX, centerY, innerDiscR, paintGlassStroke)

        // 3. 中心随动方向指针
        if (activeIndex != -1 && innerDiscR > 14f) {
            drawCenterDirectionPointer(canvas, centerX, centerY, innerDiscR, lastPointerAngleDeg)
        }

        // 4. 分区文字（1~2 行智能自适应）
        val ringThickness = fillOuterRadius - fillInnerRadius
        val baseSegmentTextSize = ringThickness * (this.textSizePercent / 100.0f)
        paintText.setShadowLayer(3f, 0f, 1.5f, 0xAA000000.toInt())
        val effectiveSweepDeg = Math.min(120f, Math.max(10f, drawSweep))

        for (i in 0 until segmentCount) {
            if (i >= segmentValues.size) continue
            val pop = segPopProgress[i]
            val startAngle = WheelPadGeometry.segmentStartAngleDegrees(i, segmentCount, alignSegmentEdge)
            val textAngle = startAngle + sweepAngle / 2.0f

            val displayName: String
            if (i < segmentNames.size && segmentNames[i].isNotEmpty()) {
                displayName = segmentNames[i]
            } else {
                displayName = getDisplayStringForValue(segmentValues[i])
            }

            paintText.setColor(if (i == activeIndex) this.pressedTextColor else this.normalTextColor)
            paintText.isFakeBoldText = i == activeIndex

            val textRadius = fillInnerRadius + ringThickness * 0.54f + pop * (popOutMax * 0.35f)
            val targetSize = baseSegmentTextSize + pop * 2.0f
            val maxHeight = Math.max(20f, ringThickness * 0.82f)

            val textRad = Math.toRadians(textAngle.toDouble())
            val textCenterX = centerX + (textRadius * Math.cos(textRad)).toFloat()
            val textCenterY = centerY + (textRadius * Math.sin(textRad)).toFloat()

            drawAdaptiveSectorText(canvas, displayName, textCenterX, textCenterY,
                fillInnerRadius, textRadius, effectiveSweepDeg, targetSize, maxHeight, ringThickness)
        }

        // 5. 中心预览文字
        val showCenterTextInNormalMode = isPopupMode && activeIndex != -1 &&
            currentMode === ElementController.Mode.Normal
        if (showCenterTextInNormalMode || isEditPreview) {
            val displayName: String
            if (showCenterTextInNormalMode && activeIndex < segmentValues.size) {
                if (activeIndex < segmentNames.size && segmentNames[activeIndex].isNotEmpty()) {
                    displayName = segmentNames[activeIndex]
                } else {
                    displayName = getDisplayStringForValue(segmentValues[activeIndex])
                }
            } else {
                displayName = context.getString(R.string.crown_preview)
            }

            paintCenterText.setColor(this.centerTextColor)
            val centerTextSize = fillInnerRadius * 2f * (this.centerTextSizePercent / 100.0f)
            paintCenterText.setShadowLayer(Math.max(2f, centerTextSize * 0.06f), 0f,
                Math.max(1f, centerTextSize * 0.04f), 0x99000000.toInt())
            drawFittedCenterText(canvas, displayName, centerX, centerY, innerDiscR, centerTextSize, paintCenterText)
        }

        canvas.restore()

        if (needNextFrame) {
            postOnAnimation { invalidateWheel() }
        }
    }

    private fun buildAnnularSectorPath(path: Path, cx: Float, cy: Float,
                                       innerR: Float, outerR: Float,
                                       startAngle: Float, sweepAngle: Float) {
        path.reset()
        tempOuterRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        tempInnerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        path.arcTo(tempOuterRect, startAngle, sweepAngle, true)
        path.arcTo(tempInnerRect, startAngle + sweepAngle, -sweepAngle, false)
        path.close()
    }

    private fun drawCenterDirectionPointer(canvas: Canvas, cx: Float, cy: Float, innerR: Float, angleDeg: Float) {
        val arcR = Math.max(4f, innerR - 3f)
        tempInnerRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        paintAccentArc.strokeWidth = 3.2f
        paintAccentArc.color = 0xEEFFFFFF.toInt()
        paintAccentArc.setShadowLayer(6f, 0f, 0f, pressedColor)
        canvas.drawArc(tempInnerRect, angleDeg - 18f, 36f, false, paintAccentArc)
        paintAccentArc.setShadowLayer(0f, 0f, 0f, 0)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angleDeg)
        pointerPath.reset()
        pointerPath.moveTo(innerR + 2f, 0f)
        pointerPath.lineTo(innerR - 6f, -4.5f)
        pointerPath.lineTo(innerR - 6f, 4.5f)
        pointerPath.close()
        paintPointer.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(pointerPath, paintPointer)
        canvas.restore()
    }

    private fun computeSectorChordWidth(innerR: Float, textRadius: Float,
                                        effectiveSweepDeg: Float, blockHeight: Float): Float {
        // 文本块上下各有半高，下边缘更靠近内圈（更窄），按偏下边缘算弦长可防撞线
        val chordRadius = Math.max(innerR + 4f, textRadius - blockHeight * 0.32f)
        return (2.0 * chordRadius * Math.sin(Math.toRadians(effectiveSweepDeg / 2.0))).toFloat() * 0.90f
    }

    private fun computeCircleChordWidth(safeRadius: Float, blockHeight: Float): Float {
        val halfH = Math.min(safeRadius * 0.76f, blockHeight * 0.44f)
        return (2.0 * Math.sqrt(Math.max(1f, safeRadius * safeRadius - halfH * halfH).toDouble())).toFloat()
    }

    private fun drawAdaptiveSectorText(canvas: Canvas, text: String, cx: Float, cy: Float,
                                       innerR: Float, textRadius: Float, effectiveSweepDeg: Float,
                                       targetSize: Float, maxHeight: Float, ringThickness: Float) {
        if (text.isEmpty()) return

        val minSize = Math.min(targetSize, Math.max(14f, Math.min(20f, ringThickness * 0.08f)))
        var size1 = Math.min(targetSize, maxHeight)
        var maxW1 = computeSectorChordWidth(innerR, textRadius, effectiveSweepDeg, size1)

        paintText.textSize = size1
        var measured1 = paintText.measureText(text)
        if (measured1 > maxW1 && maxW1 > 4f) {
            size1 = Math.max(minSize, size1 * ((maxW1 * 0.94f) / measured1))
            paintText.textSize = size1
            maxW1 = computeSectorChordWidth(innerR, textRadius, effectiveSweepDeg, size1)
            measured1 = paintText.measureText(text)
            if (measured1 > maxW1 && size1 > minSize) {
                size1 = Math.max(minSize, size1 * ((maxW1 * 0.94f) / measured1))
                paintText.textSize = size1
                maxW1 = computeSectorChordWidth(innerR, textRadius, effectiveSweepDeg, size1)
                measured1 = paintText.measureText(text)
            }
        }

        // 短词或单行已经足够大时，直接单行绘制
        val canTryTwoLines = text.length >= 4 && maxHeight >= minSize * 2.25f
        val preferOneLine = measured1 <= maxW1 && size1 >= targetSize * 0.65f
        if (!canTryTwoLines || preferOneLine) {
            val finalOne = ellipsizeWithFallback(text, paintText, maxW1, size1)
            drawSingleLineCentered(canvas, finalOne, cx, cy, paintText)
            return
        }

        // 尝试拆成居中 2 行，看看字号能不能明显变大
        val lines = splitBalancedTwoLines(text)
        if (lines[1].isEmpty()) {
            val finalOne = ellipsizeWithFallback(text, paintText, maxW1, size1)
            drawSingleLineCentered(canvas, finalOne, cx, cy, paintText)
            return
        }

        var size2 = Math.min(targetSize * 0.85f, maxHeight / 2.25f)
        paintText.textSize = size2
        var maxLineW2 = Math.max(paintText.measureText(lines[0]), paintText.measureText(lines[1]))
        var maxW2 = computeSectorChordWidth(innerR, textRadius, effectiveSweepDeg, size2 * 2.15f)

        if (maxLineW2 > maxW2 && maxW2 > 4f) {
            size2 = Math.max(minSize, size2 * ((maxW2 * 0.94f) / maxLineW2))
            paintText.textSize = size2
            maxW2 = computeSectorChordWidth(innerR, textRadius, effectiveSweepDeg, size2 * 2.15f)
            maxLineW2 = Math.max(paintText.measureText(lines[0]), paintText.measureText(lines[1]))
            if (maxLineW2 > maxW2 && size2 > minSize) {
                size2 = Math.max(minSize, size2 * ((maxW2 * 0.94f) / maxLineW2))
                paintText.textSize = size2
                maxW2 = computeSectorChordWidth(innerR, textRadius, effectiveSweepDeg, size2 * 2.15f)
            }
        }

        // 2 行能比单行大 15% 以上，或者单行原本放不下时，采用 2 行
        if (size2 > size1 * 1.15f || measured1 > maxW1) {
            val l1 = ellipsizeWithFallback(lines[0], paintText, maxW2, size2)
            val l2 = ellipsizeWithFallback(lines[1], paintText, maxW2, size2)
            drawTwoLinesCentered(canvas, l1, l2, cx, cy, paintText)
        } else {
            paintText.textSize = size1
            val finalOne = ellipsizeWithFallback(text, paintText, maxW1, size1)
            drawSingleLineCentered(canvas, finalOne, cx, cy, paintText)
        }
    }

    private fun drawFittedCenterText(canvas: Canvas, text: String, cx: Float, cy: Float,
                                     discRadius: Float, targetSize: Float, paint: Paint) {
        if (text.isEmpty() || discRadius <= 4f) return

        val safeRadius = discRadius * 0.86f
        val maxHeight = safeRadius * 1.50f
        val minSize = Math.min(targetSize, Math.max(12f, Math.min(18f, discRadius * 0.16f)))

        var size1 = Math.min(targetSize, maxHeight)
        var maxW1 = computeCircleChordWidth(safeRadius, size1)
        paint.textSize = size1
        var measured1 = paint.measureText(text)

        if (measured1 > maxW1 && maxW1 > 4f) {
            size1 = Math.max(minSize, size1 * ((maxW1 * 0.94f) / measured1))
            paint.textSize = size1
            maxW1 = computeCircleChordWidth(safeRadius, size1)
            measured1 = paint.measureText(text)
            if (measured1 > maxW1 && size1 > minSize) {
                size1 = Math.max(minSize, size1 * ((maxW1 * 0.94f) / measured1))
                paint.textSize = size1
                maxW1 = computeCircleChordWidth(safeRadius, size1)
                measured1 = paint.measureText(text)
            }
        }

        val canTryTwoLines = text.length >= 4 && maxHeight >= minSize * 2.25f
        val preferOneLine = measured1 <= maxW1 && size1 >= targetSize * 0.65f
        if (!canTryTwoLines || preferOneLine) {
            val finalOne = ellipsizeWithFallback(text, paint, maxW1, size1)
            drawSingleLineCentered(canvas, finalOne, cx, cy, paint)
            return
        }

        val lines = splitBalancedTwoLines(text)
        if (lines[1].isEmpty()) {
            val finalOne = ellipsizeWithFallback(text, paint, maxW1, size1)
            drawSingleLineCentered(canvas, finalOne, cx, cy, paint)
            return
        }

        var size2 = Math.min(targetSize * 0.85f, maxHeight / 2.25f)
        paint.textSize = size2
        var maxLineW2 = Math.max(paint.measureText(lines[0]), paint.measureText(lines[1]))
        var maxW2 = computeCircleChordWidth(safeRadius, size2 * 2.15f)

        if (maxLineW2 > maxW2 && maxW2 > 4f) {
            size2 = Math.max(minSize, size2 * ((maxW2 * 0.94f) / maxLineW2))
            paint.textSize = size2
            maxW2 = computeCircleChordWidth(safeRadius, size2 * 2.15f)
            maxLineW2 = Math.max(paint.measureText(lines[0]), paint.measureText(lines[1]))
            if (maxLineW2 > maxW2 && size2 > minSize) {
                size2 = Math.max(minSize, size2 * ((maxW2 * 0.94f) / maxLineW2))
                paint.textSize = size2
                maxW2 = computeCircleChordWidth(safeRadius, size2 * 2.15f)
            }
        }

        if (size2 > size1 * 1.15f || measured1 > maxW1) {
            val l1 = ellipsizeWithFallback(lines[0], paint, maxW2, size2)
            val l2 = ellipsizeWithFallback(lines[1], paint, maxW2, size2)
            drawTwoLinesCentered(canvas, l1, l2, cx, cy, paint)
        } else {
            paint.textSize = size1
            val finalOne = ellipsizeWithFallback(text, paint, maxW1, size1)
            drawSingleLineCentered(canvas, finalOne, cx, cy, paint)
        }
    }

    private fun splitBalancedTwoLines(text: String): Array<String> {
        val len = text.length
        val mid = len / 2
        var bestSplit = -1
        var bestDist = Int.MAX_VALUE

        val minIdx = Math.max(1, len / 4)
        val maxIdx = Math.min(len - 1, len * 3 / 4)
        for (i in minIdx..maxIdx) {
            val prev = text[i - 1]
            val cur = text[i]
            val breakAfter = prev == ' ' || prev == ']' || prev == '+' || prev == '-' ||
                prev == '|' || prev == '/'
            val breakBefore = cur == ' ' || cur == '['
            if (breakAfter || breakBefore) {
                val dist = Math.abs(i - mid)
                if (dist < bestDist) {
                    bestDist = dist
                    bestSplit = i
                }
            }
        }

        if (bestSplit == -1) {
            bestSplit = mid
        }
        if (bestSplit > 0 && bestSplit < len && Character.isLowSurrogate(text[bestSplit])) {
            bestSplit++
        }
        if (bestSplit <= 0 || bestSplit >= len) {
            return arrayOf(text, "")
        }

        val l1 = text.substring(0, bestSplit).trim()
        val l2 = text.substring(bestSplit).trim()
        if (l1.isEmpty() || l2.isEmpty()) {
            return arrayOf(text, "")
        }
        return arrayOf(l1, l2)
    }

    private fun ellipsizeWithFallback(text: String, paint: Paint, maxWidth: Float, currentSize: Float): String {
        if (maxWidth <= 4f) return text
        if (paint.measureText(text) <= maxWidth) return text

        textMeasurePaint.set(paint)
        val result = TextUtils.ellipsize(text, textMeasurePaint, maxWidth, TextUtils.TruncateAt.END).toString()
        if (result.isEmpty()) {
            val firstChar = text.substring(0, 1)
            val singleW = paint.measureText(firstChar)
            if (singleW > maxWidth && singleW > 0f) {
                paint.textSize = Math.max(8f, currentSize * ((maxWidth * 0.92f) / singleW))
            }
            return firstChar
        }
        return result
    }

    private fun drawSingleLineCentered(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
        val fm = paint.fontMetrics
        val baselineY = cy - (fm.ascent + fm.descent) / 2.0f
        canvas.drawText(text, cx, baselineY, paint)
    }

    private fun drawTwoLinesCentered(canvas: Canvas, l1: String, l2: String, cx: Float, cy: Float, paint: Paint) {
        val fm = paint.fontMetrics
        val lineStep = (fm.descent - fm.ascent) * 1.08f
        val baseCenter = cy - (fm.ascent + fm.descent) / 2.0f
        canvas.drawText(l1, cx, baseCenter - lineStep / 2.0f, paint)
        canvas.drawText(l2, cx, baseCenter + lineStep / 2.0f, paint)
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
            val dx = event.x - width / 2.0f
            val dy = event.y - height / 2.0f
            if (Math.hypot(dx.toDouble(), dy.toDouble()) <= getTriggerRadius()) {
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
            val dx = event.x - width / 2.0f
            val dy = event.y - height / 2.0f
            val distance = Math.hypot(dx.toDouble(), dy.toDouble())

            val outerRadius = getWheelFillOuterRadius()
            val innerRadius = getWheelInnerRadiusPx()

            if (distance > innerRadius && distance < outerRadius) {
                activeIndex = WheelPadGeometry.segmentIndexForVector(dx, dy, segmentCount, alignSegmentEdge)
                lastPointerAngleDeg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            } else {
                activeIndex = -1
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeIndex = -1
        }
        updateSendingState()
        invalidateWheel()
    }

    private fun handlePopupModeTouchEvent(event: MotionEvent) {
        val dx = event.x - width / 2.0f
        val dy = event.y - height / 2.0f
        val distance = Math.hypot(dx.toDouble(), dy.toDouble())
        val triggerRadius = getTriggerRadius()
        // 屏幕中心模式：轮盘不在手指下，手指的物理边界就是触发按钮本身；
        // 原地弹出模式：触发圆本身就是轮盘内圈，两者天然相等。
        val segmentInnerRadius = if (popupAtScreenCenter) triggerRadius else getWheelInnerRadiusPx()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (distance <= triggerRadius) {
                    isWheelActive = true
                    popupAnimProgress = 0.0f
                    // 复位时间戳，否则首帧 dt 会被夹到上限，弹出动画一帧就结束了。
                    lastAnimTimestamp = 0L
                    elementController.buttonVibrator()
                    invalidateWheel()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isWheelActive) {
                    hoveredGroupButton = null
                    lastPointerAngleDeg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

                    if (distance > segmentInnerRadius) {
                        activeIndex = WheelPadGeometry.segmentIndexForVector(dx, dy, segmentCount, alignSegmentEdge)

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
                                    hoveredGroupButton = null
                                }
                            }
                        }
                    } else {
                        activeIndex = -1
                    }
                    invalidateWheel()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isWheelActive && activeIndex != -1 && activeIndex < segmentValues.size) {
                    val value = segmentValues[activeIndex]
                    val handlers = getHandlersForValueNow(value)

                    for (handler in handlers) {
                        handler.sendEvent(true)
                    }

                    if (!handlers.isEmpty()) {
                        elementController.getHandler().postDelayed({
                            for (i in handlers.indices.reversed()) {
                                handlers[i].sendEvent(false)
                            }
                        }, 30L)
                    }
                }
                resetWheelState()
            }
            MotionEvent.ACTION_CANCEL -> resetWheelState()
        }
    }

    private fun resetWheelState() {
        isWheelActive = false
        activeIndex = -1
        hoveredGroupButton = null
        invalidateWheel()
    }

    private fun updateSendingState() {
        if (activeIndex != lastActiveIndex) {
            if (lastActiveIndex != -1 && lastActiveIndex < segmentValues.size) {
                val lastValue = segmentValues[lastActiveIndex]
                val lastHandlers = getHandlersForValueNow(lastValue)
                for (i in lastHandlers.indices.reversed()) {
                    lastHandlers[i].sendEvent(false)
                }
            }

            if (activeIndex != -1 && activeIndex < segmentValues.size) {
                val activeValue = segmentValues[activeIndex]
                val activeHandlers = getHandlersForValueNow(activeValue)
                for (handler in activeHandlers) {
                    handler.sendEvent(true)
                }
            }
            lastActiveIndex = activeIndex
        }
    }

    override fun getCrownInspectorTitle(): String {
        return context.getString(R.string.layout_page_wheel_pad_text_57047)
    }

    override fun getInfoPage(): SuperPageLayout {
        if (wheelPadPage == null) {
            wheelPadPage = LayoutInflater.from(context).inflate(R.layout.page_wheel_pad_clean, null) as SuperPageLayout
            centralXNumberSeekbar = wheelPadPage!!.findViewById(R.id.page_wheel_pad_central_x)
            centralYNumberSeekbar = wheelPadPage!!.findViewById(R.id.page_wheel_pad_central_y)
            valuesContainer = wheelPadPage!!.findViewById(R.id.page_wheel_pad_values_container)
        }

        val wheelPadPage = wheelPadPage!!
        val centerTextInput = wheelPadPage.findViewById<ElementEditText>(R.id.page_wheel_pad_center_text)
        val popupSwitch = wheelPadPage.findViewById<SwitchCompat>(R.id.page_wheel_pad_popup_at_center_switch)
        val previewGroupSwitch = wheelPadPage.findViewById<SwitchCompat>(R.id.page_wheel_pad_preview_group_switch)
        val popupOptionsContainer = wheelPadPage.findViewById<LinearLayout>(R.id.page_wheel_pad_popup_options_container)
        val triggerSizeNumberSeekbar = wheelPadPage.findViewById<NumberSeekbar>(R.id.page_wheel_pad_trigger_size)

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

        triggerSizeNumberSeekbar.setProgressMin(TRIGGER_MIN_DIAMETER)
        triggerSizeNumberSeekbar.setProgressMax(Math.min(TRIGGER_MAX_DIAMETER, Math.min(widthMax, heightMax)))
        triggerSizeNumberSeekbar.setValueWithNoCallBack(this.triggerDiameter)
        triggerSizeNumberSeekbar.visibility = if (isPopupMode && popupAtScreenCenter) View.VISIBLE else View.GONE
        triggerSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setTriggerDiameter(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        popupSwitch.isChecked = this.popupAtScreenCenter
        popupSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            this.popupAtScreenCenter = isChecked
            triggerSizeNumberSeekbar.visibility = if (isPopupMode && isChecked) View.VISIBLE else View.GONE
            syncViewSizeForCurrentMode()
            invalidateWheel()
            save()
        }

        previewGroupSwitch.isChecked = this.previewGroupChildren
        previewGroupSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            this.previewGroupChildren = isChecked
            save()
        }

        val isPopup = !this.centerText.isEmpty()
        popupOptionsContainer.visibility = if (isPopup) View.VISIBLE else View.GONE

        centerTextInput.setOnTextChangedListener { text ->
            setCenterText(text)
            val isPopupNow = !text.isEmpty()
            popupOptionsContainer.visibility = if (isPopupNow) View.VISIBLE else View.GONE
            triggerSizeNumberSeekbar.visibility =
                if (isPopupNow && popupAtScreenCenter) View.VISIBLE else View.GONE
            invalidateWheel()
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
                    invalidateWheel()
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

        textSizeNumberSeekbar.setProgressMin(10)
        textSizeNumberSeekbar.setProgressMax(100)
        textSizeNumberSeekbar.setValueWithNoCallBack(this.textSizePercent)
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                textSizePercent = progress
                invalidateWheel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        centerTextSizeNumberSeekbar.setProgressMin(10)
        centerTextSizeNumberSeekbar.setProgressMax(150)
        centerTextSizeNumberSeekbar.setValueWithNoCallBack(this.centerTextSizePercent)
        centerTextSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                centerTextSizePercent = progress
                invalidateWheel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        CrownColorPickerBinder.bind(this, normalTextColorElementEditText, { this.normalTextColor }) { color ->
            this.normalTextColor = color
            invalidateWheel()
        }
        CrownColorPickerBinder.bind(this, pressedTextColorElementEditText, { this.pressedTextColor }) { color ->
            this.pressedTextColor = color
            invalidateWheel()
        }
        CrownColorPickerBinder.bind(this, centerTextColorElementEditText, { this.centerTextColor }) { color ->
            this.centerTextColor = color
            invalidateWheel()
        }

        triggerTextSizeNumberSeekbar.setProgressMin(5)
        triggerTextSizeNumberSeekbar.setProgressMax(150)
        triggerTextSizeNumberSeekbar.setValueWithNoCallBack(this.triggerTextSizePercent)
        triggerTextSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                triggerTextSizePercent = progress
                invalidateWheel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        updateValuesContainerUI()
        return wheelPadPage
    }

    override fun save() {
        val viewSize = getExpectedViewSize()
        val contentValues = ContentValues()
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, viewSize)
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, viewSize)
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
            if (name.isNotEmpty()) {
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
        size.setProgressMin(WHEEL_MIN_DIAMETER)
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
            val viewSize = getExpectedViewSize()
            val cv = ContentValues()
            cv.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_WHEEL_PAD)
            cv.put(COLUMN_INT_ELEMENT_WIDTH, viewSize)
            cv.put(COLUMN_INT_ELEMENT_HEIGHT, viewSize)
            cv.put(COLUMN_STRING_ELEMENT_TEXT, this.centerText)
            cv.put(COLUMN_INT_ELEMENT_LAYER, this.layer)
            cv.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + viewSize / 2, centralXMax), centralXMin))
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
                if (name.isNotEmpty()) {
                    combinedSegments.add("$value|$name")
                } else {
                    combinedSegments.add(value)
                }
            }
            cv.put(COLUMN_STRING_ELEMENT_VALUE, combinedSegments.joinToString(","))
            elementController.addElement(cv)
        }
        delete.setOnClickListener {
            elementController.toggleInfoPage(wheelPadPage!!)
            elementController.deleteElement(wheelPad)
        }
    }

    protected fun setDiameter(newDiameter: Int) {
        this.diameter = clampWheelDiameter(newDiameter)
        syncViewSizeForCurrentMode()
        invalidateWheel()
    }

    protected fun setTriggerDiameter(newTriggerDiameter: Int) {
        this.triggerDiameter = clampTriggerDiameter(newTriggerDiameter)
        syncViewSizeForCurrentMode()
        invalidateWheel()
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
        extraAttrs.addProperty(EXTRA_WHEEL_DIAMETER, this.diameter)
        extraAttrs.addProperty(EXTRA_TRIGGER_DIAMETER, this.triggerDiameter)
        return extraAttrs
    }

    protected fun setSegmentCount(count: Int) {
        var count = count
        if (count < 2) count = 2
        this.segmentCount = count
        ensureSegPopCapacity(count)
        while (segmentValues.size < count) {
            segmentValues.add("null")
            segmentNames.add("")
        }
        while (segmentValues.size > count) {
            segmentValues.removeAt(segmentValues.size - 1)
            segmentNames.removeAt(segmentNames.size - 1)
        }
        invalidateWheel()
    }

    protected fun setSegmentValue(index: Int, value: String?) {
        if (index >= 0 && index < segmentValues.size) {
            segmentValues[index] = value!!
            invalidateWheel()
        }
    }

    protected fun setInnerRadiusPercent(percent: Int) {
        this.innerRadiusPercent = percent
        invalidateWheel()
    }

    protected fun setElementThick(thick: Int) {
        this.thick = thick
        invalidateWheel()
    }

    fun setElementNormalColor(normalColor: Int) {
        this.normalColor = normalColor
        invalidateWheel()
    }

    fun setElementPressedColor(pressedColor: Int) {
        this.pressedColor = pressedColor
        invalidateWheel()
    }

    fun setElementBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
        invalidateWheel()
    }

    fun setElementNormalTextColor(normalTextColor: Int) {
        this.normalTextColor = normalTextColor
        invalidateWheel()
    }

    fun setElementPressedTextColor(pressedTextColor: Int) {
        this.pressedTextColor = pressedTextColor
        invalidateWheel()
    }

    fun setElementCenterTextColor(centerTextColor: Int) {
        this.centerTextColor = centerTextColor
        invalidateWheel()
    }

    protected fun setCenterText(text: String?) {
        this.centerText = text ?: ""
        this.isPopupMode = !this.centerText.isEmpty()
        syncViewSizeForCurrentMode()
        invalidateWheel()
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
                        elementController.superPagesController.openNewPage(groupButtonSettingsPage)
                    }

                    groupBtnDisplay.setOnLongClickListener {
                        currentKeys.clear()
                        updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog)
                        Toast.makeText(context, R.string.wheel_group_removed, Toast.LENGTH_SHORT).show()
                        true
                    }
                    keysContainer.addView(groupBtnDisplay)
                } else {
                    currentKeys.clear()
                    addButton.visibility = View.VISIBLE
                    addGroupButton.visibility = View.VISIBLE
                    val errorText = TextView(context)
                    errorText.text = context.getString(R.string.wheel_group_wrong_type, elementId)
                    keysContainer.addView(errorText)
                }
            } catch (e: Exception) {
                currentKeys.clear()
                addButton.visibility = View.VISIBLE
                addGroupButton.visibility = View.VISIBLE
                val errorText = TextView(context)
                errorText.setText(R.string.wheel_group_missing)
                keysContainer.addView(errorText)
            }
        } else {
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
        if (currentValue.isNotEmpty() && currentValue != "null") {
            if (currentValue.startsWith("gb")) {
                currentKeys.add(currentValue)
            } else {
                currentKeys.addAll(currentValue.split("+"))
            }
        }

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

        addGroupButton.setOnClickListener {
            val groupButtons = ArrayList<GroupButton>()
            for (el in elementController.elements) {
                if (el is GroupButton) {
                    groupButtons.add(el as GroupButton)
                }
            }

            val options = ArrayList<String>()
            for (gb in groupButtons) {
                options.add("${gb.getText()} (ID: ${gb.elementId})")
            }
            options.add(context.getString(R.string.wheel_create_new_group))

            val groupSelectionDialog = AlertDialog.Builder(context, R.style.AppDialogStyle)
                .setTitle(R.string.wheel_select_group_title)
                .setItems(options.toTypedArray()) { selectionDialog, which ->

                    var selectedValue: String? = null
                    var newElementToEdit: Element? = null

                    if (which == options.size - 1) {
                        val cv = GroupButton.getInitialInfo()
                        val newElement = elementController.addElement(cv)
                        if (newElement != null) {
                            selectedValue = "gb" + newElement.elementId
                            newElementToEdit = newElement
                        }
                    } else {
                        val selectedGb = groupButtons[which]
                        selectedValue = "gb" + selectedGb.elementId
                    }

                    if (selectedValue != null) {
                        setSegmentValue(index, selectedValue)
                        save()
                        valueText.text = getDisplayStringForValue(selectedValue)
                        dialog.dismiss()

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
                    invalidateWheel()
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
