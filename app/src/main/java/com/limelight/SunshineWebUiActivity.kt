package com.limelight

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.limelight.utils.AppDialogStyler
import com.limelight.utils.UiHelper
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 内嵌 WebView 打开 Sunshine 主机的 Web 管理界面。
 *
 * 关键点：
 * 1. 通过传入的 serverCert（Moonlight 配对时持有的服务端证书）做 TLS pinning，
 *    匹配则忽略浏览器对自签证书的"不安全"警告，否则按系统默认行为拒绝。
 * 2. HTTP Basic Auth 弹出原生对话框收集凭据，并写回 WebView 的 HttpAuthDatabase
 *    便于同会话/同 host 复用，不持久化跨进程明文密码。
 */
class SunshineWebUiActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var pinnedCertSha256: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 隐藏顶部 title bar（默认 Activity 主题自带）；必须在 setContentView 之前调用
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        UiHelper.notifyNewRootView(this)
        setContentView(R.layout.activity_sunshine_webui)
        // 进入沉浸式：必须在 setContentView 之后，否则 DecorView 未初始化导致 NPE
        applyImmersiveMode()

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, R.string.pcview_open_webui_unavailable, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.pcview_menu_open_webui)
        pinnedCertSha256 = parsePinnedCertSha256(intent.getByteArrayExtra(EXTRA_SERVER_CERT))

        webView = findViewById(R.id.sunshine_webui)
        progressBar = findViewById(R.id.sunshine_webui_progress)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val pinned = pinnedCertSha256
                val presented = error.certificate?.x509Certificate
                if (pinned != null && presented != null && sha256(presented.encoded).contentEquals(pinned)) {
                    handler.proceed()
                    return
                }
                val dialog = AlertDialog.Builder(this@SunshineWebUiActivity, R.style.AppDialogStyle)
                    .setTitle(R.string.sunshine_webui_ssl_warning_title)
                    .setMessage(getString(R.string.sunshine_webui_ssl_warning_msg, error.url ?: ""))
                    .setPositiveButton(R.string.sunshine_webui_ssl_proceed) { _, _ -> handler.proceed() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> handler.cancel() }
                    .setOnCancelListener { handler.cancel() }
                    .show()
                AppDialogStyler.apply(dialog, this@SunshineWebUiActivity)
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String,
                realm: String
            ) {
                // 优先复用 WebView 自身的凭据存储（仅本进程数据目录内）
                val saved = view.getHttpAuthUsernamePassword(host, realm)
                if (saved != null && saved.size >= 2 && saved[0] != null && saved[1] != null) {
                    handler.proceed(saved[0], saved[1])
                    return
                }
                promptHttpAuth(view, handler, host, realm)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, t: String?) {
                if (!t.isNullOrEmpty()) title = t
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(url)
        }
    }

    private fun promptHttpAuth(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        val padding = (resources.displayMetrics.density * 16).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }
        val userEdit = EditText(this).apply {
            hint = getString(R.string.sunshine_webui_auth_user_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passEdit = EditText(this).apply {
            hint = getString(R.string.sunshine_webui_auth_pass_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(userEdit)
        container.addView(passEdit)

        val dialog = AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(getString(R.string.sunshine_webui_auth_title, host, realm))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val u = userEdit.text.toString()
                val p = passEdit.text.toString()
                view.setHttpAuthUsernamePassword(host, realm, u, p)
                handler.proceed(u, p)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
        AppDialogStyler.apply(dialog, this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        actionBar?.hide()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun parsePinnedCertSha256(der: ByteArray?): ByteArray? {
        if (der == null || der.isEmpty()) return null
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            sha256(cert.encoded)
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    companion object {
        const val EXTRA_URL = "com.limelight.SunshineWebUi.URL"
        const val EXTRA_TITLE = "com.limelight.SunshineWebUi.TITLE"
        const val EXTRA_SERVER_CERT = "com.limelight.SunshineWebUi.SERVER_CERT"

        @JvmStatic
        fun createIntent(
            context: android.content.Context,
            url: String,
            title: String?,
            serverCertDer: ByteArray?
        ): Intent = Intent(context, SunshineWebUiActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            if (title != null) putExtra(EXTRA_TITLE, title)
            if (serverCertDer != null) putExtra(EXTRA_SERVER_CERT, serverCertDer)
        }
    }
}
