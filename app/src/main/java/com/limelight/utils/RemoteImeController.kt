package com.limelight.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.LimeLog
import com.limelight.nvstream.RemoteTextContext
import com.limelight.nvstream.RemoteTextContextPolicy
import com.limelight.nvstream.ImeAvoidanceSession
import com.limelight.ui.StreamView

/**
 * Avoids the open IME over the host's focused field. On API 33+ a trusted host
 * activation may also open the keyboard; the view's auto-handwriting shield keeps
 * stylus strokes on the video remote, so the temporary input connection stays safe.
 */
class RemoteImeController(
    private val activity: Activity,
    private val streamView: StreamView,
    private val panZoomHandler: PanZoomHandler,
    // Defaults to false so direct constructions keep the advisory-only contract.
    private val autoShowEnabled: Boolean = false,
) : ViewTreeObserver.OnGlobalLayoutListener {
    private var latestContext: RemoteTextContext? = null
    private var latestRevision = -1L
    private var latestInsets: WindowInsetsCompat? = null
    private var imeWasVisible = false
    @Volatile private var disposed = false
    private var generation = 0L
    private val avoidanceSession = ImeAvoidanceSession()
    private val canAutoShow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    // Records the activation that already received its automatic show chance, so
    // later revisions of the same activation never re-open against a dismissal.
    private var autoShownActivationId = 0L
    // True while the visible IME is owned by an automatic show; cleared when the
    // IME is dismissed so a manually reopened keyboard is never auto-hidden.
    private var autoOwned = false

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
            LimeLog.info(
                "RemoteIme: context rev=${context.revision} act=${context.activationId} " +
                    "source=${context.source} cause=${context.cause} flags=0x" +
                    Integer.toHexString(context.flags)
            )
            val revision = context.revision.toLong() and 0xffff_ffffL
            if (latestRevision >= 0 && !RemoteTextContextPolicy.isNewerRevision(revision, latestRevision)) return@runOnUiThread
            val activeActivationId = latestContext?.activationId ?: 0L
            if (RemoteTextContextPolicy.isTrustedDeactivation(context, activeActivationId)) {
                latestRevision = revision
                generation++
                latestContext = null
                avoidanceSession.invalidateTarget()
                if (!avoidanceSession.userControlled) panZoomHandler.setImeOffsetY(0f)
                if (autoOwned) {
                    autoOwned = false
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(streamView.windowToken, 0)
                }
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
            // Geometry stays advisory: never restart an in-flight manual IME
            // composition. Auto-show binds to a fresh trusted activation.
            if (canAutoShow && autoShowEnabled) maybeShow(context, acceptedGeneration)
            ViewCompat.requestApplyInsets(streamView)
            streamView.post {
                if (!disposed && generation == acceptedGeneration) {
                    updateAvoidance(ViewCompat.getRootWindowInsets(streamView))
                }
            }
        }
    }

    private fun maybeShow(context: RemoteTextContext, acceptedGeneration: Long) {
        if (autoShownActivationId == context.activationId) return
        if (ViewCompat.getRootWindowInsets(streamView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        ) {
            // The open keyboard already covers this activation. While it is ours,
            // rebind it so the new activation's deactivation hides it; a manual
            // keyboard stays under the user's control.
            if (autoOwned) autoShownActivationId = context.activationId
            return
        }
        // Android needs both window and view focus for showSoftInput; without
        // window focus the request is doomed, so retry on a later revision.
        if (!streamView.hasWindowFocus()) return
        autoShownActivationId = context.activationId
        autoOwned = true
        streamView.setTextInputEnabled(true)
        streamView.isFocusableInTouchMode = true
        streamView.requestFocus()
        streamView.post {
            if (disposed || generation != acceptedGeneration) return@post
            val shown = (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(streamView, InputMethodManager.SHOW_IMPLICIT) == true
            if (!shown) {
                // Rejected outright; let a later revision of this activation retry.
                autoOwned = false
                autoShownActivationId = 0L
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
        val inputMethodVisible = if (insets != null) imeVisible else visibleBottom != null
        if (inputMethodVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            imeWasVisible = false
            // Dismissal ends automatic ownership; the per-activation record stays
            // so the same activation is not auto-shown against the user again.
            autoOwned = false
            streamView.setTextInputEnabled(false)
        }
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
        imeWasVisible = false
        autoOwned = false
        autoShownActivationId = 0L
        streamView.setTextInputEnabled(false)
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
            imeWasVisible = false
            if (autoOwned) {
                autoOwned = false
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(streamView.windowToken, 0)
            }
            autoShownActivationId = 0L
            streamView.setTextInputEnabled(false)
            avoidanceSession.reset()
            panZoomHandler.setImeOffsetY(0f)
        }
    }

}
