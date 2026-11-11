@file:Suppress("DEPRECATION")

package com.limelight

import android.app.Activity
import android.app.Presentation
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.DateFormat
import java.util.Date
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

    /** AppView 里当前选中的应用；副屏据此显示详情并提供「开始串流」。 */
    data class AppContext(
        val hostName: String,
        val appName: String,
        val running: Boolean,
        val summary: String,
        val onPrimary: () -> Unit
    )

    private var presentation: BrowsePresentation? = null

    fun setAppContext(ctx: AppContext?) { presentation?.bindAppContext(ctx) ?: run { pendingAppContext = ctx } }
    private var pendingAppContext: AppContext? = null

    fun cycleTab(delta: Int) { presentation?.cycleTab(delta) }
    fun toggleRemoteFocus(): Boolean = presentation?.toggleRemoteFocus() ?: false
    fun handleKey(event: KeyEvent): Boolean = presentation?.handleKey(event) ?: false

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
        current = this
        CompanionDisplays.active = display
        pendingAppContext?.let { next.bindAppContext(it) }
    }

    fun dismiss() {
        presentation?.dismiss()
        presentation = null
        if (current === this) current = null
        if (CompanionDisplays.active?.displayId == display.displayId) CompanionDisplays.active = null
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
        private var tabIndex = 0
        private var remoteFocus = false
        private var appContext: AppContext? = null

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
            appContext = pendingAppContext
            applyAppContext()
        }

        fun cycleTab(delta: Int) {
            selectTab((tabIndex + delta + tabs.size) % tabs.size)
            if (remoteFocus) focusFirstOnPage()
        }

        fun bindAppContext(ctx: AppContext?) {
            appContext = ctx
            applyAppContext()
        }

        private fun applyAppContext() {
            val ctx = appContext
            val show = ctx != null
            findViewById<View>(R.id.appPanel).visibility = if (show) View.VISIBLE else View.GONE
            hostList.visibility = if (show) View.GONE else View.VISIBLE
            hostHint.visibility = if (show) View.GONE else View.VISIBLE
            if (ctx != null) {
                findViewById<TextView>(R.id.appPanelHost).text = context.getString(R.string.browse_app_host, ctx.hostName)
                findViewById<TextView>(R.id.appPanelName).text = ctx.appName
                findViewById<TextView>(R.id.appPanelStatus).apply {
                    text = context.getString(if (ctx.running) R.string.browse_app_running else R.string.browse_app_idle)
                    setTextColor(if (ctx.running) 0xFF6FD49A.toInt() else 0xFF8FA6B6.toInt())
                }
                findViewById<TextView>(R.id.appPanelSummary).text = ctx.summary
                findViewById<TextView>(R.id.appPanelAction).apply {
                    text = context.getString(if (ctx.running) R.string.browse_app_resume else R.string.browse_app_start)
                    setOnClickListener { ctx.onPrimary() }
                }
            }
            updateHint()
        }

        fun toggleRemoteFocus(): Boolean {
            remoteFocus = !remoteFocus
            if (remoteFocus) focusFirstOnPage() else {
                window?.decorView?.findFocus()?.clearFocus()
                applyFocusMode()
            }
            updateHint()
            return remoteFocus
        }

        /** 触摸模式下 View 默认拿不到焦点；手柄操作副屏期间临时放开，退出后还原，避免影响触摸点击。 */
        private fun applyFocusMode(view: View? = window?.decorView) {
            if (view == null) return
            if (view.isFocusable) view.isFocusableInTouchMode = remoteFocus
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) applyFocusMode(view.getChildAt(i))
            }
        }

        private fun focusFirstOnPage() {
            applyFocusMode()
            val target = when {
                tabIndex == 0 && appContext != null -> findViewById<View>(R.id.appPanelAction)
                else -> pages[tabIndex]
            }
            target.requestFocus()
        }

        /** 主屏收到的手柄按键在「副屏操作」模式下转交这里：方向键移焦点，A 点击，B 退出。 */
        fun handleKey(event: KeyEvent): Boolean {
            if (!remoteFocus) return false
            val isDown = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                    if (isDown) toggleRemoteFocus()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (isDown && event.repeatCount == 0) {
                        window?.decorView?.findFocus()?.performClick()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isDown) {
                        val dir = when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                            else -> View.FOCUS_RIGHT
                        }
                        val root = window?.decorView
                        val focused = root?.findFocus()
                        if (focused == null) focusFirstOnPage()
                        else (focused.focusSearch(dir) ?: focused).requestFocus(dir)
                    }
                    return true
                }
            }
            return false
        }

        private fun updateHint() {
            val res = when {
                remoteFocus -> R.string.browse_hint_remote
                tabIndex == 0 && appContext != null -> R.string.browse_hint_app
                else -> R.string.browse_hint_browse
            }
            findViewById<TextView>(R.id.browseHint).setText(res)
        }

        private fun bindStatus() {
            val hosts = hostsProvider()
            val online = hosts.count { it.state == ComputerDetails.State.ONLINE }
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.let {
                val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && sc > 0) l * 100 / sc else -1
            } ?: -1
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            val parts = mutableListOf(context.getString(R.string.browse_status_hosts, online, hosts.size))
            if (level >= 0) parts += "$level%"
            parts += time
            findViewById<TextView>(R.id.browseStatus).text = parts.joinToString(" · ")
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
            tabIndex = index
            pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
            if (index == 1) bindSettingsSummary()
            if (index == 2) bindMenu()
            updateHint()
        }

        private fun bindMenu() {
            val page = findViewById<LinearLayout>(R.id.pageMenu)
            page.removeAllViews()
            menuItems().forEach { page.addView(createMenuButton(it)) }
        }

        /** Compact list row: blue icon, title, one-line description on the right. */
        private fun createMenuButton(item: MenuItem): View {
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.companion_key_bg)
                isClickable = true
                isFocusable = true
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
                ).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
                if (item.iconRes != 0) {
                    addView(android.widget.ImageView(context).apply {
                        setImageResource(item.iconRes)
                        setColorFilter(0xFF66C0F4.toInt())
                    }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(16) })
                }
                addView(TextView(context).apply {
                    text = item.title
                    setTextColor(0xFFF7FBFF.toInt())
                    textSize = 19f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2))
                if (item.subtitle.isNotEmpty()) {
                    addView(TextView(context).apply {
                        text = item.subtitle
                        setTextColor(0xFF8FA6B6.toInt())
                        textSize = 14f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = android.view.Gravity.END
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
                }
                setOnClickListener { item.action() }
            }
        }

        private fun refresh() {
            bindStatus()
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

    companion object {
        /** 当前显示中的浏览态副屏（AppView 等界面据此推送选中项）。 */
        @Volatile
        var current: BrowseScreenCompanion? = null
            private set

        private const val MAX_ROWS = 3
        private const val REFRESH_INTERVAL_MS = 1000L
    }
}
