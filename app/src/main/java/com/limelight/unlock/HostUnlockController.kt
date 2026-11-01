package com.limelight.unlock

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.limelight.Game
import com.limelight.R
import java.util.Arrays

/**
 * Unlocks the streamed Windows lock screen by typing a biometric-protected PIN through the
 * regular keyboard input channel.
 *
 * Hosts that report their session lock state get an automatic fingerprint prompt once per lock;
 * other hosts can only be unlocked manually through the `unlock_host` quick action.
 * All methods must be called on the main thread.
 */
class HostUnlockController(
    private val game: Game,
    onOverlayVisibilityChanged: () -> Unit
) {
    private val ui = HostUnlockUi(game, onOverlayVisibilityChanged)
    private val sender = HostUnlockKeySender({ game.conn })
    private val handler = Handler(Looper.getMainLooper())

    /** Latest host report; null when the host has not reported (unsupported or not yet sent). */
    private var hostLocked: Boolean? = null
    private var streamForeground = true
    private var attemptedForCurrentLock = false
    private var awaitingUnlock = false

    private val autoPrompt = Runnable { maybeAutoUnlock() }
    private val unlockTimeout = Runnable { onUnlockNotConfirmed() }

    val isOverlayVisible: Boolean get() = ui.isOverlayVisible

    fun onHostSessionStateChanged(locked: Boolean) {
        hostLocked = locked
        handler.removeCallbacks(autoPrompt)
        if (locked) {
            handler.postDelayed(autoPrompt, AUTO_PROMPT_DELAY_MS)
            return
        }

        if (attemptedForCurrentLock) ui.cancelPrompt()
        attemptedForCurrentLock = false
        // Never let remaining digits or Enter land on the unlocked desktop.
        sender.cancel()
        if (awaitingUnlock) {
            awaitingUnlock = false
            handler.removeCallbacks(unlockTimeout)
            toast(R.string.host_unlock_host_unlocked)
        }
    }

    fun onStreamForegroundChanged(foreground: Boolean) {
        streamForeground = foreground
        handler.removeCallbacks(autoPrompt)
        if (foreground && hostLocked == true) {
            handler.postDelayed(autoPrompt, AUTO_PROMPT_DELAY_MS)
        }
    }

    /** Manual trigger used by the quick action and float ball, mainly for hosts without lock reports. */
    fun startManual() {
        val hostUuid = game.computerUuid
        if (hostUuid.isNullOrEmpty() || game.conn == null) {
            toast(R.string.host_unlock_not_connected)
            return
        }
        if (hostLocked == false) {
            toast(R.string.host_unlock_not_locked)
            return
        }
        if (isBusy() || !ui.ensureAvailable()) return

        if (HostUnlockCredentialStore.has(game, hostUuid) &&
            ui.authenticateStoredPin(
                hostUuid,
                hostName(),
                game.getString(R.string.host_unlock_prompt_description),
                ::sendPin
            )
        ) {
            return
        }
        ui.showSetup(hostUuid, hostName()) { pin ->
            if (hostLocked == true) sendPin(pin) else ui.confirmUnlockNow(pin, ::sendPin)
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        ui.release()
        sender.cancel()
    }

    private fun maybeAutoUnlock() {
        if (hostLocked != true || attemptedForCurrentLock || !streamForeground || isBusy()) return
        val hostUuid = game.computerUuid ?: return
        if (!HostUnlockCredentialStore.has(game, hostUuid) ||
            HostUnlockUi.availability(game) != HostUnlockUi.Availability.AVAILABLE
        ) {
            return
        }

        // One attempt per lock: repeated wrong PINs would trigger Windows' PIN lockout.
        attemptedForCurrentLock = true
        ui.authenticateStoredPin(
            hostUuid,
            hostName(),
            game.getString(R.string.host_unlock_prompt_auto_description),
            ::sendPin
        )
    }

    /** Consumes [pin]. */
    private fun sendPin(pin: CharArray) {
        if (hostLocked == false) {
            Arrays.fill(pin, '\u0000')
            return
        }
        sender.send(pin, shouldContinue = { hostLocked != false }) { result ->
            when {
                result == HostUnlockKeySender.Result.CANCELLED -> Unit
                result == HostUnlockKeySender.Result.CONNECTION_LOST -> toast(R.string.host_unlock_send_failed)
                hostLocked == null -> toast(R.string.host_unlock_sent)
                hostLocked == false -> toast(R.string.host_unlock_host_unlocked)
                else -> {
                    awaitingUnlock = true
                    handler.removeCallbacks(unlockTimeout)
                    handler.postDelayed(unlockTimeout, UNLOCK_CONFIRM_TIMEOUT_MS)
                }
            }
        }
    }

    private fun onUnlockNotConfirmed() {
        awaitingUnlock = false
        val hostUuid = game.computerUuid
        if (hostLocked != true || hostUuid == null || isBusy()) return
        ui.showStillLocked {
            HostUnlockCredentialStore.remove(game, hostUuid)
            ui.showSetup(hostUuid, hostName(), ::sendPin)
        }
    }

    private fun isBusy(): Boolean = ui.isOverlayVisible || sender.isSending || awaitingUnlock

    private fun hostName(): String = game.pcName.orEmpty()

    private fun toast(resId: Int) {
        Toast.makeText(game, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        // Lets the stream settle and debounces brief lock-state flips.
        const val AUTO_PROMPT_DELAY_MS = 600L
        const val UNLOCK_CONFIRM_TIMEOUT_MS = 8000L
    }
}
