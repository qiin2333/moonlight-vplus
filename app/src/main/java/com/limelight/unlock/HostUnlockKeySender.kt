package com.limelight.unlock

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.KeyboardPacket
import java.util.Arrays

object HostUnlockPin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 32

    fun isValid(pin: CharArray): Boolean =
        pin.size in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
}

internal object HostUnlockKeySequence {
    const val VK_RETURN: Short = 0x0d

    /** Wake key that raises the Windows lock screen curtain without typing into the PIN box. */
    val WAKE_KEY: Short = KeyboardTranslator.VK_LCONTROL.toShort()

    /** Builds wake + digit + Enter virtual-key codes; the caller must wipe the result. */
    fun build(pin: CharArray): ShortArray {
        require(HostUnlockPin.isValid(pin))
        val keys = ShortArray(pin.size + 2)
        keys[0] = WAKE_KEY
        for (i in pin.indices) {
            keys[i + 1] = (KeyboardTranslator.VK_0 + (pin[i] - '0')).toShort()
        }
        keys[keys.size - 1] = VK_RETURN
        return keys
    }
}

/** Types a PIN into the host's lock screen through the regular keyboard input channel. */
class HostUnlockKeySender(
    private val connProvider: () -> NvConnection?,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    enum class Result { COMPLETED, CONNECTION_LOST, CANCELLED }

    private val token = Any()
    private var keys: ShortArray? = null
    private var index = 0
    private var pressedKey: Short? = null
    private var shouldContinue: () -> Boolean = { true }
    private var onComplete: ((Result) -> Unit)? = null

    val isSending: Boolean get() = keys != null

    /**
     * Consumes [pin]; it is wiped before this function returns. [shouldContinue] is checked
     * before every key so the sequence stops as soon as the host leaves the lock screen.
     */
    fun send(pin: CharArray, shouldContinue: () -> Boolean, onComplete: (Result) -> Unit) {
        cancel()
        try {
            keys = HostUnlockKeySequence.build(pin)
        } finally {
            Arrays.fill(pin, '\u0000')
        }
        index = 0
        this.shouldContinue = shouldContinue
        this.onComplete = onComplete
        handler.postAtTime(::sendNextKey, token, SystemClock.uptimeMillis())
    }

    /** Stops typing, releasing any key that is still held so nothing stays pressed on the host. */
    fun cancel() {
        if (keys == null) return
        handler.removeCallbacksAndMessages(token)
        releasePressedKey()
        finish(Result.CANCELLED)
    }

    private fun sendNextKey() {
        val sequence = keys ?: return
        if (!shouldContinue()) {
            finish(Result.CANCELLED)
            return
        }
        val conn = connProvider()
        if (conn == null) {
            finish(Result.CONNECTION_LOST)
            return
        }

        val key = sequence[index]
        conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, 0, 0)
        pressedKey = key
        handler.postAtTime({
            releasePressedKey()
            index++
            when {
                index >= sequence.size -> finish(Result.COMPLETED)
                else -> handler.postAtTime(::sendNextKey, token, nextDelayAfterRelease(index, sequence.size))
            }
        }, token, SystemClock.uptimeMillis() + KEY_HOLD_MS)
    }

    private fun releasePressedKey() {
        val key = pressedKey ?: return
        pressedKey = null
        connProvider()?.sendKeyboardInput(key, KeyboardPacket.KEY_UP, 0, 0)
    }

    private fun nextDelayAfterRelease(nextIndex: Int, size: Int): Long {
        val delay = when (nextIndex) {
            1 -> WAKE_SETTLE_MS
            size - 1 -> BEFORE_ENTER_MS
            else -> KEY_GAP_MS
        }
        return SystemClock.uptimeMillis() + delay
    }

    private fun finish(result: Result) {
        keys?.let { Arrays.fill(it, 0) }
        keys = null
        index = 0
        shouldContinue = { true }
        val callback = onComplete
        onComplete = null
        callback?.invoke(result)
    }

    private companion object {
        const val KEY_HOLD_MS = 30L
        const val KEY_GAP_MS = 50L
        // Time for LogonUI to slide the curtain away and focus the PIN box.
        const val WAKE_SETTLE_MS = 1000L
        const val BEFORE_ENTER_MS = 150L
    }
}
