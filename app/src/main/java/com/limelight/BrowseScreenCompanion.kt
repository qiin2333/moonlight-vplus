@file:Suppress("DEPRECATION")

package com.limelight

import android.app.Activity
import android.app.Presentation
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.preferences.PreferenceConfiguration
import java.util.Locale

/**
 * 浏览态副屏：在主机列表界面（未串流）时显示在第二块屏上。
 *
 * 只展示已有的数据——主机列表/在线/配对状态，以及当前串流设置摘要——并把点击
 * 交还给 [PcView] 现有的处理逻辑，不新增任何业务功能。
 */
class BrowseScreenCompanion(
    private val activity: Activity,
    private val display: Display,
    private val hostsProvider: () -> List<ComputerDetails>,
    private val onHostClick: (ComputerDetails) -> Unit,
    private val onHostLongClick: (ComputerDetails) -> Unit,
    private val onOpenSettings: () -> Unit,
    private val menuItems: () -> List<MenuItem> = { emptyList() }
) {
    /** 菜单页的一项：标题 + 动作；动作沿用 [PcView] 里原有的处理逻辑。 */
    data class MenuItem(
        val title: String,
        @androidx.annotation.DrawableRes val iconRes: Int = 0,
        val subtitle: String = "",
        val action: () -> Unit
    )

    private var presentation: BrowsePresentation? = null

    fun show() {
        if (presentation != null) return
        val next = BrowsePresentation()
        try {
            next.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            LimeLog.warning("Unable to show browse companion on display ${display.displayId}: ${e.message}")
            return
        }
        presentation = next
    }

    fun dismiss() {
        presentation?.dismiss()
        presentation = null
    }

    private inner class BrowsePresentation : Presentation(activity, display) {
        private val handler = Handler(Looper.getMainLooper())
        private val tick = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }

        private lateinit var tabs: List<TextView>
        private lateinit var pages: List<View>
        private lateinit var hostList: LinearLayout
        private lateinit var hostHint: TextView
        private var hostSignature: List<String>? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // 返回键不能把副屏关掉：除非主屏退出，否则这里就是副屏的最后一页。
            setCancelable(false)
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

            setContentView(R.layout.companion_browse)
            tabs = listOf(findViewById(R.id.tabHosts), findViewById(R.id.tabSettings), findViewById(R.id.tabMenu))
            pages = listOf(findViewById(R.id.pageHosts), findViewById(R.id.pageSettings), findViewById(R.id.pageMenu))
            hostList = findViewById(R.id.hostList)
            hostHint = findViewById(R.id.hostHint)
            tabs.forEachIndexed { index, tab -> tab.setOnClickListener { selectTab(index) } }
            findViewById<View>(R.id.browseOpenSettings).setOnClickListener { onOpenSettings() }
            selectTab(0)
            refresh()
        }

        override fun onStart() {
            super.onStart()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, REFRESH_INTERVAL_MS)
        }

        override fun onStop() {
            handler.removeCallbacks(tick)
            super.onStop()
        }

        private fun selectTab(index: Int) {
            pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
            if (index == 1) bindSettingsSummary()
            if (index == 2) bindMenu()
        }

        private fun bindMenu() {
            val page = findViewById<LinearLayout>(R.id.pageMenu)
            page.removeAllViews()
            menuItems().chunked(2).forEach { pair ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                }
                pair.forEach { item -> row.addView(createMenuButton(item)) }
                if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                page.addView(row)
            }
        }

        /** Large tile: blue icon, 22sp title, 15sp one-line description. Readable at arm's length on the 3.9" screen. */
        private fun createMenuButton(item: MenuItem): View {
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setBackgroundResource(R.drawable.companion_key_bg)
                isClickable = true
                isFocusable = true
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                if (item.iconRes != 0) {
                    addView(android.widget.ImageView(context).apply {
                        setImageResource(item.iconRes)
                        setColorFilter(0xFF66C0F4.toInt())
                    }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { bottomMargin = dp(6) })
                }
                addView(TextView(context).apply {
                    text = item.title
                    setTextColor(0xFFF7FBFF.toInt())
                    textSize = 22f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (item.subtitle.isNotEmpty()) {
                    addView(TextView(context).apply {
                        text = item.subtitle
                        setTextColor(0xFF8FA6B6.toInt())
                        textSize = 15f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                }
                setOnClickListener { item.action() }
            }
        }

        private fun refresh() {
            if (pages[1].visibility == View.VISIBLE) bindSettingsSummary()
            bindHosts()
        }

        private fun bindHosts() {
            val hosts = hostsProvider()
            val visible = hosts.take(MAX_ROWS)
            val signature = visible.map { "${it.uuid}|${it.name}|${it.state}|${it.pairState}" } +
                    "more=${hosts.size - visible.size}"
            if (signature == hostSignature) return
            hostSignature = signature

            // 保住当前焦点所在的行，避免每秒重建导致手柄焦点丢失。
            val focusedIndex = (0 until hostList.childCount).firstOrNull { hostList.getChildAt(it).isFocused }
            hostList.removeAllViews()

            if (visible.isEmpty()) {
                hostList.addView(TextView(context).apply {
                    text = context.getString(R.string.browse_hosts_empty)
                    setTextColor(0xFF8FA6B6.toInt())
                    textSize = 16f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
                    )
                })
            } else {
                val inflater = LayoutInflater.from(context)
                visible.forEach { details -> hostList.addView(createRow(inflater, details)) }
                focusedIndex?.let { hostList.getChildAt(it.coerceAtMost(hostList.childCount - 1))?.requestFocus() }
            }

            val extra = hosts.size - visible.size
            hostHint.text = if (extra > 0) context.getString(R.string.browse_hosts_more, extra)
            else context.getString(R.string.browse_hosts_hint)
        }

        private fun createRow(inflater: LayoutInflater, details: ComputerDetails): View {
            val row = inflater.inflate(R.layout.companion_host_row, hostList, false)
            row.findViewById<TextView>(R.id.hostName).text = details.name
            val online = details.state == ComputerDetails.State.ONLINE
            val offline = details.state == ComputerDetails.State.OFFLINE
            val paired = details.pairState == PairState.PAIRED

            val stateText = when {
                online -> context.getString(R.string.pcview_menu_header_online)
                offline -> context.getString(R.string.pcview_menu_header_offline)
                else -> ""
            }
            val pairText = if (paired) context.getString(R.string.browse_paired)
            else context.getString(R.string.browse_unpaired)
            row.findViewById<TextView>(R.id.hostStatus).text =
                if (stateText.isEmpty()) pairText else "$stateText · $pairText"

            val color = when {
                online -> 0xFF6FD49A.toInt()
                offline -> 0xFF8FA6B6.toInt()
                else -> 0xFF5B6B78.toInt()
            }
            (row.findViewById<View>(R.id.hostDot).background.mutate() as GradientDrawable).setColor(color)

            row.findViewById<TextView>(R.id.hostAction).text = context.getString(
                when {
                    online && paired -> R.string.browse_action_connect
                    online -> R.string.browse_action_pair
                    else -> R.string.browse_action_more
                }
            )
            row.alpha = if (online) 1f else 0.6f
            row.setOnClickListener { onHostClick(details) }
            row.setOnLongClickListener { onHostLongClick(details); true }
            return row
        }

        private fun bindSettingsSummary() {
            val prefs = PreferenceConfiguration.readPreferences(context)
            findViewById<TextView>(R.id.setResolution).text =
                if (prefs.width > 0 && prefs.height > 0) "${prefs.width}×${prefs.height}" else "--"
            findViewById<TextView>(R.id.setFps).text =
                if (prefs.fps > 0) "${prefs.fps} FPS" else "--"
            val mbps = prefs.bitrate / 1000.0
            findViewById<TextView>(R.id.setBitrate).text =
                if (prefs.bitrate <= 0) "--"
                else if (mbps < 10) String.format(Locale.US, "%.1f Mbps", mbps)
                else String.format(Locale.US, "%.0f Mbps", mbps)
            findViewById<TextView>(R.id.setCodec).text = when (prefs.videoFormat) {
                PreferenceConfiguration.FormatOption.AUTO -> context.getString(R.string.browse_codec_auto)
                PreferenceConfiguration.FormatOption.FORCE_PYROWAVE -> "PyroWave"
                PreferenceConfiguration.FormatOption.FORCE_AV1 -> "AV1"
                PreferenceConfiguration.FormatOption.FORCE_HEVC -> "HEVC"
                PreferenceConfiguration.FormatOption.FORCE_H264 -> "H.264"
            }
            findViewById<TextView>(R.id.setHdr).text = context.getString(
                if (prefs.enableHdr) R.string.browse_on else R.string.browse_off
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 3
        const val REFRESH_INTERVAL_MS = 1000L
    }
}
