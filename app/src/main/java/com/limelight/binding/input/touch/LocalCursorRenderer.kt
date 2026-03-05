package com.limelight.binding.input.touch

import android.os.Handler
import android.os.Looper
import com.limelight.ui.CursorView

class LocalCursorRenderer(
    private var cursorView: CursorView?,
    viewWidth: Int,
    viewHeight: Int
) {
    private var viewWidth: Int = maxOf(1, viewWidth)
    private var viewHeight: Int = maxOf(1, viewHeight)

    // 本地光标位置
    private var cursorX: Float
    private var cursorY: Float

    private val uiHandler = Handler(Looper.getMainLooper())

    init {
        // 初始化位置在中心
        cursorX = this.viewWidth / 2.0f
        cursorY = this.viewHeight / 2.0f

        // 立即同步初始位置给 View，否则 View 会绘制在屏幕外
        uiHandler.post {
            cursorView?.updateCursorPosition(cursorX, cursorY)
        }
    }

    fun updateCursorPosition(deltaX: Float, deltaY: Float) {
        // 更新逻辑坐标
        cursorX = maxOf(0f, minOf(cursorX + deltaX, (viewWidth - 1).toFloat()))
        cursorY = maxOf(0f, minOf(cursorY + deltaY, (viewHeight - 1).toFloat()))

        // 在 UI 线程更新 View
        uiHandler.post {
            cursorView?.updateCursorPosition(cursorX, cursorY)
        }
    }

    fun setViewDimensions(width: Int, height: Int) {
        viewWidth = maxOf(1, width)
        viewHeight = maxOf(1, height)
        // 确保坐标不越界
        cursorX = minOf(cursorX, (viewWidth - 1).toFloat())
        cursorY = minOf(cursorY, (viewHeight - 1).toFloat())
    }

    fun show() {
        uiHandler.post {
            cursorView?.let {
                it.show()
                // 显示时强制更新一次位置，确保立刻可见
                it.updateCursorPosition(cursorX, cursorY)
            }
        }
    }

    fun hide() {
        uiHandler.post {
            cursorView?.hide()
        }
    }

    fun destroy() {
        hide()
        cursorView = null
    }

    // Getter methods required by context
    fun getCursorAbsolutePosition(): FloatArray {
        return floatArrayOf(cursorX, cursorY)
    }
}
