package com.limelight.handbook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ActionMode
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.limelight.R
import com.limelight.utils.BrowserOnlyLauncher
import com.limelight.utils.UiHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class HandbookActivity : ComponentActivity() {
    private val repository by lazy { HandbookRepository(applicationContext) }

    private var currentPage = HandbookUrlPolicy.index
    private var loadJob: Job? = null
    private var uiState by mutableStateOf<HandbookUiState>(HandbookUiState.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)

        val systemBarColor = 0xFF16162A.toInt()
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor

        currentPage = HandbookLauncher.pageFromIntent(intent)
        setContent {
            HandbookTheme {
                HandbookScreen(
                    state = uiState,
                    onExit = ::finish,
                    onRetry = { loadPage(currentPage) },
                    onNavigate = ::navigateTo,
                    onOpenExternal = { BrowserOnlyLauncher.open(this, it) }
                )
            }
        }
        UiHelper.notifyNewRootView(this)
        loadPage(currentPage)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun navigateTo(page: HandbookPageRef) {
        if (page == currentPage) return
        currentPage = page
        loadPage(page)
    }

    private fun loadPage(page: HandbookPageRef) {
        loadJob?.cancel()
        uiState = HandbookUiState.Loading
        loadJob = lifecycleScope.launch {
            uiState = when (val result = repository.load(page)) {
                is HandbookLoadResult.Success -> HandbookUiState.Content(
                    html = result.html,
                    baseUrl = result.baseUrl
                )
                is HandbookLoadResult.Failure -> HandbookUiState.Error(result.reason)
            }
        }
    }
}

private sealed class HandbookUiState {
    data object Loading : HandbookUiState()
    data class Content(val html: String, val baseUrl: String) : HandbookUiState()
    data class Error(val reason: HandbookFailureReason) : HandbookUiState()
}

@Composable
private fun HandbookTheme(content: @Composable () -> Unit) {
    val background = colorResource(R.color.advance_setting_background)
    val panel = colorResource(R.color.crown_panel_background)
    val accent = colorResource(R.color.crown_accent)
    val primaryText = colorResource(R.color.crown_text_primary)
    val secondaryText = colorResource(R.color.crown_text_secondary)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF1B1B22),
            background = background,
            onBackground = primaryText,
            surface = panel,
            onSurface = primaryText,
            onSurfaceVariant = secondaryText
        ),
        content = content
    )
}

@Composable
private fun HandbookScreen(
    state: HandbookUiState,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    onNavigate: (HandbookPageRef) -> Unit,
    onOpenExternal: (String) -> Unit
) {
    BackHandler(onBack = onExit)
    val background = colorResource(R.color.advance_setting_background)
    val panel = colorResource(R.color.crown_panel_background)
    val primaryText = colorResource(R.color.crown_text_primary)
    val secondaryText = colorResource(R.color.crown_text_secondary)
    val accent = colorResource(R.color.crown_accent)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panel)
                    .statusBarsPadding()
                    .height(58.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onExit) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_right),
                        contentDescription = stringResource(R.string.handbook_back),
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(180f),
                        tint = primaryText
                    )
                }
                Text(
                    text = stringResource(R.string.title_document_handbook),
                    color = primaryText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (state) {
                    HandbookUiState.Loading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = accent)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.handbook_loading),
                                color = secondaryText
                            )
                        }
                    }
                    is HandbookUiState.Error -> {
                        val message = when (state.reason) {
                            HandbookFailureReason.NETWORK -> R.string.handbook_error_network
                            HandbookFailureReason.TIMEOUT -> R.string.handbook_error_timeout
                            HandbookFailureReason.UNAVAILABLE -> R.string.handbook_error_unavailable
                        }
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(message),
                                color = primaryText
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accent,
                                    contentColor = Color(0xFF1B1B22)
                                )
                            ) {
                                Text(stringResource(R.string.handbook_retry))
                            }
                        }
                    }
                    is HandbookUiState.Content -> {
                        HandbookDocument(
                            content = state,
                            onNavigate = onNavigate,
                            onOpenExternal = onOpenExternal
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun HandbookDocument(
    content: HandbookUiState.Content,
    onNavigate: (HandbookPageRef) -> Unit,
    onOpenExternal: (String) -> Unit
) {
    val latestNavigate by rememberUpdatedState(onNavigate)
    val latestOpenExternal by rememberUpdatedState(onOpenExternal)
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            createLockedHandbookWebView(
                context = context,
                onNavigate = { latestNavigate(it) },
                onOpenExternal = { latestOpenExternal(it) }
            ).also { webView = it }
        },
        update = { view ->
            if (view.tag != content) {
                view.tag = content
                view.loadDataWithBaseURL(
                    content.baseUrl,
                    content.html,
                    "text/html",
                    "UTF-8",
                    content.baseUrl
                )
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createLockedHandbookWebView(
    context: android.content.Context,
    onNavigate: (HandbookPageRef) -> Unit,
    onOpenExternal: (String) -> Unit
): WebView {
    val legacyLinkTapTracker = LegacyLinkTapTracker(context)
    return LockedHandbookWebView(context).apply {
        setBackgroundColor(AndroidColor.WHITE)
        isFocusable = true
        isFocusableInTouchMode = true
        isLongClickable = false
        setOnTouchListener { _, event ->
            legacyLinkTapTracker.record(this, event)
            false
        }
        applyLockedSettings(this)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame || request.method != "GET") return true
                val hasGesture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    legacyLinkTapTracker.clear()
                    request.hasGesture()
                } else {
                    legacyLinkTapTracker.consume(request.url.toString())
                }
                return routeNavigation(
                    view.url,
                    request.url.toString(),
                    hasGesture,
                    onNavigate,
                    onOpenExternal
                )
            }

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return routeNavigation(
                    view.url,
                    url,
                    legacyLinkTapTracker.consume(url),
                    onNavigate,
                    onOpenExternal
                )
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse = emptyResponse()

            @Deprecated("Deprecated in Android")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse {
                return emptyResponse()
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                if (!isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popup = createNavigationCaptureWebView(
                    context,
                    onNavigate,
                    onOpenExternal
                )
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        setDownloadListener { _, _, _, _, _ -> Unit }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun applyLockedSettings(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        domStorageEnabled = false
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = true
        setGeolocationEnabled(false)
        setSupportMultipleWindows(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingEnabled = true
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createNavigationCaptureWebView(
    context: android.content.Context,
    onNavigate: (HandbookPageRef) -> Unit,
    onOpenExternal: (String) -> Unit
): WebView {
    val popup = WebView(context)
    applyLockedSettings(popup)
    var handled = false

    fun handle(url: String?) {
        if (handled || url == null) return
        handled = true
        HandbookUrlPolicy.parse(url)?.let(onNavigate)
            ?: if (HandbookUrlPolicy.isExternalHttps(url)) onOpenExternal(url) else Unit
        popup.stopLoading()
        popup.destroy()
    }

    popup.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            if (request.isForMainFrame && request.method == "GET") {
                handle(request.url.toString())
            }
            return true
        }

        @Deprecated("Deprecated in Android")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            handle(url)
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse = emptyResponse()
    }

    Handler(Looper.getMainLooper()).postDelayed({
        if (!handled) {
            handled = true
            popup.stopLoading()
            popup.destroy()
        }
    }, POPUP_CAPTURE_TIMEOUT_MS)
    return popup
}

private fun routeNavigation(
    currentUrl: String?,
    targetUrl: String,
    hasUserGesture: Boolean,
    onNavigate: (HandbookPageRef) -> Unit,
    onOpenExternal: (String) -> Unit
): Boolean {
    if (!hasUserGesture) return true

    val currentPage = HandbookUrlPolicy.parse(currentUrl)
    val targetPage = HandbookUrlPolicy.parse(targetUrl)
    if (currentPage != null && targetPage != null &&
        currentPage.copy(encodedFragment = null) == targetPage.copy(encodedFragment = null)
    ) {
        return targetPage.encodedFragment == null
    }

    if (targetPage != null) {
        onNavigate(targetPage)
    } else if (HandbookUrlPolicy.isExternalHttps(targetUrl)) {
        onOpenExternal(targetUrl)
    }
    return true
}

private fun emptyResponse(): WebResourceResponse {
    return WebResourceResponse(
        "text/plain",
        "UTF-8",
        ByteArrayInputStream(ByteArray(0))
    )
}

private class LegacyLinkTapTracker(context: Context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val touchSlopSquared = touchSlop * touchSlop

    private var downX = 0f
    private var downY = 0f
    private var isTapCandidate = false
    private var pendingTap: PendingLinkTap? = null

    fun record(view: WebView, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isTapCandidate = true
                pendingTap = null
            }

            MotionEvent.ACTION_MOVE -> {
                if (movedBeyondTouchSlop(event)) {
                    isTapCandidate = false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_CANCEL -> {
                isTapCandidate = false
                pendingTap = null
            }

            MotionEvent.ACTION_UP -> {
                pendingTap = if (isTapCandidate && !movedBeyondTouchSlop(event)) {
                    view.hitTestResult
                        .takeIf {
                            it.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                                it.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                        }
                        ?.extra
                        ?.let { PendingLinkTap(it, event.eventTime) }
                } else {
                    null
                }
                isTapCandidate = false
            }
        }
    }

    fun consume(targetUrl: String): Boolean {
        val tap = pendingTap ?: return false
        pendingTap = null
        val age = SystemClock.uptimeMillis() - tap.eventTime
        return age in 0..LEGACY_LINK_TAP_TIMEOUT_MS && urlsMatch(tap.url, targetUrl)
    }

    fun clear() {
        pendingTap = null
    }

    private fun movedBeyondTouchSlop(event: MotionEvent): Boolean {
        val deltaX = event.x - downX
        val deltaY = event.y - downY
        return deltaX * deltaX + deltaY * deltaY > touchSlopSquared
    }

    private fun urlsMatch(first: String, second: String): Boolean {
        val firstUri = android.net.Uri.parse(first)
        val secondUri = android.net.Uri.parse(second)
        return firstUri.scheme.equals(secondUri.scheme, ignoreCase = true) &&
            firstUri.host.equals(secondUri.host, ignoreCase = true) &&
            firstUri.port == secondUri.port &&
            firstUri.encodedPath == secondUri.encodedPath &&
            firstUri.encodedQuery == secondUri.encodedQuery &&
            firstUri.encodedFragment == secondUri.encodedFragment
    }
}

private data class PendingLinkTap(
    val url: String,
    val eventTime: Long
)

private class LockedHandbookWebView(context: Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = null

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = null
}

private const val LEGACY_LINK_TAP_TIMEOUT_MS = 1_000L
private const val POPUP_CAPTURE_TIMEOUT_MS = 1_500L
