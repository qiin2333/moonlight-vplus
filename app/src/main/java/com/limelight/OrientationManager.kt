@file:Suppress("DEPRECATION")
package com.limelight

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.limelight.nvstream.NvConnection
import com.limelight.preferences.PreferenceConfiguration

/**
 * 管理串流 Activity 的屏幕旋转和方向同步。
 *
 * 职责：
 * - 根据用户配置和外接显示器状态设置 Activity 方向
 * - 检测用户物理旋转并通知服务端同步
 * - 处理服务端主动推送的分辨率/方向变更
 * - 防止客户端↔服务端旋转通知死循环
 */
class OrientationManager(
    private val activity: Activity,
    private val originalWidth: Int,
    private val originalHeight: Int,
    private val rotableScreen: Boolean,
    private val hasOnscreenControls: Boolean,
    private val forceLandscapeMultiWindow: Boolean = false,
    private val inMultiWindow: () -> Boolean = {
        val isPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode
        val configuration = activity.resources.configuration.toString()
        // Flyme 10 may report its mini window as fullscreen through Activity's
        // public multi-window flag, while exposing its mode in Configuration.
        val flymeWindow = configuration.contains("mWindowingMode=flyme-mini-window") ||
            configuration.contains("mWindowingMode=flyme-pinned-window")
        !isPictureInPicture &&
            ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode) || flymeWindow)
    },
    private val requestHostRotation: (NvConnection, Int, NvConnection.DisplayRotationCallback) -> Unit =
        { conn, angle, callback -> conn.rotateDisplay(angle, callback) },
    private val displayProvider: () -> Display?,
) {
    var connection: NvConnection? = null
    var connected = false

    /** 上次已知的旋转方向：-1=未知, 0=竖屏, 1=横屏 */
    private var lastRotation = -1

    /** 是否正在处理服务端主动推送的旋转（防止回环通知） */
    var isServerInitiatedRotation = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val hostRotationResultHandler = Handler(Looper.getMainLooper())
    private var pendingRotationRunnable: Runnable? = null
    private var lastServerWidth = 0
    private var lastServerHeight = 0
    private var lastServerOrientationConfirmed = false
    private var syncAfterMultiWindow = false
    private val forcedHostRotation = ForcedLandscapeHostRotation()
    private var rotationGeneration = 0
    @Volatile private var destroyed = false

    companion object {
        private const val ROTATION_DEBOUNCE_MS = 3000L
        private const val SERVER_ROTATION_SETTLE_MS = 1000L
    }

    // ──────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────

    /** Apply a host orientation received before the connection-start callback. */
    fun onConnectionEstablished() {
        if (destroyed || connection == null) return
        connected = true
        if (isForcedLandscapeMultiWindow()) forceKnownHostLandscape()
    }

    /**
     * 根据用户原始配置和屏幕特征设置 Activity 方向。
     * 始终基于 [originalWidth]/[originalHeight]，不受服务端运行时分辨率变更影响。
     */
    fun setPreferredOrientation() {
        val display = displayProvider()

        val desiredOrientation = when {
            originalWidth > originalHeight -> Configuration.ORIENTATION_LANDSCAPE
            originalHeight > originalWidth -> Configuration.ORIENTATION_PORTRAIT
            hasOnscreenControls -> Configuration.ORIENTATION_LANDSCAPE
            else -> Configuration.ORIENTATION_UNDEFINED
        }

        activity.requestedOrientation = when {
            isForcedLandscapeMultiWindow() -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            rotableScreen -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            display != null && PreferenceConfiguration.isSquarishScreen(display) -> when (desiredOrientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
            else -> when (desiredOrientation) {
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            }
        }
    }

    /**
     * Activity 配置变更时调用。处理：
     * - 服务端旋转到位后恢复 FULL_USER
     * - 用户物理旋转后通知服务端
     */
    fun onConfigurationChanged() {
        if (isForcedLandscapeMultiWindow()) {
            cancelPendingRotation()
            setPreferredOrientation()
            forceKnownHostLandscape()
            return
        }
        if (forceLandscapeMultiWindow) forcedHostRotation.leaveWindow()
        if (syncAfterMultiWindow) {
            setPreferredOrientation()
            resumeOrientationSync()
            return
        }
        if (isServerInitiatedRotation) {
            // 服务端推送的旋转已到位，取消安全兜底定时器，立即恢复自由旋转
            handler.removeCallbacksAndMessages(null)
            isServerInitiatedRotation = false
            setPreferredOrientation()
            LimeLog.info("OrientationManager: server rotation settled, restored FULL_USER")
        } else {
            setPreferredOrientation()
            if (rotableScreen && connection != null) {
                handleRotationChange()
            }
        }
    }

    /**
     * 首次收到服务端分辨率时调用。
     * - rotableScreen=true：检查客户端物理方向是否匹配服务端，不匹配则通知服务端
     * - rotableScreen=false：若服务端方向与用户配置不匹配，重置服务端旋转
     */
    fun syncOrientationOnFirstFrame(serverWidth: Int, serverHeight: Int) {
        forcedHostRotation.observeHostOrientation()
        lastServerWidth = serverWidth
        lastServerHeight = serverHeight
        lastServerOrientationConfirmed = true
        // Window bounds do not describe the device's rotation in multi-window mode.
        if (isForcedLandscapeMultiWindow()) {
            syncAfterMultiWindow = true
            forceHostLandscapeIfNeeded(serverWidth > serverHeight)
            return
        }
        if (lastRotation != -1 || !connected) return
        val conn = connection ?: return

        if (rotableScreen) {
            checkAndSyncOrientation(serverWidth, serverHeight)
        } else {
            val configIsLandscape = originalWidth > originalHeight
            val serverIsLandscape = serverWidth > serverHeight
            lastRotation = if (configIsLandscape) 1 else 0
            if (configIsLandscape != serverIsLandscape) {
                val angle = if (configIsLandscape) 0 else 90
                LimeLog.info("OrientationManager: server orientation mismatch, resetting to ${if (configIsLandscape) "landscape" else "portrait"}")
                conn.rotateDisplay(angle, object : NvConnection.DisplayRotationCallback {
                    override fun onSuccess(angle: Int) {
                        LimeLog.info("Display rotation reset to $angle degrees")
                    }
                    override fun onFailure(errorMessage: String) {
                        LimeLog.warning("Failed to reset display rotation: $errorMessage")
                    }
                })
            }
        }
    }

    /**
     * 服务端分辨率变更时调用，处理方向锁定和恢复。
     * - rotableScreen=true：短暂锁定方向匹配服务端，1 秒后恢复 FULL_USER
     * - rotableScreen=false：重新设置用户配置的方向（不受服务端影响）
     */
    fun onServerResolutionChanged(isLandscape: Boolean) {
        // Game may report the same confirmed frame through both entry points.
        if (!lastServerOrientationConfirmed || lastServerWidth <= 0 || lastServerHeight <= 0 ||
            (lastServerWidth > lastServerHeight) != isLandscape) {
            forcedHostRotation.observeHostOrientation()
        }
        if (lastServerWidth > 0 && lastServerHeight > 0 &&
            (lastServerWidth > lastServerHeight) != isLandscape) {
            val width = lastServerWidth
            lastServerWidth = lastServerHeight
            lastServerHeight = width
        }
        lastServerOrientationConfirmed = true
        if (isForcedLandscapeMultiWindow()) {
            cancelPendingRotation()
            setPreferredOrientation()
            forceHostLandscapeIfNeeded(isLandscape)
            return
        }
        if (rotableScreen) {
            handler.removeCallbacksAndMessages(null)
            isServerInitiatedRotation = true
            activity.requestedOrientation = if (isLandscape)
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            handler.postDelayed({
                isServerInitiatedRotation = false
                setPreferredOrientation()
            }, SERVER_ROTATION_SETTLE_MS)
        } else {
            setPreferredOrientation()
        }
    }

    /**
     * 连接断开/重置时调用，清除旋转状态。
     */
    fun reset() {
        rotationGeneration++
        forcedHostRotation.reset()
        lastRotation = -1
        isServerInitiatedRotation = false
        syncAfterMultiWindow = false
        lastServerWidth = 0
        lastServerHeight = 0
        lastServerOrientationConfirmed = false
        pendingRotationRunnable?.let { handler.removeCallbacks(it) }
        pendingRotationRunnable = null
    }

    /** Reapply the user's orientation when entering or leaving a multi-window session. */
    fun onWindowModeChanged() {
        if (!forceLandscapeMultiWindow) return
        val forced = isForcedLandscapeMultiWindow()
        if (forced) cancelPendingRotation()
        setPreferredOrientation()
        if (forced) {
            forceKnownHostLandscape()
        } else {
            forcedHostRotation.leaveWindow()
            resumeOrientationSync()
        }
    }

    private fun isForcedLandscapeMultiWindow(): Boolean = forceLandscapeMultiWindow && inMultiWindow()

    private fun cancelPendingRotation() {
        if (pendingRotationRunnable != null || connected) {
            // A queued rotation may never have been sent, and the physical display
            // can also rotate while the freeform window is active.
            lastRotation = -1
            syncAfterMultiWindow = true
        }
        handler.removeCallbacksAndMessages(null)
        pendingRotationRunnable = null
        isServerInitiatedRotation = false
    }

    private fun resumeOrientationSync() {
        if (!syncAfterMultiWindow || !connected || connection == null ||
            lastServerWidth <= 0 || lastServerHeight <= 0 ||
            forcedHostRotation.inFlight ||
            inMultiWindow() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode)) return
        syncAfterMultiWindow = false
        syncOrientationOnFirstFrame(lastServerWidth, lastServerHeight)
    }

    /**
     * Activity 销毁时调用，清理所有 Handler 回调。
     */
    fun cleanup() {
        destroyed = true
        rotationGeneration++
        forcedHostRotation.reset()
        handler.removeCallbacksAndMessages(null)
        hostRotationResultHandler.removeCallbacksAndMessages(null)
        pendingRotationRunnable = null
    }

    private fun forceKnownHostLandscape() {
        if (lastServerOrientationConfirmed && lastServerWidth > 0 && lastServerHeight > 0) {
            forceHostLandscapeIfNeeded(lastServerWidth > lastServerHeight)
        }
    }

    private fun forceHostLandscapeIfNeeded(serverIsLandscape: Boolean) {
        val conn = connection
        if (!forcedHostRotation.shouldRequest(
                isForcedLandscapeMultiWindow(), connected && conn != null, serverIsLandscape
            ) || conn == null) return
        val generation = rotationGeneration
        val observedVersion = forcedHostRotation.observationVersion
        LimeLog.info("OrientationManager: requesting landscape host for multi-window")
        requestHostRotation(conn, 0, object : NvConnection.DisplayRotationCallback {
            override fun onSuccess(angle: Int) = finish(true, null)
            override fun onFailure(errorMessage: String) = finish(false, errorMessage)

            private fun finish(success: Boolean, error: String?) {
                if (destroyed) return
                hostRotationResultHandler.post {
                    if (destroyed || generation != rotationGeneration || connection !== conn) return@post
                    val noNewHostOrientation = forcedHostRotation.complete(observedVersion, success)
                    if (!connected) return@post
                    if (success) {
                        LimeLog.info("OrientationManager: host rotated for multi-window")
                        if (noNewHostOrientation) {
                            if (lastServerWidth > 0 && lastServerHeight > lastServerWidth) {
                                val width = lastServerWidth
                                lastServerWidth = lastServerHeight
                                lastServerHeight = width
                            }
                            lastServerOrientationConfirmed = false
                        }
                    } else {
                        LimeLog.warning("OrientationManager: failed to rotate host for multi-window: $error")
                    }
                    if (isForcedLandscapeMultiWindow()) {
                        forceKnownHostLandscape()
                    } else {
                        resumeOrientationSync()
                    }
                }
            }
        })
    }

    // ──────────────────────────────────────────────
    // 内部实现
    // ──────────────────────────────────────────────

    /**
     * 检查客户端物理方向与服务端方向是否一致，不一致时通知服务端。
     */
    private fun checkAndSyncOrientation(serverWidth: Int, serverHeight: Int) {
        val display = displayProvider()
        if (display == null) {
            LimeLog.warning("checkAndSyncOrientation: display is null")
            return
        }

        val size = android.graphics.Point()
        display.getRealSize(size)

        val clientIsLandscape = size.x > size.y
        val serverIsLandscape = serverWidth > serverHeight

        LimeLog.info("checkAndSyncOrientation: client=${size.x}x${size.y}" +
                " (${if (clientIsLandscape) "landscape" else "portrait"})" +
                ", server=${serverWidth}x${serverHeight}" +
                " (${if (serverIsLandscape) "landscape" else "portrait"})")

        if (clientIsLandscape != serverIsLandscape) {
            LimeLog.info("checkAndSyncOrientation: mismatch detected, notifying server")
            if (forceLandscapeMultiWindow) {
                handleRotationChange(clientIsLandscape)
            } else {
                handleRotationChange()
            }
        } else {
            LimeLog.info("checkAndSyncOrientation: orientation matches")
            if (lastRotation == -1) {
                lastRotation = if (clientIsLandscape) 1 else 0
            }
        }
    }

    /**
     * 检测物理旋转变化，通知服务端同步分辨率。
     * 带 3 秒防抖，避免频繁通知。
     */
    private fun handleRotationChange(
        isLandscape: Boolean = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    ) {
        val currentOrientation = if (isLandscape) 1 else 0

        LimeLog.info("handleRotationChange: isLandscape=$isLandscape, lastRotation=$lastRotation")

        val conn = connection
        if (conn == null || !connected) {
            LimeLog.warning("handleRotationChange: connection not ready")
            return
        }

        when {
            lastRotation == -1 -> {
                lastRotation = currentOrientation
                LimeLog.info("handleRotationChange: first call, orientation=$currentOrientation")
            }
            currentOrientation == lastRotation -> return
            else -> lastRotation = currentOrientation
        }

        val angle = if (isLandscape) 0 else 90

        pendingRotationRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            LimeLog.info("handleRotationChange: notifying server, angle=$angle")
            conn.rotateDisplay(angle, object : NvConnection.DisplayRotationCallback {
                override fun onSuccess(angle: Int) {
                    LimeLog.info("Display rotated to $angle degrees")
                }
                override fun onFailure(errorMessage: String) {
                    LimeLog.warning("Failed to rotate display: $errorMessage")
                }
            })
            pendingRotationRunnable = null
        }
        pendingRotationRunnable = runnable
        handler.postDelayed(runnable, ROTATION_DEBOUNCE_MS)
    }
}

/** Suppress duplicate requests until a new portrait state or a new window session. */
internal class ForcedLandscapeHostRotation {
    var inFlight = false
        private set
    var observationVersion = 0L
        private set
    private var active = false
    private var wasPortrait = false
    private var attempted = false
    private var newerPortraitRetryAvailable = true

    fun shouldRequest(forced: Boolean, connected: Boolean, serverIsLandscape: Boolean): Boolean {
        if (!forced) {
            leaveWindow()
            return false
        }
        if (!active) {
            active = true
            attempted = false
            wasPortrait = false
            newerPortraitRetryAvailable = true
        }
        if (serverIsLandscape) {
            wasPortrait = false
            attempted = false
            newerPortraitRetryAvailable = true
            return false
        }
        if (!wasPortrait) {
            wasPortrait = true
            attempted = false
        }
        if (!connected || inFlight || attempted) return false
        attempted = true
        inFlight = true
        return true
    }

    fun observeHostOrientation() {
        observationVersion++
    }

    fun complete(requestObservationVersion: Long, success: Boolean): Boolean {
        inFlight = false
        val noNewHostOrientation = requestObservationVersion == observationVersion
        if (success && !noNewHostOrientation && active && wasPortrait && newerPortraitRetryAvailable) {
            // The successful request may have been superseded by a newer portrait
            // notification. Retry once, but do not loop on stale notifications.
            attempted = false
            newerPortraitRetryAvailable = false
        }
        return noNewHostOrientation
    }

    fun leaveWindow() {
        active = false
    }

    fun reset() {
        inFlight = false
        active = false
        wasPortrait = false
        attempted = false
        newerPortraitRetryAvailable = true
        observationVersion = 0L
    }
}
