package com.limelight.utils

import android.app.Activity
import android.os.SystemClock
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.nvstream.RemoteTextContext
import com.limelight.nvstream.RemoteTextContextPolicy
import com.limelight.nvstream.ImeAvoidanceSession
import com.limelight.ui.StreamView

/** Avoids a manually opened IME. Host observations never request keyboard display. */
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
    private val avoidanceSession = ImeAvoidanceSession()

    init {
        // Freeze the temporary offset on a real pan/scale, without changing the
        // rendered position. Dismissal still removes only this temporary offset.
        panZoomHandler.onUserTransform = { avoidanceSession.takeControl() }
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
                avoidanceSession.invalidateTarget()
                if (!avoidanceSession.userControlled) panZoomHandler.setImeOffsetY(0f)
                return@runOnUiThread
            }
            if (!RemoteTextContextPolicy.isTrustedActivation(context)) return@runOnUiThread
            latestRevision = revision
            generation++
            val acceptedGeneration = generation
            latestContext = context
            avoidanceSession.offer(context, SystemClock.elapsedRealtime())
            streamView.setRemoteTextInputOptions(
                context.hasFlag(RemoteTextContext.FLAG_PASSWORD),
                context.hasFlag(RemoteTextContext.FLAG_MULTILINE),
            )
            // UIA and InputPane are advisory geometry, not proof of editing intent.
            // Do not steal focus or restart an ongoing manual IME composition.
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
        } else if (insets == null) {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            RemoteTextContextPolicy.legacyVisibleBottom(rootTop, root.height, visible.bottom)
        } else null
        avoidanceSession.updateVisibility(
            if (insets != null) imeVisible else visibleBottom != null,
            SystemClock.elapsedRealtime(),
        )
        if (visibleBottom == null) {
            panZoomHandler.setImeOffsetY(0f)
            return
        }

        if (avoidanceSession.userControlled) return
        val context = avoidanceSession.target
        if (context == null) {
            panZoomHandler.setImeOffsetY(0f)
            return
        }
        val focusY = RemoteTextContextPolicy.focusY(context)
        if (focusY == null) {
            panZoomHandler.setImeOffsetY(0f)
            return
        }
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
        avoidanceSession.reset()
        panZoomHandler.onUserTransform = null
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
            avoidanceSession.reset()
            panZoomHandler.setImeOffsetY(0f)
        }
    }

}
