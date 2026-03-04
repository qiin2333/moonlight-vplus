package com.limelight.binding.input.touch

interface TouchContext {
    fun getActionIndex(): Int
    fun setPointerCount(pointerCount: Int)
    fun touchDownEvent(eventX: Int, eventY: Int, eventTime: Long, isNewFinger: Boolean): Boolean
    fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean
    fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long)
    fun cancelTouch()
    fun isCancelled(): Boolean
}
