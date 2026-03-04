package com.limelight

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

import com.limelight.utils.SpinnerDialog

class HelpActivity : Activity() {

    private var loadingDialog: SpinnerDialog? = null
    private lateinit var webView: WebView

    private var backCallbackRegistered = false
    private var onBackInvokedCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedCallback = OnBackInvokedCallback {
                // We should always be able to go back because we unregister our callback
                // when we can't go back. Nonetheless, we will still check anyway.
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
        }

        webView = WebView(this)
        setContentView(webView)

        // These allow the user to zoom the page
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        // This sets the view to display the whole page by default
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true

        // This allows the links to places on the same page to work
        webView.settings.javaScriptEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (loadingDialog == null) {
                    loadingDialog = SpinnerDialog.displayDialog(
                        this@HelpActivity,
                        resources.getString(R.string.help_loading_title),
                        resources.getString(R.string.help_loading_msg),
                        false
                    )
                }
                refreshBackDispatchState()
            }

            override fun onPageFinished(view: WebView, url: String) {
                loadingDialog?.dismiss()
                loadingDialog = null
                refreshBackDispatchState()
            }
        }

        webView.loadUrl(intent.data.toString())
    }

    private fun refreshBackDispatchState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (webView.canGoBack() && !backCallbackRegistered) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, onBackInvokedCallback!!
                )
                backCallbackRegistered = true
            } else if (!webView.canGoBack() && backCallbackRegistered) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBackInvokedCallback!!)
                backCallbackRegistered = false
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (backCallbackRegistered) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBackInvokedCallback!!)
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back goes back through the WebView history
        // until no more history remains
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
