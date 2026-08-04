package com.limelight

import android.content.res.ColorStateList
import android.graphics.Point
import android.os.Build
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.limelight.binding.video.PerformanceInfo
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.utils.UiHelper
import java.util.Locale

/**
 * Controls the dashboard shown alongside the stream. On built-in dual-screen handhelds the
 * dashboard is hosted by a Presentation on the lower panel. For a conventional external
 * display, it remains in the Activity while the stream is rendered externally.
 */
class DualScreenControlPanel(private val game: Game) {
    private val actionExecutor = StreamActionExecutor(game, { game.conn })

    private var root: View? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var statusDetail: TextView? = null
    private var sessionTitle: TextView? = null
    private var targetDisplayText: TextView? = null
    private var resolutionValue: TextView? = null
    private var codecValue: TextView? = null
    private var fpsValue: TextView? = null
    private var rttValue: TextView? = null
    private var decodeValue: TextView? = null
    private var packetLossValue: TextView? = null
    private var bandwidthValue: TextView? = null
    private var batteryValue: TextView? = null
    private var connectedControls: List<View> = emptyList()

    private var active = false
    private enum class ConnectionQuality {
        CONNECTING,
        GOOD,
        POOR,
        DISCONNECTED
    }

    fun initialize(
        container: View = game.window.decorView,
        allowCloseControlScreen: Boolean = false
    ) {
        active = false
        clearBindings()

        val panelRoot = container.findViewById<View>(R.id.dualScreenControlPanel) ?: return
        root = panelRoot
        statusDot = panelRoot.findViewById(R.id.dualScreenStatusDot)
        statusText = panelRoot.findViewById(R.id.dualScreenStatusText)
        statusDetail = panelRoot.findViewById(R.id.dualScreenStatusDetail)
        sessionTitle = panelRoot.findViewById(R.id.dualScreenSessionTitle)
        targetDisplayText = panelRoot.findViewById(R.id.dualScreenTargetDisplay)
        resolutionValue = panelRoot.findViewById(R.id.dualScreenResolutionValue)
        codecValue = panelRoot.findViewById(R.id.dualScreenCodecValue)
        fpsValue = panelRoot.findViewById(R.id.dualScreenFpsValue)
        rttValue = panelRoot.findViewById(R.id.dualScreenRttValue)
        decodeValue = panelRoot.findViewById(R.id.dualScreenDecodeValue)
        packetLossValue = panelRoot.findViewById(R.id.dualScreenPacketLossValue)
        bandwidthValue = panelRoot.findViewById(R.id.dualScreenBandwidthValue)
        batteryValue = panelRoot.findViewById(R.id.dualScreenBatteryValue)
        panelRoot.findViewById<FrameLayout>(R.id.dualScreenKeyboardContainer)?.let {
            game.bindDualScreenKeyboard(it)
        }

        val menu = panelRoot.findViewById<View>(R.id.dualScreenMenuButton)?.also { view ->
            view.setOnClickListener { game.showGameMenuOnControlDisplay(view) }
        }
        val keyboard = bindAction(R.id.dualScreenKeyboardButton) {
            game.toggleDualScreenVirtualKeyboard()
        }
        val escape = bindAction(R.id.dualScreenEscapeButton) { actionExecutor.execute("send_esc") }
        val windows = bindAction(R.id.dualScreenWindowsButton) { actionExecutor.execute("send_win") }
        val taskSwitch = bindAction(R.id.dualScreenTaskSwitchButton) { actionExecutor.execute("send_alt_tab") }
        val controller = bindAction(R.id.dualScreenControllerButton) { game.toggleVirtualController() }
        val microphone = bindAction(R.id.dualScreenMicrophoneButton) { game.handleMicrophoneMenuAction() }
        panelRoot.findViewById<View>(R.id.dualScreenCloseControlButton)?.also { view ->
            view.visibility = if (allowCloseControlScreen) View.VISIBLE else View.GONE
            view.setOnClickListener(
                if (allowCloseControlScreen) {
                    View.OnClickListener { game.setDualScreenControlsEnabled(false) }
                } else {
                    null
                }
            )
        }
        bindAction(R.id.dualScreenDisconnectButton) { game.disconnect() }

        connectedControls = listOfNotNull(
            menu,
            keyboard,
            escape,
            windows,
            taskSwitch,
            controller,
            microphone
        )

        sessionTitle?.text = game.getString(
            R.string.dual_screen_session_title,
            game.pcName ?: game.getString(R.string.dual_screen_unknown_host),
            game.appName ?: game.app.appName
        )
        batteryValue?.text = game.getString(
            R.string.dual_screen_battery_value,
            UiHelper.getBatteryLevel(game)
        )
        resolutionValue?.setText(R.string.dual_screen_metric_pending)
        codecValue?.setText(R.string.dual_screen_metric_pending)
        setControlsEnabled(false)
    }

    fun show(targetDisplay: Display) {
        active = true
        targetDisplayText?.text = formatTargetDisplay(targetDisplay)
        root?.visibility = View.VISIBLE
        if (game.connected) {
            updateConnectionQuality(MoonBridge.CONN_STATUS_OKAY)
        } else {
            updateStatus(
                ConnectionQuality.CONNECTING,
                R.string.dual_screen_status_connecting,
                game.getString(R.string.dual_screen_status_connecting_detail)
            )
        }
    }

    fun hide() {
        active = false
        game.hideDualScreenVirtualKeyboard()
        root?.visibility = View.GONE
    }

    fun updateConnectionStage(stage: String) {
        if (!active) return
        updateStatus(
            ConnectionQuality.CONNECTING,
            R.string.dual_screen_status_connecting,
            game.getString(R.string.dual_screen_status_stage, stage)
        )
    }

    fun updateConnectionStarted() {
        if (!active) return
        updateStatus(
            ConnectionQuality.GOOD,
            R.string.dual_screen_status_connected,
            game.getString(R.string.dual_screen_status_connected_detail)
        )
    }

    fun updateConnectionQuality(connectionStatus: Int) {
        if (!active) return
        if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
            updateStatus(
                ConnectionQuality.POOR,
                R.string.dual_screen_status_poor,
                game.getString(R.string.dual_screen_status_poor_detail)
            )
        } else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
            updateStatus(
                ConnectionQuality.GOOD,
                R.string.dual_screen_status_connected,
                game.getString(R.string.dual_screen_status_connected_detail)
            )
        }
    }

    fun updateConnectionFailed(detail: String) {
        if (!active) return
        updateStatus(
            ConnectionQuality.DISCONNECTED,
            R.string.dual_screen_status_failed,
            detail
        )
    }

    fun updateConnectionStopped() {
        if (!active) return
        updateStatus(
            ConnectionQuality.DISCONNECTED,
            R.string.dual_screen_status_disconnected,
            game.getString(R.string.dual_screen_status_disconnected_detail)
        )
    }

    fun updatePerformanceInfo(info: PerformanceInfo) {
        if (!active) return
        game.runOnUiThread {
            resolutionValue?.text = DualScreenMetricFormatter.resolution(
                info.initialWidth,
                info.initialHeight
            )
            codecValue?.text = DualScreenMetricFormatter.codec(info.decoder, info.isHdrActive)
            fpsValue?.text = DualScreenMetricFormatter.fps(info.renderedFps, info.receivedFps)
            rttValue?.text = DualScreenMetricFormatter.rtt(info.rttInfo)
            decodeValue?.text = DualScreenMetricFormatter.latency(info.decodeTimeMs)
            packetLossValue?.text = DualScreenMetricFormatter.packetLoss(info.lostFrameRate)
            bandwidthValue?.text = info.bandWidth?.takeIf { it.isNotBlank() }
                ?: game.getString(R.string.dual_screen_metric_pending)
            batteryValue?.text = game.getString(
                R.string.dual_screen_battery_value,
                UiHelper.getBatteryLevel(game)
            )
        }
    }

    fun release() {
        active = false
        clearBindings()
    }

    private fun clearBindings() {
        game.releaseDualScreenKeyboard()
        root = null
        statusDot = null
        statusText = null
        statusDetail = null
        sessionTitle = null
        targetDisplayText = null
        resolutionValue = null
        codecValue = null
        fpsValue = null
        rttValue = null
        decodeValue = null
        packetLossValue = null
        bandwidthValue = null
        batteryValue = null
        connectedControls = emptyList()
    }

    private fun bindAction(viewId: Int, action: () -> Unit): View? {
        return root?.findViewById<View>(viewId)?.also { view ->
            view.setOnClickListener { action() }
        }
    }

    private fun updateStatus(
        quality: ConnectionQuality,
        titleRes: Int,
        detail: String
    ) {
        statusText?.setText(titleRes)
        statusDetail?.text = detail

        val colorRes = when (quality) {
            ConnectionQuality.CONNECTING -> R.color.dual_screen_status_connecting
            ConnectionQuality.GOOD -> R.color.dual_screen_status_good
            ConnectionQuality.POOR -> R.color.dual_screen_status_poor
            ConnectionQuality.DISCONNECTED -> R.color.dual_screen_status_disconnected
        }
        statusDot?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(game, colorRes)
        )
        setControlsEnabled(quality == ConnectionQuality.GOOD || quality == ConnectionQuality.POOR)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        connectedControls.forEach { control ->
            control.isEnabled = enabled
            control.alpha = if (enabled) 1f else 0.45f
        }
    }

    @Suppress("DEPRECATION")
    private fun formatTargetDisplay(display: Display): String {
        val realSize = Point()
        display.getRealSize(realSize)
        val refreshRate: Float
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            refreshRate = display.mode.refreshRate
        } else {
            refreshRate = display.refreshRate
        }
        return game.getString(
            R.string.dual_screen_target_display,
            display.name,
            realSize.x,
            realSize.y,
            refreshRate
        )
    }
}

internal object DualScreenMetricFormatter {
    fun resolution(width: Int, height: Int): String = "${width}x${height}"

    fun codec(decoder: String?, hdrActive: Boolean): String {
        val codec = decoder?.takeIf { it.isNotBlank() } ?: "--"
        return if (hdrActive) "$codec · HDR" else codec
    }

    fun fps(renderedFps: Float, receivedFps: Float): String =
        String.format(Locale.US, "%.1f / %.1f", renderedFps, receivedFps)

    fun rtt(rttInfo: Long): String = "${(rttInfo shr 32).toInt().coerceAtLeast(0)} ms"

    fun latency(milliseconds: Float): String =
        String.format(Locale.US, "%.1f ms", milliseconds.coerceAtLeast(0f))

    fun packetLoss(rate: Float): String =
        String.format(Locale.US, "%.2f%%", rate.coerceAtLeast(0f))
}
