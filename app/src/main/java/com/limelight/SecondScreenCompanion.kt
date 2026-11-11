@file:Suppress("DEPRECATION")

package com.limelight

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.limelight.gamemenu.AudioHapticsCardController
import com.limelight.gamemenu.BitrateCardController
import com.limelight.gamemenu.GyroCardController
import com.limelight.perfhud.ChainSegment
import com.limelight.perfhud.PerfHudPalette
import com.limelight.perfhud.PerfHudSnapshot
import com.limelight.perfhud.PerfSeverity
import java.util.Locale

/**
 * 副屏伴生面板。
 *
 * 串流画面仍留在主屏，第二块屏显示监控、码率、输入开关和快捷操作。
 * 只在“第二块屏比主屏小”（掌机内屏）时启用，外接大屏仍沿用原来的
 * [ExternalDisplayManager] 串流到外屏的行为。
 */
class SecondScreenCompanion(
    private val activity: Game,
    private val display: Display,
    private val perfManager: PerformanceOverlayManager?
) {
    private var presentation: CompanionPresentation? = null

    val displayId: Int = display.displayId

    fun show() {
        if (presentation != null) return
        val next = CompanionPresentation(activity, display)
        try {
            next.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            LimeLog.warning("Unable to show companion on display $displayId: ${e.message}")
            next.releaseAfterFailedShow()
            return
        }
        presentation = next
        perfManager?.setSnapshotListener { snapshot -> next.updateSnapshot(snapshot) }
    }

    fun dismiss() {
        perfManager?.setSnapshotListener(null)
        presentation?.dismiss()
        presentation = null
    }

    private class CompanionPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display) {

        private val game = outerContext as Game
        private val gyroController = GyroCardController(game)
        private val audioHapticsController = AudioHapticsCardController(game)
        private var gyroSyncing = false
        private var audioSyncing = false
        private var touchpadSyncing = false

        private lateinit var pages: List<View>
        private lateinit var tabs: List<TextView>

        private lateinit var monitorFps: TextView
        private lateinit var monitorFpsSub: TextView
        private lateinit var monitorFrameTime: TextView
        private lateinit var monitorLowFps: TextView
        private lateinit var monitorDecode: TextView
        private lateinit var statBandwidth: TextView
        private lateinit var statRtt: TextView
        private lateinit var statLoss: TextView
        private lateinit var statBattery: TextView
        private lateinit var chainBarViews: List<View>
        private lateinit var monitorChainTotal: TextView
        private lateinit var monitorChainText: TextView

        private lateinit var bitrateValue: TextView
        private lateinit var bitrateSeek: SeekBar
        private lateinit var bitrateHint: TextView
        private lateinit var bitrateAbrSwitch: Switch

        private val actionExecutor by lazy { StreamActionExecutor(game, { game.conn }) }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

            setContentView(R.layout.companion_screen)
            bindViews()
            bindTabs()
            bindQuickKeys()
            bindHeader()
            bindBitrate()
            bindInputToggles()
        }

        override fun onStop() {
            gyroController.dispose()
            audioHapticsController.dispose()
            super.onStop()
        }

        fun releaseAfterFailedShow() {
            gyroController.dispose()
            audioHapticsController.dispose()
        }

        private fun bindViews() {
            pages = listOf(
                findViewById(R.id.pageMonitor),
                findViewById(R.id.pageBitrate),
                findViewById(R.id.pageInput),
                findViewById(R.id.pageQuick)
            )
            tabs = listOf(
                findViewById(R.id.tabMonitor),
                findViewById(R.id.tabBitrate),
                findViewById(R.id.tabInput),
                findViewById(R.id.tabQuick)
            )

            monitorFps = findViewById(R.id.monitorFps)
            monitorFpsSub = findViewById(R.id.monitorFpsSub)
            monitorFrameTime = findViewById(R.id.monitorFrameTime)
            monitorLowFps = findViewById(R.id.monitorLowFps)
            monitorDecode = findViewById(R.id.monitorDecode)
            statBandwidth = findViewById(R.id.statBandwidth)
            statRtt = findViewById(R.id.statRtt)
            statLoss = findViewById(R.id.statLoss)
            statBattery = findViewById(R.id.statBattery)
            chainBarViews = listOf(
                findViewById(R.id.chainNetwork),
                findViewById(R.id.chainHost),
                findViewById(R.id.chainDecode),
                findViewById(R.id.chainRender)
            )
            monitorChainTotal = findViewById(R.id.monitorChainTotal)
            monitorChainText = findViewById(R.id.monitorChainText)

            bitrateValue = findViewById(R.id.bitrateValue)
            bitrateSeek = findViewById(R.id.bitrateSeek)
            bitrateHint = findViewById(R.id.bitrateHint)
            bitrateAbrSwitch = findViewById(R.id.bitrateAbrSwitch)
        }

        private fun bindTabs() {
            tabs.forEachIndexed { index, tab ->
                tab.setOnClickListener { selectTab(index) }
            }
            selectTab(0)
            // 掌机优先：进入面板时焦点落在当前 Tab，可用十字键左右切换。
            tabs.firstOrNull()?.post { tabs.firstOrNull()?.requestFocus() }
        }

        private fun selectTab(index: Int) {
            pages.forEachIndexed { i, page ->
                page.visibility = if (i == index) View.VISIBLE else View.GONE
            }
            tabs.forEachIndexed { i, tab ->
                tab.isSelected = i == index
            }
        }

        private fun bindQuickKeys() {
            fun quick(id: Int, actionId: String) {
                findViewById<TextView>(id).setOnClickListener {
                    actionExecutor.execute(actionId)
                }
            }

            quick(R.id.quickEsc, "send_esc")
            quick(R.id.quickWin, "send_win")
            quick(R.id.quickAltTab, "send_alt_tab")
            quick(R.id.quickKeyboard, "toggle_keyboard")
            quick(R.id.quickPerf, "toggle_perf")
            quick(R.id.quickHdr, "toggle_hdr")
            quick(R.id.quickMic, "toggle_mic")
            quick(R.id.quickController, "toggle_controller")
            findViewById<TextView>(R.id.quickQuit).setOnClickListener {
                game.disconnect()
            }
        }

        private fun bindHeader() {
            findViewById<TextView>(R.id.headerMenu).setOnClickListener {
                game.showGameMenu(null)
            }
        }

        private fun bindBitrate() {
            bitrateSeek.max = BitrateCardController.MAX_PROGRESS
            val initialKbps = game.prefConfig.bitrate
            bitrateSeek.progress = BitrateCardController.bitrateToProgress(initialKbps)
            updateBitrateLabel(BitrateCardController.progressToBitrateKbps(bitrateSeek.progress))

            bitrateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val kbps = BitrateCardController.progressToBitrateKbps(progress)
                    updateBitrateLabel(kbps)
                    if (fromUser) {
                        bitrateHint.text = "松开滑块后立即应用到当前串流"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    applyBitrate(BitrateCardController.progressToBitrateKbps(seekBar?.progress ?: 0))
                }
            })

            bitrateAbrSwitch.isChecked = game.prefConfig.enableAdaptiveBitrate
            bitrateAbrSwitch.setOnCheckedChangeListener { _, checked ->
                game.prefConfig.enableAdaptiveBitrate = checked
                if (checked) {
                    game.startAdaptiveBitrateIfEnabled()
                } else {
                    game.stopAdaptiveBitrate()
                    bitrateSeek.progress =
                        BitrateCardController.bitrateToProgress(game.prefConfig.bitrate)
                    updateBitrateLabel(game.prefConfig.bitrate)
                }
                game.prefConfig.writePreferences(game)
                bitrateHint.text = if (checked) "动态码率已开启" else "动态码率已关闭"
            }
        }

        private fun updateBitrateLabel(kbps: Int) {
            val mbps = kbps / 1000.0
            val text = if (mbps < 10) String.format(Locale.US, "%.1f", mbps)
            else String.format(Locale.US, "%.0f", mbps)
            bitrateValue.text = text
        }

        private fun applyBitrate(kbps: Int) {
            game.prefConfig.bitrate = kbps
            game.prefConfig.writePreferences(game)
            updateBitrateLabel(kbps)
            val connection = game.conn
            if (connection == null) {
                bitrateHint.text = "当前未连接，已保存为下次串流码率"
                return
            }
            bitrateHint.text = "正在切换到 ${kbps / 1000} Mbps…"
            connection.setBitrate(kbps, null)
            bitrateHint.text = "已请求切换到 ${kbps / 1000} Mbps"
        }

        private fun bindInputToggles() {
            val gyroSwitch = findViewById<Switch>(R.id.inputGyroSwitch)
            gyroSwitch.setOnCheckedChangeListener { _, checked ->
                if (!gyroSyncing) gyroController.setEnabled(checked)
            }
            gyroController.start { state ->
                gyroSyncing = true
                gyroSwitch.isChecked = state.enabled
                gyroSyncing = false
            }

            val audioSwitch = findViewById<Switch>(R.id.inputAudioHapticsSwitch)
            audioSwitch.setOnCheckedChangeListener { _, checked ->
                if (!audioSyncing) audioHapticsController.setEnabled(checked)
            }
            audioHapticsController.start { state ->
                audioSyncing = true
                audioSwitch.isChecked = state.enabled
                audioSyncing = false
            }

            val touchpadSwitch = findViewById<Switch>(R.id.inputTouchpadSwitch)
            touchpadSwitch.isChecked = game.prefConfig.screenDs5Touchpad
            touchpadSwitch.setOnCheckedChangeListener { _, checked ->
                if (touchpadSyncing) return@setOnCheckedChangeListener
                val enabled = game.screenDs5Touchpad.toggle()
                if (enabled != checked) {
                    touchpadSyncing = true
                    touchpadSwitch.isChecked = enabled
                    touchpadSyncing = false
                }
                Toast.makeText(
                    context,
                    if (enabled) "手柄触控板已开启" else "手柄触控板已关闭",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        fun updateSnapshot(snapshot: PerfHudSnapshot) {
            monitorFps.text = formatFps(snapshot.renderedFps)
            monitorFps.setTextColor(severityColor(snapshot.fpsSeverity))
            monitorFpsSub.text = if (snapshot.width > 0 && snapshot.height > 0) {
                "${snapshot.width}×${snapshot.height} · ${snapshot.targetFps} FPS · ${snapshot.codec}"
            } else {
                "等待串流数据"
            }

            monitorFrameTime.text = if (snapshot.renderedFps > 0f) {
                String.format(Locale.US, "%.1f ms", 1000f / snapshot.renderedFps)
            } else {
                "--"
            }
            monitorLowFps.text = formatFps(snapshot.onePercentLowFps)
            monitorLowFps.setTextColor(severityColor(snapshot.onePercentLowSeverity))
            monitorDecode.text = String.format(Locale.US, "%.1f ms", snapshot.latency.decodeMs)
            monitorDecode.setTextColor(severityColor(snapshot.decodeSeverity))

            statBandwidth.text = snapshot.bandwidth
            statRtt.text = "${snapshot.rttMs} ms"
            statRtt.setTextColor(severityColor(snapshot.rttSeverity))
            statLoss.text = String.format(Locale.US, "%.1f%%", snapshot.packetLossPercent)
            statLoss.setTextColor(severityColor(snapshot.lossSeverity))
            statBattery.text = if (snapshot.batteryLevel >= 0) {
                "${snapshot.batteryLevel}%" + if (snapshot.charging) " ⚡" else ""
            } else {
                "--"
            }
            statBattery.setTextColor(severityColor(snapshot.batterySeverity))

            updateLatencyChain(snapshot.latency.segments())
        }

        private fun updateLatencyChain(segments: List<ChainSegment>) {
            var total = 0f
            segments.forEachIndexed { index, segment ->
                total += segment.ms
                val view = chainBarViews.getOrNull(index) ?: return@forEachIndexed
                view.setBackgroundColor(segment.color)
                val params = view.layoutParams as android.widget.LinearLayout.LayoutParams
                params.weight = segment.ms.coerceAtLeast(0.1f)
                view.layoutParams = params
            }
            monitorChainTotal.text = String.format(Locale.US, "%.1f ms", total)
            monitorChainText.text = String.format(
                Locale.US,
                "网络 %.0f · 主机 %.0f · 解码 %.0f · 渲染 %.0f",
                segments.getOrNull(0)?.ms ?: 0f,
                segments.getOrNull(1)?.ms ?: 0f,
                segments.getOrNull(2)?.ms ?: 0f,
                segments.getOrNull(3)?.ms ?: 0f
            )
        }

        private fun formatFps(fps: Float): String {
            return if (fps > 0f) String.format(Locale.US, "%.0f", fps) else "--"
        }

        private fun severityColor(severity: PerfSeverity): Int {
            return when (severity) {
                PerfSeverity.OK -> PerfHudPalette.OK
                PerfSeverity.WARN -> PerfHudPalette.WARN
                PerfSeverity.BAD -> PerfHudPalette.BAD
            }
        }
    }
}
