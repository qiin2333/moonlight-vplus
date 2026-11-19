package com.limelight.binding.input.advance_setting.element

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.os.Build
import android.preference.PreferenceManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.limelight.utils.AppSwitchStyle

import com.limelight.Game
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.input.advance_setting.ControllerManager
import com.limelight.binding.input.advance_setting.CrownConfigChoiceFactory
import com.limelight.binding.input.advance_setting.CrownConfigChoice
import com.limelight.binding.input.advance_setting.DirectConfigAction
import com.limelight.binding.input.advance_setting.DirectConfigSwitchState
import com.limelight.binding.input.advance_setting.PageDeviceController
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController

import java.util.ArrayList
import java.util.HashMap

class ElementController(private val controllerManager: ControllerManager, layout: FrameLayout, context: Context) {

    // 空按键
    private val context: Context = context
    private val game: Game = context as Game
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var currentToast: Toast? = null
    private val directConfigSwitch = DirectConfigSwitchState()
    private var dispatchingElement: Element? = null

    private val pageDeviceController: PageDeviceController = controllerManager.pageDeviceController!!

    private var gamepadInputContext = GamepadInputContext()

    private val elementList = ArrayList<Element>()

    val elements: List<Element>
        get() = elementList
    private var elementIds: List<Long>? = null
    private var keyEventRunnableMap: MutableMap<Short, Runnable> = HashMap()
    private var mouseEventRunnableMap: MutableMap<Int, Runnable> = HashMap()
    private var elementsLayout: FrameLayout = layout
    private var mode: Mode = Mode.Normal
    private var pageEdit: SuperPageLayout
    private var lastElementSettingPage: SuperPageLayout? = null
    private val bottomViewAmount: Int
    private var editGridView: EditGridView
    private var editGridWidth = 1
    private var currentConfigId: Long = 0
    private var selectedElement: Element? = null
    private var gameVibrator = false
    private var buttonVibrator = false

    // 滚轮按住事件管理
    private var mouseScrollRunnableMap: MutableMap<Int, Runnable> = HashMap()
    private var alignmentSnapEnabled = true

    // 拖动编辑开关变量
    private var dragEditEnabled = true

    init {
        this.pageEdit = LayoutInflater.from(context).inflate(R.layout.page_edit, null) as SuperPageLayout
        (pageEdit.findViewById<View>(R.id.crown_page_title_text) as TextView)
            .setText(R.string.game_menu_edit_mode)
        (pageEdit.findViewById<View>(R.id.crown_page_subtitle) as TextView)
            .setText(R.string.crown_edit_toolbar_hint)
        pageEdit.findViewById<View>(R.id.crown_page_subtitle).setVisibility(View.VISIBLE)
        this.editGridView = EditGridView(context)
        this.bottomViewAmount = elementsLayout.childCount
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        this.alignmentSnapEnabled = preferences.getBoolean(PREF_CROWN_ALIGNMENT_SNAP_ENABLED, true)
        initEditPage()
    }

    interface SendEventHandler {
        fun sendEvent(down: Boolean)

        /**
         * Delivers a DigitalSwitchButton transition and identifies cleanup releases.
         * Existing handlers retain their normal press/release behavior by default.
         */
        fun sendDigitalSwitchEvent(down: Boolean, cleanup: Boolean) {
            sendEvent(down)
        }

        fun sendEvent(analog1: Int, analog2: Int)
    }

    enum class Mode {
        Normal,
        Edit,
        Select
    }

    class GamepadInputContext {
        @JvmField
        var inputMap: Short = 0x0000
        @JvmField
        var leftTrigger: Byte = 0x00
        @JvmField
        var rightTrigger: Byte = 0x00
        @JvmField
        var rightStickX: Short = 0x0000
        @JvmField
        var rightStickY: Short = 0x0000
        @JvmField
        var leftStickX: Short = 0x0000
        @JvmField
        var leftStickY: Short = 0x0000
    }

    class SnapResult(val centralX: Int, val centralY: Int)

    private class AxisSnap(center: Int) {
        private var center = center
        private var guideOffset = 0
        private var distance = Integer.MAX_VALUE
        private var matched = false

        fun consider(candidateCenter: Int, movingOffset: Int, targetGuide: Int, threshold: Int) {
            val candidateGuide = candidateCenter + movingOffset
            val candidateDistance = Math.abs(candidateGuide - targetGuide)
            if (candidateDistance > threshold || candidateDistance >= distance) {
                return
            }

            this.center = targetGuide - movingOffset
            guideOffset = movingOffset
            distance = candidateDistance
            matched = true
        }

        fun getCenter(): Int {
            return center
        }

        fun getGuide(finalCenter: Int): Float {
            return if (matched) finalCenter + guideOffset.toFloat() else EditGridView.NO_GUIDE
        }
    }

    companion object {
        // 空按键
        private const val SPECIAL_KEY_NULL = "null"
        // 手柄左摇杆
        private const val SPECIAL_KEY_GAMEPAD_LEFT_STICK = "LS"
        // 手柄右摇杆
        private const val SPECIAL_KEY_GAMEPAD_RIGHT_STICK = "RS"
        // 手柄左触发器
        private const val SPECIAL_KEY_GAMEPAD_LEFT_TRIGGER = "lt"
        // 手柄右触发器
        private const val SPECIAL_KEY_GAMEPAD_RIGHT_TRIGGER = "rt"
        // 滚轮上滚
        private const val SPECIAL_KEY_MOUSE_SCROLL_UP = "SU"
        // 滚轮下滚
        private const val SPECIAL_KEY_MOUSE_SCROLL_DOWN = "SD"
        private const val SPECIAL_KEY_MOUSE_MODE_SWITCH = "MMS"
        private const val SPECIAL_KEY_CLASSIC_MOUSE_SWITCH = "CMS" // 经典鼠标
        private const val SPECIAL_KEY_TRACKPAD_MODE = "TPM"         // 触控板
        private const val SPECIAL_KEY_MULTI_TOUCH_MODE = "MTM"      // 多点触控
        private const val SPECIAL_KEY_MOUSE_ENABLE_SWITCH = "MES"
        private const val SPECIAL_KEY_PC_KEYBOARD_SWITCH = "PKS"
        private const val SPECIAL_KEY_ANDROID_KEYBOARD_SWITCH = "AKS"
        // 切换配置
        private const val SPECIAL_KEY_CONFIG_SWITCH = "CSW"
        private const val SPECIAL_KEY_PAN_ZOOM_MODE = "PZM"
        private const val SPECIAL_KEY_OPEN_GAME_MENU = "OGM"
        private const val SPECIAL_KEY_EDIT_MODE_SWITCH = "EMS" // 编辑模式
        private const val SPECIAL_KEY_MOUSE_MOVE_ONLY = "MMO" // 仅鼠标移动
        private const val SPECIAL_KEY_DS5_TOUCHPAD_CYCLE = "DSC" // DS5 触摸板 开/关

        private val CROWN_DETAIL_ACTION_BAR_TAG = "crown_detail_action_bar"
        private val CROWN_INSPECTOR_COMPACT_TAG = "crown_inspector_compact"
        private val PREF_CROWN_ALIGNMENT_SNAP_ENABLED = "crown_alignment_snap_enabled"
        private const val ALIGNMENT_SNAP_THRESHOLD_DP = 8

        private const val MOUSE_SCROLL_INITIAL_DELAY = 150 // 初始延迟（毫秒）
        private var MOUSE_SCROLL_REPEAT_INTERVAL = 100 // 重复间隔（毫秒）

        @JvmStatic
        fun setMouseScrollRepeatInterval(interval: Int) {
            MOUSE_SCROLL_REPEAT_INTERVAL = interval
        }
    }

    val superPagesController: SuperPagesController
        get() = controllerManager.superPagesController!!

    /**
     * 隐藏所有虚拟按键的容器。
     */
    fun hideAllElementsForTest() {
        if (elementsLayout != null) {
            elementsLayout.setVisibility(View.INVISIBLE)
        }
    }

    /**
     * 显示所有虚拟按键的容器。
     */
    fun showAllElementsForTest() {
        if (elementsLayout != null) {
            elementsLayout.setVisibility(View.VISIBLE)
        }
    }

    // 设置拖动编辑开关的方法
    fun setDragEditEnabled(enabled: Boolean) {
        this.dragEditEnabled = enabled
    }

    fun setSelectedElement(element: Element?) {
        if (selectedElement === element) return
        val previous = selectedElement
        selectedElement = element
        if (previous != null) previous.invalidate()
        if (element != null) element.invalidate()
    }

    fun isSelectedElement(element: Element): Boolean {
        return selectedElement === element
    }

    // 获取拖动编辑开关状态的方法
    fun isDragEditEnabled(): Boolean {
        return dragEditEnabled
    }

    private fun setAlignmentSnapEnabled(enabled: Boolean) {
        alignmentSnapEnabled = enabled
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_CROWN_ALIGNMENT_SNAP_ENABLED, enabled)
            .apply()
        if (!enabled) {
            clearAlignmentGuides()
        }
    }

    fun showToast(message: String) {
        if (currentToast != null) {
            currentToast!!.cancel()
        }
        currentToast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
        currentToast!!.show()
    }

    private fun initEditPage() {
        pageEdit.findViewById<View>(R.id.page_edit_exit_edit_mode).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                exitCrownEditMode()
            }
        })
        pageEdit.findViewById<View>(R.id.page_edit_auto_color_all).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                applyCrownAutoColorsToAll()
            }
        })
        (pageEdit.findViewById<View>(R.id.page_edit_edit_grid_width) as NumberSeekbar).setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                editGridWidth = progress
                editGridView.setEditGridWidth(editGridWidth)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }
        })

        bindAddElementButton(R.id.page_edit_add_digital_common_button,
            DigitalCommonButton.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_digital_switch_button,
            DigitalSwitchButton.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_digital_movable_button,
            DigitalMovableButton.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_pad,
            DigitalPad.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_analog_stick,
            AnalogStick.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_digital_stick,
            DigitalStick.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_invisible_analog_stick,
            InvisibleAnalogStick.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_invisible_digital_stick,
            InvisibleDigitalStick.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_simplify_performance,
            SimplifyPerformance.getInitialInfo(context))
        bindAddElementButton(R.id.page_edit_add_digital_combine_button,
            DigitalCombineButton.getInitialInfo(context))
        bindAddElementButton(R.id.page_edit_add_wheel_pad,
            WheelPad.getInitialInfo())
        bindAddElementButton(R.id.page_edit_add_group_button,
            GroupButton.getInitialInfo())
        val dragEditSwitch = pageEdit.findViewById<SwitchCompat?>(R.id.page_edit_drag_edit_switch)
        if (dragEditSwitch != null) {
            AppSwitchStyle.apply(dragEditSwitch)
            // 设置初始状态
            dragEditSwitch.isChecked = true // 默认启用长按移动按键

            // 添加开关监听器
            dragEditSwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                    setDragEditEnabled(isChecked)
                    val message = if (isChecked) context.getString(R.string.crown_drag_hold) else context.getString(R.string.crown_drag_direct)
                    showToast(message)
                }
            })
        }
        val alignmentSnapSwitch = pageEdit.findViewById<SwitchCompat?>(R.id.page_edit_alignment_snap_switch)
        if (alignmentSnapSwitch != null) {
            AppSwitchStyle.apply(alignmentSnapSwitch)
            alignmentSnapSwitch.isChecked = alignmentSnapEnabled
            alignmentSnapSwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                    setAlignmentSnapEnabled(isChecked)
                    val messageResId = if (isChecked)
                        R.string.crown_alignment_snap_enabled
                    else
                        R.string.crown_alignment_snap_disabled
                    showToast(context.getString(messageResId))
                }
            })
        }
    }

    private fun bindAddElementButton(viewId: Int, template: ContentValues) {
        val view = pageEdit.findViewById<View>(viewId) ?: return
        view.setOnClickListener { addElement(ContentValues(template)) }
    }

    private fun exitCrownEditMode() {
        changeMode(Mode.Normal)
        controllerManager.pageSuperMenuController!!.open()
        val pages = controllerManager.superPagesController!!
        if (pages.pageNow === pages.pageNull) {
            handler.post { pages.returnOperation() }
        }
        if (game.getCurrentBackKeyMenuMode() === Game.BackKeyMenuMode.NO_MENU) {
            game.setcurrentBackKeyMenu(Game.BackKeyMenuMode.GAME_MENU)
        } else {
            game.setcurrentBackKeyMenu(Game.BackKeyMenuMode.CROWN_MODE)
        }
    }

    fun getHandler(): Handler {
        return handler
    }

    fun beginElementEvent(element: Element, action: Int) {
        dispatchingElement = element
        if (action == MotionEvent.ACTION_DOWN) directConfigSwitch.begin(element)
    }

    fun endElementEvent(element: Element, action: Int) {
        dispatchingElement = null
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            directConfigSwitch.finish(element, action == MotionEvent.ACTION_CANCEL)
        }
    }

    fun cancelDirectConfigSwitch() {
        directConfigSwitch.reset()
    }

    fun finishStreamTouch(action: Int) {
        if (action == MotionEvent.ACTION_CANCEL) {
            directConfigSwitch.reset()
        } else if (action == MotionEvent.ACTION_UP) {
            val target = directConfigSwitch.takeCompletedTarget()
            if (target != null && !game.isFinishing && !game.isDestroyed
                && mode === Mode.Normal && elementsLayout.isAttachedToWindow) {
                controllerManager.pageConfigController!!.switchDirectlyToConfig(target)
            }
        }
    }

    fun loadAllElement(configId: Long) {
        directConfigSwitch.reset()
        currentConfigId = configId
        removeAllElementsOnScreen()
        elementIds = controllerManager.superConfigDatabaseHelper!!.queryAllElementIds(configId)

        // 用于在第二阶段链接关系的 GroupButton 列表
        val groupButtonsToLink = ArrayList<GroupButton>()

        // --- 阶段一：创建所有 Element 对象 ---
        // 遍历所有 elementId，不区分类型，统一调用 loadElement 创建对象
        for (elementId in elementIds!!) {
            val newElement = loadElement(elementId)

            // 如果创建的是一个 GroupButton，将其添加到待链接列表
            if (newElement is GroupButton) {
                groupButtonsToLink.add(newElement as GroupButton)
            }
        }

        // --- 阶段二：链接 GroupButton 的子元素 ---
        // 此时，`elementList` 列表已经包含了当前配置下的所有 Element 对象
        for (gb in groupButtonsToLink) {
            // 调用我们将在 GroupButton 类中添加的新方法
            gb.linkChildElements(elementList)
        }
    }

    fun addElement(contentValues: ContentValues): Element {
        val configId = controllerManager.pageConfigController!!.currentConfigId
        val elementId = System.currentTimeMillis()
        contentValues.put(Element.COLUMN_LONG_CONFIG_ID, configId)
        contentValues.put(Element.COLUMN_LONG_ELEMENT_ID, elementId)
        controllerManager.superConfigDatabaseHelper!!.insertElement(contentValues)

        return loadElement(elementId)
    }

    fun updateElement(elementId: Long, contentValues: ContentValues) {
        controllerManager.superConfigDatabaseHelper!!.updateElement(currentConfigId, elementId, contentValues)
    }

    fun deleteElement(element: Element) {
        controllerManager.superConfigDatabaseHelper!!.deleteElement(currentConfigId, element.elementId!!)
        if (elementList.contains(element)) {
            element.releaseLatchedInput()
            elementsLayout.removeView(element)
            elementList.remove(element)
        }
    }

    private fun removeAllElementsOnScreen() {
        for (element in elementList) {
            element.releaseLatchedInput()
            elementsLayout.removeView(element)
        }
        elementList.clear()
    }

    private fun loadElement(elementId: Long): Element {
        val attributesMap = controllerManager.superConfigDatabaseHelper!!.queryAllElementAttributes(currentConfigId, elementId)
        val type = (attributesMap.get(Element.COLUMN_INT_ELEMENT_TYPE) as Long).toInt()
        val element: Element = when (type) {
            Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON -> DigitalCommonButton(attributesMap,
                this,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON -> DigitalSwitchButton(attributesMap,
                this,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON -> DigitalMovableButton(attributesMap,
                this,
                controllerManager.touchController!!,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_GROUP_BUTTON -> GroupButton(attributesMap,
                this,
                pageDeviceController,
                controllerManager.superPagesController!!,
                context)
            Element.ELEMENT_TYPE_DIGITAL_PAD -> DigitalPad(attributesMap,
                this,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_ANALOG_STICK -> AnalogStick(attributesMap,
                this,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_DIGITAL_STICK -> DigitalStick(attributesMap,
                this,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_INVISIBLE_ANALOG_STICK -> InvisibleAnalogStick(attributesMap,
                this,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK -> InvisibleDigitalStick(attributesMap,
                this,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_SIMPLIFY_PERFORMANCE -> SimplifyPerformance(attributesMap,
                this,
                context)
            Element.ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON -> DigitalCombineButton(attributesMap,
                this,
                pageDeviceController,
                context)
            Element.ELEMENT_TYPE_WHEEL_PAD -> WheelPad(attributesMap,
                this,
                pageDeviceController,
                context)
            else -> DigitalCommonButton(attributesMap,
                this,
                pageDeviceController,
                context)
        }
        val elementWidth = (attributesMap.get(Element.COLUMN_INT_ELEMENT_WIDTH) as Long).toInt()
        val elementHeight = (attributesMap.get(Element.COLUMN_INT_ELEMENT_HEIGHT) as Long).toInt()
        val elementCentralX = (attributesMap.get(Element.COLUMN_INT_ELEMENT_CENTRAL_X) as Long).toInt()
        val elementCentralY = (attributesMap.get(Element.COLUMN_INT_ELEMENT_CENTRAL_Y) as Long).toInt()
        val layoutParams = FrameLayout.LayoutParams(elementWidth, elementHeight)
        layoutParams.leftMargin = elementCentralX - elementWidth / 2
        layoutParams.topMargin = elementCentralY - elementHeight / 2

        //对element的层级进行排序
        for (i in 0..elementList.size) {
            if (i == elementList.size) {
                elementList.add(i, element)
                elementsLayout.addView(element, i + bottomViewAmount, layoutParams)
                break
            }
            val elementExist = elementList[i]
            if (elementExist.elementId!! + (elementExist.layer.toLong() shl 48) > element.elementId!! + (element.layer.toLong() shl 48)) {
                elementList.add(i, element)
                elementsLayout.addView(element, i + bottomViewAmount, layoutParams)
                break
            }
        }

        //限制element的位置范围
        element.setElementHeight(element.getElementHeight())
        element.setElementWidth(element.getElementWidth())

        return element
    }

    fun adjustLayer(element: Element) {
        val elementWidth = element.getElementWidth()
        val elementHeight = element.getElementHeight()
        val elementCentralX = element.getElementCentralX()
        val elementCentralY = element.getElementCentralY()

        elementsLayout.removeView(element)
        elementList.remove(element)

        val layoutParams = FrameLayout.LayoutParams(elementWidth, elementHeight)
        layoutParams.leftMargin = elementCentralX - elementWidth / 2
        layoutParams.topMargin = elementCentralY - elementHeight / 2
        //对element的层级进行排序
        for (i in 0..elementList.size) {
            if (i == elementList.size) {
                elementList.add(i, element)
                elementsLayout.addView(element, i + bottomViewAmount, layoutParams)
                break
            }
            val elementExist = elementList[i]
            if (elementExist.elementId!! + (elementExist.layer.toLong() shl 48) > element.elementId!! + (element.layer.toLong() shl 48)) {
                elementList.add(i, element)
                elementsLayout.addView(element, i + bottomViewAmount, layoutParams)
                break
            }
        }
    }

    fun editGridHandle(position: Int): Int {
        return position - position % editGridWidth
    }

    fun snapElementPosition(movingElement: Element?, candidateCentralX: Int, candidateCentralY: Int): SnapResult {
        val snappedCentralX = editGridHandle(candidateCentralX)
        val snappedCentralY = editGridHandle(candidateCentralY)
        if (mode !== Mode.Edit || !alignmentSnapEnabled || movingElement == null || elementsLayout == null) {
            clearAlignmentGuides()
            return SnapResult(snappedCentralX, snappedCentralY)
        }

        val threshold = dp(ALIGNMENT_SNAP_THRESHOLD_DP)
        val xSnap = AxisSnap(snappedCentralX)
        val ySnap = AxisSnap(snappedCentralY)

        val halfWidth = movingElement.getElementWidth() / 2
        val halfHeight = movingElement.getElementHeight() / 2
        val movingXOffsets = intArrayOf(-halfWidth, 0, halfWidth)
        val movingYOffsets = intArrayOf(-halfHeight, 0, halfHeight)

        val layoutWidth = elementsLayout.width
        val layoutHeight = elementsLayout.height
        if (layoutWidth > 0) {
            considerAxisGuides(xSnap, snappedCentralX, movingXOffsets, intArrayOf(0, layoutWidth / 2, layoutWidth), threshold)
        }
        if (layoutHeight > 0) {
            considerAxisGuides(ySnap, snappedCentralY, movingYOffsets, intArrayOf(0, layoutHeight / 2, layoutHeight), threshold)
        }

        for (element in elementList) {
            if (element === movingElement || element.visibility != View.VISIBLE) {
                continue
            }

            val elementHalfWidth = element.getElementWidth() / 2
            val elementHalfHeight = element.getElementHeight() / 2
            val elementCentralX = element.getElementCentralX()
            val elementCentralY = element.getElementCentralY()
            considerAxisGuides(
                xSnap,
                snappedCentralX,
                movingXOffsets,
                intArrayOf(elementCentralX - elementHalfWidth, elementCentralX, elementCentralX + elementHalfWidth),
                threshold
            )
            considerAxisGuides(
                ySnap,
                snappedCentralY,
                movingYOffsets,
                intArrayOf(elementCentralY - elementHalfHeight, elementCentralY, elementCentralY + elementHalfHeight),
                threshold
            )
        }

        val finalCentralX = editGridHandle(xSnap.getCenter())
        val finalCentralY = editGridHandle(ySnap.getCenter())
        updateAlignmentGuides(xSnap.getGuide(finalCentralX), ySnap.getGuide(finalCentralY))
        return SnapResult(finalCentralX, finalCentralY)
    }

    fun clearAlignmentGuides() {
        editGridView.clearAlignmentGuides()
    }

    private fun considerAxisGuides(snap: AxisSnap,
                                   movingCenter: Int,
                                   movingOffsets: IntArray,
                                   targetGuides: IntArray,
                                   threshold: Int) {
        for (movingOffset in movingOffsets) {
            for (targetGuide in targetGuides) {
                snap.consider(movingCenter, movingOffset, targetGuide, threshold)
            }
        }
    }

    private fun updateAlignmentGuides(verticalGuide: Float, horizontalGuide: Float) {
        editGridView.setAlignmentGuides(verticalGuide, horizontalGuide)
    }

    fun toggleInfoPage(elementSettingPage: SuperPageLayout) {
        if (elementSettingPage === controllerManager.superPagesController!!.pageNow) {
            controllerManager.superPagesController!!.openNewPage(
                controllerManager.superPagesController!!.pageNull)
        } else {
            addCrownInspectorPreview(elementSettingPage, selectedElement)
            applyCrownDetailStyle(elementSettingPage)
            compactCrownInspector(elementSettingPage)
            promoteCrownDetailActions(elementSettingPage)
            positionCrownDetailPageAwayFromOwner(elementSettingPage)
            controllerManager.superPagesController!!.openNewPage(elementSettingPage)
            elementSettingPage.setPageReturnListener(object : SuperPageLayout.ReturnListener {
                override fun returnCallBack() {
                    controllerManager.superPagesController!!.openNewPage(controllerManager.superPagesController!!.pageNull)
                }
            })
        }
    }

    private fun addCrownInspectorPreview(page: SuperPageLayout, element: Element?) {
        if (element == null || page.findViewWithTag<View>("crown_inspector_preview") != null) {
            return
        }

        val scrollView = findCrownDetailScrollView(page) ?: return
        if (scrollView.getChildAt(0) !is ViewGroup) {
            return
        }
        val content = scrollView.getChildAt(0) as ViewGroup
        // Design L4: a full-width 84dp dark canvas with the live element centered.
        val card = FrameLayout(context)
        card.tag = "crown_inspector_preview"
        card.setBackgroundResource(R.drawable.crown_inspector_preview_bg)
        card.setPadding(dp(6), dp(6), dp(6), dp(6))

        val preview = CrownInspectorPreviewView(context)
        preview.bind(element)
        card.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val cardParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(84))
        cardParams.bottomMargin = dp(10)
        content.addView(card, Math.min(1, content.childCount), cardParams)
    }

    /**
     * The element pages remain the source of truth for their bindings, but their
     * original XML is intentionally verbose. Reflow consecutive numeric controls
     * into compact two-column rows at runtime so every element type gets the same
     * Inspector density without duplicating 13 page layouts.
     */
    private fun compactCrownInspector(page: SuperPageLayout) {
        val scrollView = findCrownDetailScrollView(page)
        if (scrollView == null || CROWN_INSPECTOR_COMPACT_TAG == scrollView.tag
            || scrollView.getChildAt(0) !is ViewGroup) {
            return
        }

        scrollView.setClipToPadding(false)
        scrollView.setFillViewport(false)
        scrollView.setPadding(
            scrollView.paddingLeft,
            Math.max(scrollView.paddingTop, dp(8)),
            scrollView.paddingRight,
            Math.max(scrollView.paddingBottom, dp(82))
        )
        compactNumberSeekbars(scrollView.getChildAt(0) as ViewGroup)
        normalizeCrownInspectorTypography(scrollView.getChildAt(0) as ViewGroup)
        scrollView.tag = CROWN_INSPECTOR_COMPACT_TAG
    }

    private fun compactNumberSeekbars(parent: ViewGroup) {
        var i = 0
        while (i < parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is NumberSeekbar) {
                val start = i
                val seekbars = ArrayList<NumberSeekbar>()
                while (i < parent.childCount && parent.getChildAt(i) is NumberSeekbar) {
                    seekbars.add(parent.getChildAt(i) as NumberSeekbar)
                    i++
                }
                for (seekbar in seekbars) {
                    seekbar.setCompactMode(true)
                }

                if (seekbars.size >= 2) {
                    parent.removeViews(start, seekbars.size)
                    var insertAt = start
                    for (offset in 0 until seekbars.size step 2) {
                        val row = LinearLayout(context)
                        row.orientation = LinearLayout.HORIZONTAL
                        row.gravity = Gravity.CENTER_VERTICAL
                        val rowParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
                        if (insertAt > 0) {
                            rowParams.topMargin = dp(2)
                        }

                        val first = seekbars[offset]
                        val firstParams = LinearLayout.LayoutParams(
                            0, dp(40), 1f)
                        firstParams.marginEnd = dp(4)
                        row.addView(first, firstParams)

                        if (offset + 1 < seekbars.size) {
                            val second = seekbars[offset + 1]
                            val secondParams = LinearLayout.LayoutParams(
                                0, dp(40), 1f)
                            secondParams.marginStart = dp(4)
                            row.addView(second, secondParams)
                        }
                        parent.addView(row, (if (insertAt++ == start) start else insertAt - 1), rowParams)
                    }
                    i = start + (seekbars.size + 1) / 2
                }
                continue
            }

            if (child is ViewGroup && "crown_inspector_preview" != child.tag) {
                compactNumberSeekbars(child as ViewGroup)
            }
            i++
        }
    }

    private fun normalizeCrownInspectorTypography(parent: ViewGroup) {
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is TextView && child !is NumberSeekbar) {
                val text = child as TextView
                val sizeSp = text.textSize / Math.max(0.1f, scaledDensity)
                if (sizeSp >= 17f) {
                    text.textSize = 16f
                } else if (text.typeface != null && text.typeface!!.isBold && sizeSp >= 13f) {
                    text.textSize = 12.5f
                } else if (sizeSp >= 12f) {
                    text.textSize = 11.5f
                }
                text.setIncludeFontPadding(false)
            }
            if (child is ViewGroup && "crown_inspector_preview" != child.tag) {
                normalizeCrownInspectorTypography(child as ViewGroup)
            }
        }
    }

    private fun positionCrownDetailPageAwayFromOwner(page: SuperPageLayout) {
        val owner = getCrownAutoColorOwner(page) ?: return
        if (owner.width <= 0) {
            return
        }

        val location = IntArray(2)
        owner.getLocationOnScreen(location)
        val ownerCenterX = location[0] + owner.width / 2f
        val screenWidth = context.resources.displayMetrics.widthPixels
        val targetPosition = if (ownerCenterX > screenWidth / 2f)
            SuperPagesController.BoxPosition.Left
        else
            SuperPagesController.BoxPosition.Right
        controllerManager.superPagesController!!.setPosition(targetPosition)
    }

    private fun promoteCrownDetailActions(page: SuperPageLayout) {
        if (page.findViewWithTag<View>(CROWN_DETAIL_ACTION_BAR_TAG) != null) {
            return
        }

        val actionButtons = ArrayList<Button>()
        collectCrownActionButtons(page, actionButtons)
        val autoColorOwner = getCrownAutoColorOwner(page)
        val supportsAutoColor = autoColorOwner != null && autoColorOwner.supportsCrownAutoColors()
        val copyButton = findCrownActionButton(actionButtons, "_copy")
        val deleteButton = findCrownActionButton(actionButtons, "_delete")
        if (copyButton == null && deleteButton == null && !supportsAutoColor) {
            return
        }

        val actionBar = LinearLayout(context)
        actionBar.tag = CROWN_DETAIL_ACTION_BAR_TAG
        actionBar.gravity = Gravity.CENTER_VERTICAL
        actionBar.orientation = LinearLayout.HORIZONTAL
        actionBar.setPadding(dp(12), dp(10), dp(12), dp(14))
        actionBar.setBackgroundResource(R.drawable.crown_panel_footer_bg)

        val detailScroll = findCrownDetailScrollView(page)
        if (detailScroll != null) {
            detailScroll.setClipToPadding(false)
            detailScroll.setPadding(
                detailScroll.paddingLeft,
                detailScroll.paddingTop,
                detailScroll.paddingRight,
                detailScroll.paddingBottom + dp(78)
            )
        }

        val actionBarParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        actionBar.elevation = dp(6).toFloat()

        if (supportsAutoColor) {
            val autoColorButton = createCrownIconActionButton(
                R.drawable.phc_action_auto_color,
                context.getString(R.string.crown_auto_color_action)
            ) { applyCrownAutoColors(autoColorOwner!!, page) }
            actionBar.addView(autoColorButton, createCrownIconActionParams())
        }

        // Design L4: 复制控件 stays as a labeled ghost action, 完成 closes the page,
        // and delete moves to a danger icon next to the page title.
        if (copyButton != null) {
            val copyAction = createCrownTextActionButton(
                copyButton.text, false
            ) { copyButton.performClick() }
            actionBar.addView(copyAction, LinearLayout.LayoutParams(
                0, dp(42), 1f))
            actionBar.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            copyButton.setVisibility(View.GONE)
            hideActionRowIfNeeded(copyButton)
        }

        val doneAction = createCrownTextActionButton(
            context.getString(R.string.crown_edit_done), true
        ) {
            controllerManager.superPagesController!!
                .openNewPage(controllerManager.superPagesController!!.pageNull)
        }
        actionBar.addView(doneAction, LinearLayout.LayoutParams(0, dp(42), 1.2f))

        if (deleteButton != null) {
            addCrownHeaderDeleteButton(page, deleteButton)
            deleteButton.setVisibility(View.GONE)
            hideActionRowIfNeeded(deleteButton)
        }

        page.addView(actionBar, actionBarParams)
    }

    private fun findCrownActionButton(actionButtons: List<Button>, idSuffix: String): Button? {
        for (button in actionButtons) {
            val idName: String
            try {
                idName = context.resources.getResourceEntryName(button.id)
            } catch (e: Resources.NotFoundException) {
                continue
            }
            if (idName.endsWith(idSuffix)) {
                return button
            }
        }
        return null
    }

    private fun createCrownTextActionButton(label: CharSequence, primary: Boolean,
                                            listener: View.OnClickListener): LinearLayout {
        val actionButton = LinearLayout(context)
        actionButton.gravity = Gravity.CENTER
        actionButton.orientation = LinearLayout.HORIZONTAL
        actionButton.setBackgroundResource(if (primary)
            R.drawable.crown_primary_button_bg
        else
            R.drawable.crown_ghost_button_bg)
        actionButton.isClickable = true
        actionButton.isFocusable = true
        val text = TextView(context)
        text.text = label
        text.textSize = 13f
        if (primary) {
            text.setTypeface(text.typeface, android.graphics.Typeface.BOLD)
        }
        text.setTextColor(context.resources.getColor(if (primary)
            R.color.crown_on_accent
        else
            R.color.crown_text_secondary))
        actionButton.addView(text)
        actionButton.setOnClickListener(listener)
        return actionButton
    }

    private fun addCrownHeaderDeleteButton(page: SuperPageLayout, originalDelete: Button) {
        // Inspector pages carry their own tagged header row; fall back to the
        // shared crown_page_title structure for pages that use it.
        var header: ViewGroup? = null
        val tagged = page.findViewWithTag<View>("crown_inspector_header")
        if (tagged is ViewGroup) {
            header = tagged as ViewGroup
        } else {
            val titleText = page.findViewById<View>(R.id.crown_page_title_text)
            if (titleText != null && titleText.parent is ViewGroup) {
                header = titleText.parent as ViewGroup
            }
        }
        if (header == null || page.findViewWithTag<View>("crown_header_delete") != null) {
            return
        }
        val deleteButton = createCrownIconActionButton(
            R.drawable.phc_action_trash,
            if (originalDelete.contentDescription != null)
                originalDelete.contentDescription
            else
                originalDelete.text
        ) { originalDelete.performClick() }
        deleteButton.tag = "crown_header_delete"
        deleteButton.setColorFilter(
            context.resources.getColor(R.color.app_action_sheet_danger))
        val params = LinearLayout.LayoutParams(dp(32), dp(32))
        params.marginStart = dp(8)
        header.addView(deleteButton, params)
    }

    private fun findCrownDetailScrollView(view: View): ScrollView? {
        if (view is ScrollView) {
            return view as ScrollView
        }
        if (view !is ViewGroup) {
            return null
        }
        val group = view as ViewGroup
        for (i in 0 until group.childCount) {
            val result = findCrownDetailScrollView(group.getChildAt(i))
            if (result != null) return result
        }
        return null
    }

    private fun createCrownIconActionButton(iconResId: Int, contentDescription: CharSequence, listener: View.OnClickListener): ImageButton {
        val actionButton = ImageButton(context)
        actionButton.setBackgroundResource(R.drawable.crown_action_icon_button_bg)
        actionButton.setColorFilter(context.resources.getColor(R.color.crown_text_primary))
        actionButton.contentDescription = contentDescription
        actionButton.setImageResource(iconResId)
        actionButton.setPadding(dp(12), dp(12), dp(12), dp(12))
        actionButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        actionButton.setOnClickListener(listener)
        return actionButton
    }

    private fun createCrownIconActionParams(): LinearLayout.LayoutParams {
        val buttonParams = LinearLayout.LayoutParams(
            dp(48),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        buttonParams.marginStart = dp(4)
        return buttonParams
    }

    private fun getCrownAutoColorOwner(page: SuperPageLayout): Element? {
        val owner = page.getTag(R.id.crown_auto_color_owner)
        return owner as Element?
    }

    private fun applyCrownAutoColors(element: Element?, page: SuperPageLayout) {
        if (context !is Game) {
            Toast.makeText(context, R.string.crown_auto_color_no_frame, Toast.LENGTH_SHORT).show()
            return
        }

        CrownScreenColorSampler.sample(context as Game, object : CrownScreenColorSampler.Callback {
            override fun onPalette(palette: CrownAutoColorPalette) {
                if (element!!.applyCrownAutoColors(palette, page)) {
                    Toast.makeText(context, R.string.crown_auto_color_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.crown_auto_color_failed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(message: String) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun applyCrownAutoColorsToAll() {
        if (context !is Game) {
            Toast.makeText(context, R.string.crown_auto_color_no_frame, Toast.LENGTH_SHORT).show()
            return
        }

        CrownScreenColorSampler.sample(context as Game, object : CrownScreenColorSampler.Callback {
            override fun onPalette(palette: CrownAutoColorPalette) {
                var appliedCount = 0
                for (element in elementList) {
                    if (!element.supportsCrownAutoColors()) {
                        continue
                    }
                    if (element.applyCrownAutoColors(palette, null)) {
                        appliedCount++
                    }
                }

                if (appliedCount > 0) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.crown_auto_color_all_done, appliedCount),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(context, R.string.crown_auto_color_no_supported_elements, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(message: String) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun collectCrownActionButtons(view: View, actionButtons: MutableList<Button>) {
        if (view is Button && isCrownDetailActionButton(view)) {
            actionButtons.add(view as Button)
            return
        }

        if (view is ViewGroup) {
            val viewGroup = view as ViewGroup
            for (i in 0 until viewGroup.childCount) {
                collectCrownActionButtons(viewGroup.getChildAt(i), actionButtons)
            }
        }
    }

    private fun isCrownDetailActionButton(view: View): Boolean {
        if (view.id == View.NO_ID) {
            return false
        }

        val idName: String
        try {
            idName = context.resources.getResourceEntryName(view.id)
        } catch (e: Resources.NotFoundException) {
            return false
        }
        return idName.endsWith("_copy")
            || idName.endsWith("_delete")
            || idName.endsWith("_reset")
            || idName.endsWith("_ensure")
    }

    private fun hideActionRowIfNeeded(originalButton: Button) {
        val parent = originalButton.parent
        if (parent !is ViewGroup) {
            return
        }

        val row = parent as ViewGroup
        if (containsVisibleActionButton(row)) {
            return
        }
        if (containsVisibleNonActionChild(row)) {
            return
        }

        row.setVisibility(View.GONE)
        val sectionParent = row.parent
        if (sectionParent is ViewGroup) {
            val section = sectionParent as ViewGroup
            if (hasOnlyTextAndHiddenActionRows(section)) {
                section.setVisibility(View.GONE)
            }
        }
    }

    private fun containsVisibleActionButton(viewGroup: ViewGroup): Boolean {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is Button && child.visibility == View.VISIBLE && isCrownDetailActionButton(child)) {
                return true
            }
        }
        return false
    }

    private fun containsVisibleNonActionChild(viewGroup: ViewGroup): Boolean {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child.visibility != View.VISIBLE) {
                continue
            }
            if (child !is Button || !isCrownDetailActionButton(child)) {
                return true
            }
        }
        return false
    }

    private fun hasOnlyTextAndHiddenActionRows(viewGroup: ViewGroup): Boolean {
        var hasHiddenActionRow = false
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child.visibility == View.GONE && child is ViewGroup) {
                hasHiddenActionRow = true
                continue
            }
            if (child is TextView) {
                continue
            }
            return false
        }
        return hasHiddenActionRow
    }

    private fun dp(value: Int): Int {
        return Math.round(value * context.resources.displayMetrics.density)
    }

    private fun applyCrownDetailStyle(view: View) {
        if (view is SwitchCompat) {
            val switchView = view as SwitchCompat
            AppSwitchStyle.apply(switchView)
        } else if (view is RadioButton) {
            val radioButton = view as RadioButton
            radioButton.setTextColor(context.resources.getColor(R.color.crown_text_primary))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                radioButton.buttonTintList = getColorStateList(R.color.crown_radio_button)
            }
        } else if (view is Button) {
            val button = view as Button
            button.setBackgroundResource(R.drawable.crown_config_action_button_bg)
            button.setTextColor(context.resources.getColor(R.color.crown_text_primary))
        } else if (view is EditText) {
            val editText = view as EditText
            editText.setTextColor(context.resources.getColor(R.color.crown_text_primary))
            editText.setHintTextColor(context.resources.getColor(R.color.crown_text_secondary))
        }

        if (view is ViewGroup) {
            val viewGroup = view as ViewGroup
            for (i in 0 until viewGroup.childCount) {
                applyCrownDetailStyle(viewGroup.getChildAt(i))
            }
        }
    }

    private fun getColorStateList(colorResId: Int): ColorStateList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.resources.getColorStateList(colorResId, context.theme)
        }
        return context.resources.getColorStateList(colorResId)
    }

    val currentEditingPage: SuperPageLayout
        get() = controllerManager.superPagesController!!.pageNow

    fun open() {
        val superPagesController = controllerManager.superPagesController!!
        val pageNull = superPagesController.pageNull
        superPagesController.openNewPage(pageNull)
        pageNull.setPageReturnListener(object : SuperPageLayout.ReturnListener {
            override fun returnCallBack() {
                superPagesController.openNewPage(pageEdit)
            }
        })
        pageEdit.setPageReturnListener(object : SuperPageLayout.ReturnListener {
            override fun returnCallBack() {
                superPagesController.openNewPage(pageNull)
            }
        })
        pageEdit.post {
            val scrollView = pageEdit.findViewById<View>(R.id.page_edit_scroll)
            if (scrollView != null) scrollView.scrollTo(0, 0)
        }
    }

    fun changeMode(mode: Mode) {
        if (this.mode === mode) {
            return
        }

        this.mode = mode
        when (mode) {
            Mode.Normal -> {
                selectedElement = null
                controllerManager.touchController!!.enableTouch(
                    controllerManager.pageConfigController!!.isTouchEnabled())
                this.mode = Mode.Normal
                clearAlignmentGuides()
                elementsLayout.removeView(editGridView)
                for (element in elementList) {
                    element.invalidate()
                }
            }
            Mode.Edit -> {
                selectedElement = null
                controllerManager.touchController!!.enableTouch(false)
                this.mode = Mode.Edit
                val layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                layoutParams.leftMargin = 0
                layoutParams.topMargin = 0
                elementsLayout.addView(editGridView, bottomViewAmount, layoutParams)
                for (element in elementList) {
                    element.setEditColor(Element.EDIT_COLOR_EDIT)
                    element.invalidate()
                }
            }
            Mode.Select -> {
                clearAlignmentGuides()
                elementsLayout.removeView(editGridView)
                this.mode = Mode.Select
                for (element in elementList) {
                    element.setEditColor(Element.EDIT_COLOR_SELECT)
                    element.invalidate()
                }
            }
        }
        // 无论切换到什么模式，都通知所有元素，并让它们重绘
        for (element in elementList) {
            element.onModeChanged(mode) // <-- 调用新方法

            // 我们仍然需要根据模式设置颜色
            if (mode === Mode.Edit) {
                element.setEditColor(Element.EDIT_COLOR_EDIT)
            } else if (mode === Mode.Select) {
                element.setEditColor(Element.EDIT_COLOR_SELECT)
            }

            element.invalidate()
        }
    }

    fun getMode(): Mode {
        return mode
    }

    //其他辅助方法----------------------------------

    /**
     * 根据提供的元素ID查找并返回对应的 Element 对象。
     *
     * @param elementId 要查找的元素的ID。
     * @return 如果找到，则返回 Element 对象；否则返回 null。
     */
    fun findElementById(elementId: Long): Element? {
        // 直接遍历类成员 'elementList' 列表
        for (element in elementList) {
            if (element.elementId == elementId) {
                return element
            }
        }
        return null // 如果没有找到
    }

    // 添加开始滚轮按住的方法
    fun startMouseScrollHold(scrollDirection: Int) {
        // 立即发送一次滚轮事件（按下事件）
        game.mouseVScroll(scrollDirection.toByte())

        // 创建持续滚动的Runnable
        val direction = scrollDirection
        val scrollRunnable = object : Runnable {
            override fun run() {
                // 发送重复的滚轮事件（按住事件）
                game.mouseVScroll(direction.toByte())
                // 继续安排下一次滚动
                handler.postDelayed(this, MOUSE_SCROLL_REPEAT_INTERVAL.toLong())
            }
        }

        // 取消之前相同方向的滚动任务
        mouseScrollRunnableMap[scrollDirection]?.let { handler.removeCallbacks(it) }

        // 存储新的滚动任务
        mouseScrollRunnableMap[scrollDirection] = scrollRunnable

        // 安排首次重复滚动（延迟执行，区分按下和按住）
        handler.postDelayed(scrollRunnable, MOUSE_SCROLL_INITIAL_DELAY.toLong())
    }

    // 添加停止滚轮按住的方法
    fun stopMouseScrollHold(scrollDirection: Int) {
        mouseScrollRunnableMap[scrollDirection]?.let { handler.removeCallbacks(it) }
        mouseScrollRunnableMap.remove(scrollDirection)
    }

    // 辅助方法，创建一个什么都不做的安全处理器
    private fun createEmptyHandler(): SendEventHandler {
        return object : SendEventHandler {
            override fun sendEvent(down: Boolean) {
            }

            override fun sendEvent(analog1: Int, analog2: Int) {
            }
        }
    }

    fun getSendEventHandler(key: String?): SendEventHandler? {
        if (key != null && key.startsWith(DirectConfigAction.PREFIX)) {
            val target = DirectConfigAction.parse(key)
            if (target == null) return createEmptyHandler()
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    // A toggle button also emits false on a click. The touch transaction,
                    // not the logical button level, confirms this one-shot action.
                    if (dispatchingElement != null && target != currentConfigId
                        && controllerManager.superConfigDatabaseHelper!!.queryAllConfigIds().contains(target)) {
                        directConfigSwitch.request(dispatchingElement!!, target)
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        }
        val key = key!!
        if (key.matches("k\\d+".toRegex())) {
            val keyCode = key.substring(1).toInt()
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    sendKeyEvent(down, keyCode.toShort())
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key.matches("m\\d+".toRegex())) {
            val mouseCode = key.substring(1).toInt()
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    sendMouseEvent(mouseCode, down)
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key.startsWith("gb")) {
            // 前缀是 "gb"，说明这一定是一个组按键的ID
            val idString = key.substring(2) // 从第3个字符开始截取 (跳过 "gb")
            try {
                val elementId = idString.toLong()
                val element = findElementById(elementId)

                if (element is GroupButton) {
                    val groupButton = element as GroupButton
                    return object : SendEventHandler {
                        override fun sendEvent(down: Boolean) {
                            if (down) {
                                // 在UI线程上执行按钮的动作
                                handler.post { groupButton.triggerAction() }
                            }
                        }

                        override fun sendEvent(analog1: Int, analog2: Int) {
                            // GroupButton 不处理模拟量事件
                        }
                    }
                } else {
                    // 找到了ID，但它不是GroupButton，或者在加载期间没找到（返回null）
                    // 这是一个无效的配置，返回一个安全的空处理器
                    LimeLog.warning("EventHandler:" + "Invalid GroupButton configuration for key: " + key)
                    return createEmptyHandler()
                }
            } catch (e: NumberFormatException) {
                // ID部分无法解析为long，无效配置
                LimeLog.warning("EventHandler:" + "Failed to parse GroupButton ID for key: " + key + e)
                return createEmptyHandler()
            }
        } else if (key.matches("g\\d+".toRegex())) {
            val padCode = key.substring(1).toInt()
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        gamepadInputContext.inputMap = (gamepadInputContext.inputMap.toInt() or padCode).toShort()
                    } else {
                        gamepadInputContext.inputMap = (gamepadInputContext.inputMap.toInt() and padCode.inv()).toShort()
                    }
                    sendGamepadEvent()
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_GAMEPAD_LEFT_STICK) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                    gamepadInputContext.leftStickX = analog1.toShort()
                    gamepadInputContext.leftStickY = analog2.toShort()
                    sendGamepadEvent()
                }
            }
        } else if (key == SPECIAL_KEY_GAMEPAD_RIGHT_STICK) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                    gamepadInputContext.rightStickX = analog1.toShort()
                    gamepadInputContext.rightStickY = analog2.toShort()
                    sendGamepadEvent()
                }
            }
        } else if (key == SPECIAL_KEY_GAMEPAD_LEFT_TRIGGER) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        gamepadInputContext.leftTrigger = 0xFF.toByte()
                    } else {
                        gamepadInputContext.leftTrigger = 0.toByte()
                    }
                    sendGamepadEvent()
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_GAMEPAD_RIGHT_TRIGGER) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        gamepadInputContext.rightTrigger = 0xFF.toByte()
                    } else {
                        gamepadInputContext.rightTrigger = 0.toByte()
                    }
                    sendGamepadEvent()
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_MOUSE_SCROLL_UP) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        startMouseScrollHold(1)  // 开始向上滚动按住
                    } else {
                        stopMouseScrollHold(1)   // 停止向上滚动按住
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_MOUSE_SCROLL_DOWN) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        startMouseScrollHold(-1) // 开始向下滚动按住
                    } else {
                        stopMouseScrollHold(-1)  // 停止向下滚动按住
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_NULL) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_MOUSE_MODE_SWITCH) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        // 获取当前设置
                        val touchMode = java.lang.Boolean.parseBoolean(controllerManager.superConfigDatabaseHelper!!
                            .queryConfigAttribute(currentConfigId, PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, false.toString()) as String?)
                        val enhancedTouch = java.lang.Boolean.parseBoolean(controllerManager.superConfigDatabaseHelper!!
                            .queryConfigAttribute(currentConfigId, PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, false.toString()) as String?)

                        // 确定当前模式并切换到下一个模式
                        // 模式1: 经典鼠标模式 (touchMode=false, enhancedTouch=false)
                        // 模式2: 多点触控模式 (touchMode=false, enhancedTouch=true)
                        // 模式3: 触控板模式 (touchMode=true, enhancedTouch=false)

                        val contentValues = ContentValues()

                        if (!touchMode && !enhancedTouch) {
                            // 当前是经典鼠标模式 -> 切换到多点触控模式
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, false.toString())
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, true.toString())
                            controllerManager.touchController!!.setTouchMode(false)
                            controllerManager.touchController!!.setEnhancedTouch(true)
                            showToast(context.getString(R.string.touch_mode_multitouch))
                        } else if (!touchMode && enhancedTouch) {
                            // 当前是多点触控模式 -> 切换到触控板模式
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, true.toString())
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, false.toString())
                            controllerManager.touchController!!.setTouchMode(true)
                            controllerManager.touchController!!.setEnhancedTouch(false)
                            showToast(context.getString(R.string.touch_mode_trackpad))
                        } else {
                            // 当前是触控板模式 -> 切换到经典鼠标模式
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, false.toString())
                            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, false.toString())
                            controllerManager.touchController!!.setTouchMode(false)
                            controllerManager.touchController!!.setEnhancedTouch(false)
                            showToast(context.getString(R.string.touch_mode_classic_mouse))
                        }

                        // 保存到数据库中
                        controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, contentValues)
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_CLASSIC_MOUSE_SWITCH) {
            return switchMouseMode(false, false, context.getString(R.string.touch_mode_classic_mouse))
        } else if (key == SPECIAL_KEY_TRACKPAD_MODE) {
            return switchMouseMode(true, false, context.getString(R.string.touch_mode_trackpad))
        } else if (key == SPECIAL_KEY_MULTI_TOUCH_MODE) {
            return switchMouseMode(false, true, context.getString(R.string.touch_mode_multitouch))
        } else if (key == SPECIAL_KEY_DS5_TOUCHPAD_CYCLE) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        val enabled = game.screenDs5Touchpad.toggle()
                        val toastRes = if (enabled) R.string.toast_ds5_touchpad_on
                        else R.string.toast_ds5_touchpad_off
                        showToast(context.getString(toastRes))
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_PC_KEYBOARD_SWITCH) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        game.toggleVirtualKeyboard()
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_ANDROID_KEYBOARD_SWITCH) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        game.toggleKeyboard()
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_CONFIG_SWITCH) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (!down) {
                        return
                    }

                    try {
                        val configController = controllerManager.pageConfigController!!
                        val currentConfigId = configController.currentConfigId
                        val allConfigIds = controllerManager.superConfigDatabaseHelper!!.queryAllConfigIds()
                        val orderedIds = ArrayList<Long>()
                        if (allConfigIds.contains(currentConfigId)) orderedIds.add(currentConfigId)
                        for (id in allConfigIds) {
                            if (!orderedIds.contains(id)) orderedIds.add(id)
                        }
                        val choices = CrownConfigChoiceFactory.create(
                            controllerManager.superConfigDatabaseHelper!!, orderedIds, currentConfigId)
                        game.showCrownConfigPicker(choices, { choice ->
                            if (!choice.isSelectable || choice.configId == currentConfigId) {
                                return@showCrownConfigPicker
                            }
                            PreferenceManager.getDefaultSharedPreferences(context)
                                .edit()
                                .putLong("current_config_id", choice.configId)
                                .apply()
                            try {
                                configController.initConfig()
                            } catch (ignored: Exception) {
                            }
                            loadAllElement(choice.configId)
                            showToast(context.getString(
                                R.string.toast_switched_to_config, choice.name))
                        }, { })
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.toast_config_list_load_failed, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_PAN_ZOOM_MODE) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        Toast.makeText(game,
                            if (game.getisTouchOverrideEnabled()) R.string.toast_pan_zoom_disabled_short else R.string.toast_pan_zoom_enabled_short,
                            Toast.LENGTH_SHORT).show()
                        game.setisTouchOverrideEnabled(!game.getisTouchOverrideEnabled())
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_OPEN_GAME_MENU) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        game.showGameMenu(null)
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_EDIT_MODE_SWITCH) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        buttonVibrator()
                        game.toggleBackKeyMenuType()
                        changeMode(Mode.Edit)
                        open()
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_MOUSE_ENABLE_SWITCH) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        val enabled = controllerManager.pageConfigController!!.toggleTouchEnabled()
                        if (enabled) {
                            showToast(context.getString(R.string.toast_touch_enabled_short))
                        } else {
                            showToast(context.getString(R.string.toast_touch_disabled_short))
                        }
                    }
                }

                override fun sendDigitalSwitchEvent(down: Boolean, cleanup: Boolean) {
                    if (!cleanup) {
                        sendEvent(true)
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        } else if (key == SPECIAL_KEY_MOUSE_MOVE_ONLY) {
            return object : SendEventHandler {
                override fun sendEvent(down: Boolean) {
                    if (down) {
                        game.toggleMouseMoveOnly()
                        if (game.isMouseMoveOnlyEnabled) {
                            showToast(context.getString(R.string.layout_page_device_text_mmo_true_text))
                        } else {
                            showToast(context.getString(R.string.layout_page_device_text_mmo_false_text))
                        }
                    }
                }

                override fun sendEvent(analog1: Int, analog2: Int) {
                }
            }
        }
        return null
    }

    //鼠标模式切换
    private fun switchMouseMode(touchMode: Boolean, enhancedTouch: Boolean, toastMessage: String): SendEventHandler {
        return object : SendEventHandler {
            override fun sendEvent(down: Boolean) {
                if (down) {
                    val contentValues = ContentValues()
                    contentValues.put(PageConfigController.COLUMN_BOOLEAN_TOUCH_MODE, touchMode.toString())
                    contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, enhancedTouch.toString())
                    controllerManager.touchController!!.setTouchMode(touchMode)
                    controllerManager.touchController!!.setEnhancedTouch(enhancedTouch)
                    showToast(toastMessage)
                    controllerManager.superConfigDatabaseHelper!!
                        .updateConfig(currentConfigId, contentValues)
                }
            }

            override fun sendEvent(analog1: Int, analog2: Int) {
            }
        }
    }

    fun sendKeyEvent(buttonDown: Boolean, keyCode: Short) {
        game.keyboardEvent(buttonDown, keyCode)
        //如果map中有对应按键的runnable，则删除该按键的runnable。
        keyEventRunnableMap[keyCode]?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            game.keyboardEvent(buttonDown, keyCode)
        }
        //把这个按键的runnable放到map中，以便这个按键重新发送的时候，重置runnable。
        keyEventRunnableMap[keyCode] = runnable

        handler.postDelayed(runnable, 50)
        handler.postDelayed(runnable, 75)
    }

    fun sendMouseEvent(mouseId: Int, down: Boolean) {
        game.mouseButtonEvent(mouseId, down)
        mouseEventRunnableMap[mouseId]?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            game.mouseButtonEvent(mouseId, down)
        }
        //把这个按键的runnable放到map中，以便这个按键重新发送的时候，重置runnable。
        mouseEventRunnableMap[mouseId] = runnable

        handler.postDelayed(runnable, 50)
        handler.postDelayed(runnable, 75)
    }

    fun sendMouseScroll(scrollDirection: Int) {
        game.mouseVScroll(scrollDirection.toByte())
        val runnable = Runnable {
            game.mouseVScroll(scrollDirection.toByte())
        }
        handler.postDelayed(runnable, 50)
        handler.postDelayed(runnable, 75)
    }

    fun sendGamepadEvent() {
        game.controllerHandler.reportOscState(
            gamepadInputContext.inputMap.toInt(),
            gamepadInputContext.leftStickX,
            gamepadInputContext.leftStickY,
            gamepadInputContext.rightStickX,
            gamepadInputContext.rightStickY,
            gamepadInputContext.leftTrigger,
            gamepadInputContext.rightTrigger
        )

        val runnable = Runnable {
            game.controllerHandler.reportOscState(
                gamepadInputContext.inputMap.toInt(),
                gamepadInputContext.leftStickX,
                gamepadInputContext.leftStickY,
                gamepadInputContext.rightStickX,
                gamepadInputContext.rightStickY,
                gamepadInputContext.leftTrigger,
                gamepadInputContext.rightTrigger
            )
        }
        handler.postDelayed(runnable, 50)
        handler.postDelayed(runnable, 75)
    }

    fun setButtonVibrator(buttonVibrator: Boolean) {
        this.buttonVibrator = buttonVibrator
    }

    fun setGameVibrator(gameVibrator: Boolean) {
        this.gameVibrator = gameVibrator
        if (!gameVibrator) {
            game.controllerHandler.submitLegacyDeviceRumble(0.toShort(), 0.toShort())
        }
    }

    fun buttonVibrator() {
        if (buttonVibrator) {
            rumbleSingleVibrator(1000.toShort(), 1000.toShort(), 50)
        }
    }

    fun gameVibrator(lowFreqMotor: Short, highFreqMotor: Short) {
        if (gameVibrator) {
            game.controllerHandler.submitLegacyDeviceRumble(lowFreqMotor, highFreqMotor)
        }
    }

    fun rumbleSingleVibrator(lowFreqMotor: Short, highFreqMotor: Short, vibratorTime: Int) {
        game.controllerHandler.playDeviceTouchHaptic(lowFreqMotor, highFreqMotor, vibratorTime)
    }
}
