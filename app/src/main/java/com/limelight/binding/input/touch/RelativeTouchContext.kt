package com.limelight.binding.input.touch

import android.os.Handler
import android.os.Looper
import android.view.View

import com.limelight.Game
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.CursorView

class RelativeTouchContext(
    private val conn: NvConnection,
    private val actionIndex: Int,
    private var targetView: View,
    private val prefConfig: PreferenceConfiguration
) : TouchContext {
    private var lastTouchX = 0
    private var lastTouchY = 0
    private var originalTouchX = 0
    private var originalTouchY = 0
    private var originalTouchTime: Long = 0
    private var cancelled = false
    private var confirmedMove = false
    private var confirmedDrag = false
    private var confirmedScroll = false
    private var distanceMoved = 0.0
    private var xFactor = 0.6
    private var yFactor = 0.6
    private var sense = 1.0
    private var pointerCount = 0
    private var maxPointerCountInGesture = 0

    private var lastTapUpTime: Long = 0
    /** 记录上一次成功单击的结束位置X */
    private var lastTapUpX = 0
    /** 记录上一次成功单击的结束位置Y */
    private var lastTapUpY = 0
    /** 标志位，表示当前是否处于"双击并按住"触发的拖拽模式 */
    private var isDoubleClickDrag = false
    /** 标志位，表示当前手势可能是双击的第二次点击，处于"待定"状态 */
    private var isPotentialDoubleClick = false
    /** 记录双击第二次点击的按下时间，用于保证第二击的最短按下时长 */
    private var potentialSecondTapDownTime: Long = 0

    private val handler: Handler = Handler(Looper.getMainLooper())

    // 仅鼠标移动
    // 动态读取 Game 中的“仅鼠标移动”状态
    private val isMouseMoveOnlyEnabled: Boolean
        get() {
            var context = targetView.context
            while (context is android.content.ContextWrapper) {
                if (context is Game) {
                    return context.isMouseMoveOnlyEnabled
                }
                context = context.baseContext
            }
            return (context as? Game)?.isMouseMoveOnlyEnabled == true
        }

    // 网络发送代理方法
    private val forwardedButtonsDown = HashSet<Byte>()

    private fun sendMouseButtonDownProxy(button: Byte) {
        if (isMouseMoveOnlyEnabled) return
        forwardedButtonsDown += button
        conn.sendMouseButtonDown(button)
    }

    private fun sendMouseButtonUpProxy(button: Byte) {
        if (!forwardedButtonsDown.remove(button)) return
        conn.sendMouseButtonUp(button)
    }

    // 使用代理方法替换原有的 conn 发送
    private val buttonUpRunnables: Array<Runnable> = arrayOf(
        Runnable { sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_LEFT) },
        Runnable { sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_MIDDLE) },
        Runnable { sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_RIGHT) },
        Runnable { sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_X1) },
        Runnable { sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_X2) }
    )

    //  用于处理"双击并按住"的计时器
    private var doubleTapHoldRunnable: Runnable? = null

    // 本地光标渲染器 - 用于显示虚拟鼠标光标
    private var localCursorRenderer: LocalCursorRenderer? = null
    // 是否启用本地光标渲染
    private var enableLocalCursorRendering = true

    private val dragTimerRunnable = Runnable {
        // Check if someone already set move
        if (confirmedMove) {
            return@Runnable
        }

        // The drag should only be processed for the primary finger
        if (actionIndex != maxPointerCountInGesture - 1) {
            return@Runnable
        }

        // We haven't been cancelled before the timer expired so begin dragging
        confirmedDrag = true
        sendMouseButtonDownProxy(getMouseButtonIndex()) 
    }

    // 定义2次点击的间隔小于多久才为双击按住
    private val DOUBLE_TAP_TIME_THRESHOLD: Int = prefConfig.doubleTapTimeThreshold

    fun setTargetView(view: View) {
        this.targetView = view
    }

    /**
     * 初始化本地光标渲染器
     */
    fun initializeLocalCursorRenderer(cursorOverlay: CursorView, width: Int, height: Int) {
        val renderer = localCursorRenderer
        if (renderer == null) {
            localCursorRenderer = LocalCursorRenderer(cursorOverlay, width, height)
        } else {
            renderer.setViewDimensions(width, height)
        }
    }

    /**
     * 销毁本地光标渲染器
     */
    fun destroyLocalCursorRenderer() {
        localCursorRenderer?.let {
            it.hide()
            it.destroy()
        }
        localCursorRenderer = null
    }

    /**
     * 设置是否启用本地光标渲染
     */
    fun setEnableLocalCursorRendering(enable: Boolean) {
        this.enableLocalCursorRendering = enable
        localCursorRenderer?.let {
            if (enable) it.show() else it.hide()
        }
    }

    override fun getActionIndex(): Int = actionIndex

    private fun isWithinTapBounds(touchX: Int, touchY: Int): Boolean {
        val xDelta = Math.abs(touchX - originalTouchX)
        val yDelta = Math.abs(touchY - originalTouchY)
        return xDelta <= TAP_MOVEMENT_THRESHOLD && yDelta <= TAP_MOVEMENT_THRESHOLD
    }

    private fun isTap(eventTime: Long): Boolean {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false
        }

        // If this input wasn't the last finger down, do not report
        // a tap. This ensures we don't report duplicate taps for each
        // finger on a multi-finger tap gesture
        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false
        }

        val timeDelta = eventTime - originalTouchTime
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD
    }

    private fun getMouseButtonIndex(): Byte {
        return if (actionIndex == 1) MouseButtonPacket.BUTTON_RIGHT else MouseButtonPacket.BUTTON_LEFT
    }

    override fun touchDownEvent(eventX: Int, eventY: Int, eventTime: Long, isNewFinger: Boolean): Boolean {
        // Get the view dimensions to scale inputs on this touch
        xFactor = Game.REFERENCE_HORIZ_RES / targetView.width.toDouble() * sense
        yFactor = Game.REFERENCE_VERT_RES / targetView.height.toDouble() * sense

        originalTouchX = eventX
        lastTouchX = eventX
        originalTouchY = eventY
        lastTouchY = eventY

        if (isNewFinger) {
            //  新手势开始，取消任何可能存在的按住计时器
            cancelDoubleTapHoldTimer()

            maxPointerCountInGesture = pointerCount
            originalTouchTime = eventTime
            cancelled = false
            confirmedDrag = false
            confirmedMove = false
            confirmedScroll = false
            isDoubleClickDrag = false
            distanceMoved = 0.0

            isPotentialDoubleClick = false // 重置双击待定状态

            if (isMouseMoveOnlyEnabled) {
                confirmedMove = true
                return true
            }

            if (prefConfig.enableDoubleClickDrag) {
                val timeSinceLastTap = eventTime - lastTapUpTime
                val xDelta = Math.abs(eventX - lastTapUpX)
                val yDelta = Math.abs(eventY - lastTapUpY)

                if (actionIndex == 0 && timeSinceLastTap <= DOUBLE_TAP_TIME_THRESHOLD &&
                    xDelta <= DOUBLE_TAP_MOVEMENT_THRESHOLD && yDelta <= DOUBLE_TAP_MOVEMENT_THRESHOLD
                ) {
                    //  符合双击条件。第一击已在抬起时发送，先确保它已完整释放（抬起可能仍延迟在100ms内）
                    val firstClickUp = buttonUpRunnables[MouseButtonPacket.BUTTON_LEFT - 1]
                    handler.removeCallbacks(firstClickUp)
                    sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_LEFT)

                    //  立即发送第二击的按下，主机看到的第一、二击间隔即为真实间隔
                    sendMouseButtonDownProxy(MouseButtonPacket.BUTTON_LEFT)
                    isPotentialDoubleClick = true
                    potentialSecondTapDownTime = eventTime
                    cancelDragTimer()

                    //  启动"按住确认拖拽"计时器
                    startDoubleTapHoldTimer()
                    return true
                }
            }

            if (actionIndex == 0) {
                // Start the timer for engaging a drag
                startDragTimer()
            }
        }

        return true
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        if (cancelled) {
            return
        }

        // 决策点1：如果在"待定"状态下抬起，说明用户意图是"双击"
        if (isPotentialDoubleClick) {
            //  用户抬起了，说明是双击，取消"按住确认拖拽"计时器
            cancelDoubleTapHoldTimer()

            isPotentialDoubleClick = false

            //  第二击的按下已在touchDown时发送，此处释放完成第二击；
            //  若按下时长不足100ms则补足，避免轮询检测的应用漏事件
            val buttonIndex = MouseButtonPacket.BUTTON_LEFT
            val heldTime = eventTime - potentialSecondTapDownTime
            val buttonUpRunnable = buttonUpRunnables[buttonIndex - 1]
            handler.removeCallbacks(buttonUpRunnable)
            if (heldTime < 100) {
                handler.postDelayed(buttonUpRunnable, 100 - heldTime)
            } else {
                sendMouseButtonUpProxy(buttonIndex)
            }

            // Invalidate the tap time to prevent a triple-tap from becoming a double-tap drag
            lastTapUpTime = 0
            return
        }

        if (isDoubleClickDrag) {
            sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_LEFT) 
            isDoubleClickDrag = false
            lastTapUpTime = 0
            return
        }

        cancelDragTimer()

        val buttonIndex = getMouseButtonIndex()

        if (confirmedDrag) {
            sendMouseButtonUpProxy(buttonIndex) 
            // 拖动结束后重置点击时间，避免影响后续的双指右键
            lastTapUpTime = 0
        } else if (isTap(eventTime)) {
            if (prefConfig.enableDoubleClickDrag && buttonIndex == MouseButtonPacket.BUTTON_LEFT) {
                //  记录时间和位置，用于下一次touchDown的双击判定；第一击立即发送，不再延迟
                lastTapUpTime = eventTime
                lastTapUpX = eventX
                lastTapUpY = eventY
            } else {
                lastTapUpTime = 0 // 清除非左键或功能关闭时的记录
            }

            sendMouseButtonDownProxy(buttonIndex) 

            // Release the mouse button in 100ms to allow for apps that use polling
            // to detect mouse button presses.
            val buttonUpRunnable = buttonUpRunnables[buttonIndex - 1]
            handler.removeCallbacks(buttonUpRunnable)
            handler.postDelayed(buttonUpRunnable, 100)
        } else {
            // 无效点击，重置
            lastTapUpTime = 0
        }
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (cancelled) {
            return true
        }

        // 决策点2：如果在"待定"状态下移动，说明用户意图是"双击拖拽"
        if (isPotentialDoubleClick) {
            val xDelta = Math.abs(eventX - originalTouchX)
            val yDelta = Math.abs(eventY - originalTouchY)
            if (xDelta > DRAG_START_THRESHOLD || yDelta > DRAG_START_THRESHOLD) {
                //  用户移动了，说明是拖拽，取消"按住确认拖拽"计时器
                cancelDoubleTapHoldTimer()
                //  确认是双击拖拽，按下事件已在touchDown时发送，只需进入拖拽状态
                isPotentialDoubleClick = false
                isDoubleClickDrag = true
                confirmedMove = true // 标记为已移动，避免后续逻辑冲突
            }
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            checkForConfirmedMove(eventX, eventY)
            checkForConfirmedScroll()

            if (actionIndex == 0) {
                var deltaX = eventX - lastTouchX
                var deltaY = eventY - lastTouchY
                deltaX = Math.round(Math.abs(deltaX) * xFactor * if (eventX < lastTouchX) -1 else 1).toInt()
                deltaY = Math.round(Math.abs(deltaY) * yFactor * if (eventY < lastTouchY) -1 else 1).toInt()

                if (pointerCount == 2) {
                    if (confirmedScroll) {
                        conn.sendMouseHighResScroll((deltaY * SCROLL_SPEED_FACTOR).toShort())
                    }
                } else if (confirmedMove || isDoubleClickDrag || confirmedDrag) {
                    val renderer = localCursorRenderer
                    if (renderer != null && this.enableLocalCursorRendering) {
                        // 1. 本地模式：更新本地光标
                        renderer.updateCursorPosition(deltaX.toFloat(), deltaY.toFloat())
                        // 2. 获取绝对坐标并发送给服务器 (保持同步)
                        val absPos = renderer.getCursorAbsolutePosition()
                        conn.sendMousePosition(
                            absPos[0].toInt().toShort(),
                            absPos[1].toInt().toShort(),
                            targetView.width.toShort(),
                            targetView.height.toShort()
                        )
                    } else if (prefConfig.absoluteMouseMode) {
                        // 3. 旧版绝对模式
                        conn.sendMouseMoveAsMousePosition(
                            deltaX.toShort(),
                            deltaY.toShort(),
                            targetView.width.toShort(),
                            targetView.height.toShort()
                        )
                    } else {
                        conn.sendMouseMove(deltaX.toShort(), deltaY.toShort())
                    }
                }

                // If the scaling factor ended up rounding deltas to zero, wait until they are
                // non-zero to update lastTouch that way devices that report small touch events often
                // will work correctly
                if (deltaX != 0) {
                    lastTouchX = eventX
                }
                if (deltaY != 0) {
                    lastTouchY = eventY
                }
            } else {
                lastTouchX = eventX
                lastTouchY = eventY
            }
        }

        return true
    }

    override fun cancelTouch() {
        cancelled = true

        cancelDragTimer()
        //  取消手势时，也要清理这个新计时器
        cancelDoubleTapHoldTimer()

        //  双击待定状态下第二击的按下已发出，取消时确保释放按键
        if (isPotentialDoubleClick) {
            sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_LEFT)
        }

        if (isDoubleClickDrag) {
            sendMouseButtonUpProxy(MouseButtonPacket.BUTTON_LEFT) 
            isDoubleClickDrag = false
        }

        if (confirmedDrag) {
            sendMouseButtonUpProxy(getMouseButtonIndex()) 
        }

        confirmedMove = false
        confirmedDrag = false
        confirmedScroll = false
        lastTapUpTime = 0
        isPotentialDoubleClick = false
    }

    //  启动"按住确认拖拽"计时器的方法
    private fun startDoubleTapHoldTimer() {
        cancelDoubleTapHoldTimer() // 防御性取消
        doubleTapHoldRunnable = Runnable {
            // 计时器触发，说明用户按住不动，我们主动确认为拖拽；按下事件已在touchDown时发送
            if (isPotentialDoubleClick) {
                isPotentialDoubleClick = false
                isDoubleClickDrag = true
                confirmedMove = true
            }
        }
        handler.postDelayed(doubleTapHoldRunnable!!, DOUBLE_TAP_HOLD_TO_DRAG_THRESHOLD.toLong())
    }

    //  取消"按住确认拖拽"计时器的方法
    private fun cancelDoubleTapHoldTimer() {
        doubleTapHoldRunnable?.let { handler.removeCallbacks(it) }
        doubleTapHoldRunnable = null
    }

    private fun startDragTimer() {
        cancelDragTimer()
        handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD.toLong())
    }

    private fun cancelDragTimer() {
        handler.removeCallbacks(dragTimerRunnable)
    }

    private fun checkForConfirmedMove(eventX: Int, eventY: Int) {
        if (confirmedMove || confirmedDrag || isPotentialDoubleClick) return
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true
            cancelDragTimer()
            return
        }
        distanceMoved += Math.sqrt(Math.pow((eventX - lastTouchX).toDouble(), 2.0) + Math.pow((eventY - lastTouchY).toDouble(), 2.0))
        if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
            confirmedMove = true
            cancelDragTimer()
        }
    }

    private fun checkForConfirmedScroll() {
        confirmedScroll = actionIndex == 0 && pointerCount == 2 && confirmedMove
    }

    override fun isCancelled(): Boolean = cancelled

    override fun setPointerCount(pointerCount: Int) {
        this.pointerCount = pointerCount

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount
        }
    }

    fun adjustMsense(sense: Double) {
        this.sense = sense
    }

    companion object {
        private const val TAP_MOVEMENT_THRESHOLD = 40
        private const val TAP_DISTANCE_THRESHOLD = 50
        private const val TAP_TIME_THRESHOLD = 250
        private const val DRAG_TIME_THRESHOLD = 650
        private const val DRAG_START_THRESHOLD = 10
        //  定义双击后按住多久确认为拖拽
        private const val DOUBLE_TAP_HOLD_TO_DRAG_THRESHOLD = 200
        /** 定义双击时，两次点击位置的最大允许偏差 */
        private const val DOUBLE_TAP_MOVEMENT_THRESHOLD = 40

        private const val SCROLL_SPEED_FACTOR = 5
    }
}
