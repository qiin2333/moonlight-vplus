package com.limelight.nvstream.input

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.limelight.LimeLog
import com.limelight.nvstream.jni.MoonBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import kotlin.random.Random

/**
 * Bridges the Android system clipboard with Sunshine's clipboard sync protocol
 * (moonlight-common-c PR #5, control packet 0x5508).
 *
 * Echo suppression has two layers:
 *   - Host echo:  every outbound payload carries a fresh 32-bit token; we drop
 *     inbound payloads whose token we recently emitted ([ECHO_TTL_MS] window).
 *   - Self echo:  writing into the local clipboard fires our own listener; a
 *     one-shot counter ([pendingSelfWrites]) absorbs that callback.
 *
 * Image policy: PNG-only. Outbound bitmaps are re-encoded losslessly. Inbound
 * PNGs are persisted to cache and exposed via a dedicated FileProvider URI so
 * other apps can paste them.
 *
 * Wire payload is currently capped at [MAX_PAYLOAD_BYTES] (one ENet control
 * packet, no chunking) — anything larger is dropped with a log entry.
 */
class ClipboardSyncManager(
    private val context: Context,
    private val syncText: Boolean,
    private val syncImage: Boolean,
    private val fileProviderAuthority: String,
) : MoonBridge.ClipboardListener {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recentSentTokens = ArrayDeque<TokenEntry>()

    @Volatile private var pendingSelfWrites = 0

    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            handleLocalClipChanged()
        } catch (t: Throwable) {
            LimeLog.warning("Clipboard listener failed: ${t.message}")
        }
    }

    fun start() {
        if (!syncText && !syncImage) return
        MoonBridge.setClipboardListener(this)
        clipboard.addPrimaryClipChangedListener(primaryClipListener)
    }

    fun stop() {
        clipboard.removePrimaryClipChangedListener(primaryClipListener)
        MoonBridge.setClipboardListener(null)
        synchronized(recentSentTokens) { recentSentTokens.clear() }
    }

    // ---------------------------------------------------------------------
    // Outbound: Android clipboard → host
    // ---------------------------------------------------------------------

    private fun handleLocalClipChanged() {
        if (pendingSelfWrites > 0) {
            pendingSelfWrites--
            return
        }
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val desc = clip.description ?: return

        // Image takes precedence — Android may attach a text label alongside the URI.
        if (syncImage && desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
            item.uri?.let { if (trySendImage(it)) return }
        }
        if (syncText && desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            val text = item.coerceToText(context)?.toString().orEmpty()
            if (text.isNotEmpty()) {
                sendPayload(MoonBridge.LI_CLIPBOARD_KIND_TEXT, text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun trySendImage(uri: Uri): Boolean = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        when {
            opts.outWidth <= 0 || opts.outHeight <= 0 -> false
            opts.outWidth.toLong() * opts.outHeight > MAX_IMAGE_PIXELS -> {
                LimeLog.info("Clipboard image too large (${opts.outWidth}x${opts.outHeight}), dropping")
                false
            }
            else -> {
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                if (bmp == null) {
                    false
                } else {
                    val out = ByteArrayOutputStream(64 * 1024)
                    val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bmp.recycle()
                    if (ok) {
                        sendPayload(MoonBridge.LI_CLIPBOARD_KIND_PNG, out.toByteArray())
                    }
                    ok
                }
            }
        }
    } catch (t: Throwable) {
        LimeLog.warning("Clipboard image encode failed: ${t.message}")
        false
    }

    private fun sendPayload(kind: Byte, bytes: ByteArray) {
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            LimeLog.info("Clipboard payload kind=$kind size=${bytes.size} exceeds $MAX_PAYLOAD_BYTES, dropping")
            return
        }
        val token = Random.nextInt()
        synchronized(recentSentTokens) {
            val now = System.currentTimeMillis()
            while (true) {
                val head = recentSentTokens.peekFirst() ?: break
                if (now - head.timestamp <= ECHO_TTL_MS) break
                recentSentTokens.pollFirst()
            }
            if (recentSentTokens.size >= MAX_TOKEN_HISTORY) recentSentTokens.pollFirst()
            recentSentTokens.add(TokenEntry(token, now))
        }
        val rc = MoonBridge.sendClipboardData(kind, token, bytes)
        if (rc != 0) {
            LimeLog.warning("sendClipboardData kind=$kind size=${bytes.size} rc=$rc")
        }
    }

    // ---------------------------------------------------------------------
    // Inbound: host → Android clipboard
    // ---------------------------------------------------------------------

    override fun onClipboardData(kind: Byte, token: Int, data: ByteArray) {
        if (isHostEcho(token)) return
        when (kind) {
            MoonBridge.LI_CLIPBOARD_KIND_TEXT -> if (syncText) {
                val text = String(data, Charsets.UTF_8)
                postToClipboard { ClipData.newPlainText("Sunshine", text) }
            }
            MoonBridge.LI_CLIPBOARD_KIND_PNG -> if (syncImage) {
                val uri = persistInboundPng(data) ?: return
                postToClipboard { ClipData.newUri(context.contentResolver, "Sunshine image", uri) }
            }
            else -> LimeLog.info("Ignoring unknown clipboard kind=$kind")
        }
    }

    private fun isHostEcho(token: Int): Boolean = synchronized(recentSentTokens) {
        val now = System.currentTimeMillis()
        val it = recentSentTokens.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.timestamp > ECHO_TTL_MS) { it.remove(); continue }
            if (e.token == token) { it.remove(); return true }
        }
        false
    }

    private inline fun postToClipboard(crossinline build: () -> ClipData) {
        mainHandler.post {
            pendingSelfWrites++
            runCatching { clipboard.setPrimaryClip(build()) }
                .onFailure {
                    pendingSelfWrites = (pendingSelfWrites - 1).coerceAtLeast(0)
                    LimeLog.warning("setPrimaryClip failed: ${it.message}")
                }
        }
    }

    private fun persistInboundPng(data: ByteArray): Uri? = try {
        val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        // Single rolling file — Android clipboard only ever holds one item.
        val target = File(dir, "inbound.png")
        FileOutputStream(target).use { it.write(data) }
        FileProvider.getUriForFile(context, fileProviderAuthority, target)
    } catch (t: Throwable) {
        LimeLog.warning("Persist inbound PNG failed: ${t.message}")
        null
    }

    private data class TokenEntry(val token: Int, val timestamp: Long)

    companion object {
        // moonlight-common-c PR #5 caps the wire payload at ~64 KiB; no chunking.
        private const val MAX_PAYLOAD_BYTES = 65500 - MoonBridge.CLIPBOARD_WIRE_HEADER
        private const val MAX_IMAGE_PIXELS = 32L * 1024L * 1024L
        private const val ECHO_TTL_MS = 5_000L
        private const val MAX_TOKEN_HISTORY = 16
    }
}
