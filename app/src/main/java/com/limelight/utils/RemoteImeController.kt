package com.limelight.utils

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.limelight.nvstream.RemoteTextContext
import com.limelight.nvstream.RemoteTextContextPolicy
import com.limelight.ui.StreamView

/** Displays the local IME and keeps the host's focused field above it. */
class RemoteImeController(
    private val activity: Activity,
    private val streamView: StreamView,
    private val panZoomHandler: PanZoomHandler,
) : ViewTreeObserver.OnGlobalLayoutListener {
    private var latestContext: RemoteTextContext? = null
    private var latestRevision = -1L
    private var latestInsets: WindowInsetsCompat? = null
    @Volatile private var disposed = false
    private var generation = 0L

    init {
        streamView.rootView.viewTreeObserver.addOnGlobalLayoutListener(this)
        ViewCompat.setOnApplyWindowInsetsListener(streamView) { _, insets ->
            latestInsets = insets
            updateAvoidance(insets)
            insets
        }
    }

    fun handle(context: RemoteTextContext) {
        if (disposed) return
        activity.runOnUiThread {
            if (disposed) return@runOnUiThread
            val revision = context.revision.toLong() and 0xffff_ffffL
            if (latestRevision >= 0 && !RemoteTextContextPolicy.isNewerRevision(revision, latestRevision)) return@runOnUiThread
            val activeActivationId = latestContext?.activationId ?: 0L
            if (RemoteTextContextPolicy.isTrustedDeactivation(context, activeActivationId)) {
                latestRevision = revision
                generation++
                latestContext = null
                panZoomHandler.setImeOffsetY(0f)
                return@runOnUiThread
            }
            if (!RemoteTextContextPolicy.isTrustedActivation(context)) return@runOnUiThread
            latestRevision = revision
            generation++
            val acceptedGeneration = generation
            latestContext = context
            streamView.setRemoteTextInputOptions(
                context.hasFlag(RemoteTextContext.FLAG_PASSWORD),
                context.hasFlag(RemoteTextContext.FLAG_MULTILINE),
            )
            streamView.isFocusableInTouchMode = true
            streamView.requestFocus()
            activity.getSystemService<InputMethodManager>()?.restartInput(streamView)
            WindowInsetsControllerCompat(activity.window, streamView)
                .show(WindowInsetsCompat.Type.ime())
            activity.getSystemService<InputMethodManager>()
                ?.showSoftInput(streamView, InputMethodManager.SHOW_IMPLICIT)
            ViewCompat.requestApplyInsets(streamView)
            streamView.post {
                if (!disposed && generation == acceptedGeneration) {
                    updateAvoidance(ViewCompat.getRootWindowInsets(streamView))
                }
            }
        }
    }

    override fun onGlobalLayout() {
        if (disposed) return
        updateAvoidance(ViewCompat.getRootWindowInsets(streamView) ?: latestInsets)
    }

    private fun updateAvoidance(insets: WindowInsetsCompat?) {
        if (disposed) return
        val context = latestContext
        if (context == null) {
            panZoomHandler.setImeOffsetY(0f)
            return
        }

        val root = streamView.rootView
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val rootTop = rootLocation[1].toFloat()
        val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val imeBottomInset = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val visibleBottom = if (imeVisible) {
            // Floating IMEs commonly report visible with a zero bottom inset. In
            // that case there is no trustworthy occlusion rectangle to avoid.
            RemoteTextContextPolicy.imeVisibleBottom(rootTop, root.height, imeBottomInset)
        } else {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            RemoteTextContextPolicy.legacyVisibleBottom(rootTop, root.height, visible.bottom)
        }
        if (visibleBottom == null) {
            panZoomHandler.setImeOffsetY(0f)
            return
        }

        val focusY = RemoteTextContextPolicy.focusY(context)
        val focusInParent = panZoomHandler.captureYToParent(focusY, context.captureHeight)
        val streamParentTop = (streamView.parent as? View)?.let { parent ->
            val location = IntArray(2)
            parent.getLocationOnScreen(location)
            location[1]
        } ?: 0
        val margin = 24 * streamView.resources.displayMetrics.density
        panZoomHandler.setImeOffsetY(
            RemoteTextContextPolicy.viewportOffset(
                streamParentTop + focusInParent,
                visibleBottom,
                margin,
            ),
        )
    }

    fun dispose() {
        disposed = true
        generation++
        latestContext = null
        latestInsets = null
        val observer = streamView.rootView.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
        ViewCompat.setOnApplyWindowInsetsListener(streamView, null)
        panZoomHandler.setImeOffsetY(0f)
    }

    fun resetSession() {
        if (disposed) return
        activity.runOnUiThread {
            if (disposed) return@runOnUiThread
            generation++
            latestContext = null
            latestRevision = -1L
            latestInsets = null
            panZoomHandler.setImeOffsetY(0f)
        }
    }

}
