package com.limelight

import android.view.View
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.limelight.nvstream.jni.MoonBridge

/**
 * 管理网络质量通知覆盖层的显示、隐藏和状态。
 */
class NotificationOverlayManager(
    private val cardView: CardView?,
    private val textView: TextView?,
    private val bitrateProvider: () -> Int,
) {
    private var requestedVisibility = View.GONE
    private var hiding = false

    /** 根据连接状态更新通知内容和请求可见性 */
    fun update(connectionStatus: Int, message: String) {
        if (cardView == null || textView == null) return

        textView.text = message

        val backgroundColor = when {
            connectionStatus == MoonBridge.CONN_STATUS_POOR && bitrateProvider() > 5000 ->
                0x80FF9800.toInt() // Orange - slow
            connectionStatus == MoonBridge.CONN_STATUS_POOR ->
                0x80F44336.toInt() // Red - poor
            else ->
                0x80FF5722.toInt() // Orange-red - default
        }
        cardView.setCardBackgroundColor(backgroundColor)
    }

    /** 设置请求的可见性状态（不立即刷新视图） */
    fun setRequestedVisible(visible: Boolean) {
        requestedVisibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 重置状态并隐藏 */
    fun reset() {
        requestedVisibility = View.GONE
        cardView?.visibility = View.GONE
    }

    /** PiP 进入时隐藏 */
    fun setHiding(isHiding: Boolean) {
        hiding = isHiding
        if (isHiding) {
            cardView?.visibility = View.GONE
        }
    }

    /** 根据当前请求的可见性和隐藏状态刷新视图 */
    fun applyVisibility() {
        if (hiding) return
        cardView?.visibility = requestedVisibility
    }
}
