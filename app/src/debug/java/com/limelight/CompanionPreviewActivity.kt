package com.limelight

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView

/**
 * Debug-only preview of the second-screen companion layout.
 * Run: adb shell am start -n com.limelight.vplus_debug/com.limelight.CompanionPreviewActivity
 */
class CompanionPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.companion_screen)

        findViewById<TextView>(R.id.monitorFps).text = "120"
        findViewById<TextView>(R.id.monitorFpsSub).text = "1920×1080 · 120 FPS · AV1"
        findViewById<TextView>(R.id.monitorFrameTime).text = "8.3 ms"
        findViewById<TextView>(R.id.monitorLowFps).text = "118"
        findViewById<TextView>(R.id.monitorDecode).text = "2.1 ms"
        findViewById<TextView>(R.id.statBandwidth).text = "62.4 Mbps"
        findViewById<TextView>(R.id.statRtt).text = "9 ms"
        findViewById<TextView>(R.id.statLoss).text = "0.0%"
        findViewById<TextView>(R.id.statBattery).text = "82%"
        findViewById<TextView>(R.id.monitorChainTotal).text = "12.6 ms"
        findViewById<TextView>(R.id.monitorChainText).text = "网络 4 · 主机 3 · 解码 2 · 渲染 3"

        findViewById<TextView>(R.id.bitrateValue).text = "62"
        findViewById<SeekBar>(R.id.bitrateSeek).progress = 49

        val pages = listOf(
            findViewById<View>(R.id.pageMonitor),
            findViewById<View>(R.id.pageBitrate),
            findViewById<View>(R.id.pageInput),
            findViewById<View>(R.id.pageQuick)
        )
        val tabs = listOf(
            findViewById<TextView>(R.id.tabMonitor),
            findViewById<TextView>(R.id.tabBitrate),
            findViewById<TextView>(R.id.tabInput),
            findViewById<TextView>(R.id.tabQuick)
        )
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                pages.forEachIndexed { i, page ->
                    page.visibility = if (i == index) View.VISIBLE else View.GONE
                }
                tabs.forEachIndexed { i, t -> t.isSelected = i == index }
            }
        }
        tabs[0].isSelected = true
    }
}
