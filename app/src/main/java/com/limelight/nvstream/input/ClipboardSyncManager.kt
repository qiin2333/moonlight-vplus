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
import com.limelight.nvstream.http.ClipboardBlobUploadResult
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.jni.MoonBridge
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
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
 * Wire payload sizing:
 *  - Up to [MAX_PAYLOAD_BYTES]: sent inline as KIND_TEXT/KIND_PNG.
 *  - Larger (up to [BLOB_MAX_BYTES]): when an [NvHTTP] is supplied, uploaded
 *    via the Sunshine /api/v1/clipboard/blob HTTPS endpoint and exchanged as a
 *    KIND_REF frame referencing the resulting id.
 *  - Above [BLOB_MAX_BYTES]: dropped — refuses to hand multi-hundred-MiB
 *    buffers to the PNG decoder or to a host that may have lied about size.
 *
 * The blob HTTPS calls block, so they run on a dedicated single-thread executor
 * to keep the system clipboard listener and the native receive callback
 * non-blocking.
 */
class ClipboardSyncManager(
    private val context: Context,
    private val syncText: Boolean,
    private val syncImage: Boolean,
    private val fileProviderAuthority: String,
    private val nvHttpProvider: (() -> NvHTTP?)? = null,
) : MoonBridge.ClipboardListener {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recentSentTokens = ArrayDeque<TokenEntry>()

    @Volatile private var pendingSelfWrites = 0

    // Fingerprint of the last payload we actually dispatched to the host.
    // Used to suppress duplicates when the focus-regain poll sees the same
    // clip we already sent (the OnPrimaryClipChangedListener would normally
    // not refire, but the polling path will re-read it on every focus gain).
    @Volatile private var lastDispatchedFingerprint: Long = 0L

    private var blobExecutor: ExecutorService? = null

    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            handleLocalClipChanged()
        } catch (t: Throwable) {
            LimeLog.warning("Clipboard listener failed: ${t.message}")
        }
    }

    fun start() {
        if (!syncText && !syncImage) return
        if (blobExecutor == null && nvHttpProvider != null) {
            blobExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "ClipboardBlobIO").apply { isDaemon = true }
            }
        }
        MoonBridge.setClipboardListener(this)
        clipboard.addPrimaryClipChangedListener(primaryClipListener)
    }

    fun stop() {
        clipboard.removePrimaryClipChangedListener(primaryClipListener)
        MoonBridge.setClipboardListener(null)
        synchronized(recentSentTokens) { recentSentTokens.clear() }
        blobExecutor?.shutdownNow()
        blobExecutor = null
        lastDispatchedFingerprint = 0L
    }

    /**
     * Re-poll the system clipboard. Android only fires
     * [ClipboardManager.OnPrimaryClipChangedListener] while the app holds input
     * focus, so any clip change that happened in another app while Game was
     * paused is silently dropped. Call this when focus is regained so the most
     * recent clip is forwarded to the host. The fingerprint check prevents
     * duplicate sends when the user did not actually copy anything new.
     */
    fun onFocusGained() {
        if (!syncText && !syncImage) return
        try {
            handleLocalClipChanged()
        } catch (t: Throwable) {
            LimeLog.warning("Clipboard focus poll failed: ${t.message}")
        }
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
                sendPayloadOrBlob(
                    MoonBridge.LI_CLIPBOARD_KIND_TEXT,
                    MIME_TEXT,
                    text.toByteArray(Charsets.UTF_8),
                    "文本",
                )
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
                        sendPayloadOrBlob(
                            MoonBridge.LI_CLIPBOARD_KIND_PNG,
                            MIME_PNG,
                            out.toByteArray(),
                            "图片",
                        )
                    }
                    ok
                }
            }
        }
    } catch (t: Throwable) {
        LimeLog.warning("Clipboard image encode failed: ${t.message}")
        false
    }

    /**
     * Decide between inline send and blob upload based on payload size.
     * Inline path runs synchronously on the caller; blob path is dispatched to
     * the IO executor so the system clipboard listener returns promptly.
     */
    private fun sendPayloadOrBlob(kind: Byte, mime: String, bytes: ByteArray, displayName: String) {
        if (bytes.isEmpty()) return
        // Suppress duplicates: focus-regain polling will re-read the same
        // clip on every focus event. We only care about new content.
        val fp = fingerprint(kind, bytes)
        if (fp == lastDispatchedFingerprint) return
        lastDispatchedFingerprint = fp
        if (bytes.size <= MAX_PAYLOAD_BYTES) {
            sendInlinePayload(kind, bytes)
            return
        }
        if (bytes.size > BLOB_MAX_BYTES) {
            LimeLog.info("Clipboard $displayName payload size=${bytes.size} exceeds ${BLOB_MAX_BYTES} cap, dropping")
            return
        }
        val executor = blobExecutor
        val provider = nvHttpProvider
        if (executor == null || provider == null) {
            LimeLog.info("Clipboard $displayName payload size=${bytes.size} exceeds inline cap and blob transport unavailable, dropping")
            return
        }
        executor.execute {
            try {
                val nvHttp = provider() ?: run {
                    LimeLog.warning("Clipboard $displayName: NvHTTP unavailable, dropping blob upload")
                    return@execute
                }
                val result: ClipboardBlobUploadResult = withBlobRetry("upload $displayName") {
                    nvHttp.uploadClipboardBlob(mime, bytes)
                }
                LimeLog.info("Clipboard $displayName uploaded blob id=${result.id} size=${result.size}")
                val refPayload = buildRefPayload(result.id, result.mime, result.size)
                if (refPayload.size > MAX_PAYLOAD_BYTES) {
                    LimeLog.warning("Clipboard REF payload size=${refPayload.size} exceeds inline cap, dropping")
                    return@execute
                }
                sendInlinePayload(MoonBridge.LI_CLIPBOARD_KIND_REF, refPayload)
            } catch (t: Throwable) {
                LimeLog.warning("Clipboard $displayName blob upload failed: ${t.message}")
            }
        }
    }

    private fun sendInlinePayload(kind: Byte, bytes: ByteArray) {
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

    private fun buildRefPayload(id: String, mime: String, size: Long): ByteArray {
        val obj = JSONObject()
        obj.put("type", "ref")
        obj.put("id", id)
        obj.put("mime", mime)
        obj.put("size", size)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Run [block] up to [BLOB_RETRY_ATTEMPTS] times. Retries only on
     * [java.io.IOException] (network/transport hiccups); HTTP 4xx/5xx come
     * back as [com.limelight.nvstream.http.HostHttpResponseException] and are
     * treated as terminal. Mirrors the harmonyos client's retry policy.
     */
    private fun <T> withBlobRetry(label: String, block: () -> T): T {
        var lastError: java.io.IOException? = null
        for (attempt in 1..BLOB_RETRY_ATTEMPTS) {
            try {
                return block()
            } catch (e: com.limelight.nvstream.http.HostHttpResponseException) {
                throw e
            } catch (e: java.io.IOException) {
                lastError = e
                // executor.shutdownNow() during stop() interrupts this thread;
                // bail out instead of burning the remaining retry budget on a
                // doomed connection.
                if (Thread.currentThread().isInterrupted) throw e
                if (attempt == BLOB_RETRY_ATTEMPTS) break
                LimeLog.warning("Clipboard blob $label attempt $attempt/$BLOB_RETRY_ATTEMPTS failed: ${e.message}, retrying")
                try {
                    Thread.sleep(BLOB_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastError ?: java.io.IOException("Clipboard blob $label failed without an exception")
    }

    // ---------------------------------------------------------------------
    // Inbound: host → Android clipboard
    // ---------------------------------------------------------------------

    override fun onClipboardData(kind: Byte, token: Int, data: ByteArray) {
        if (isHostEcho(token)) return
        when (kind) {
            MoonBridge.LI_CLIPBOARD_KIND_TEXT -> if (syncText) applyInboundText(data)
            MoonBridge.LI_CLIPBOARD_KIND_PNG -> if (syncImage) applyInboundPng(data)
            MoonBridge.LI_CLIPBOARD_KIND_REF -> handleInboundRef(data)
            else -> LimeLog.info("Ignoring unknown clipboard kind=$kind")
        }
    }

    private fun applyInboundText(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        postToClipboard { ClipData.newPlainText("Sunshine", text) }
    }

    private fun applyInboundPng(data: ByteArray) {
        val uri = persistInboundPng(data) ?: return
        postToClipboard { ClipData.newUri(context.contentResolver, "Sunshine image", uri) }
    }

    private fun handleInboundRef(payload: ByteArray) {
        val ref = parseRefPayload(payload) ?: run {
            LimeLog.warning("Ignoring malformed clipboard REF payload")
            return
        }
        val isText = isTextMime(ref.mime)
        val isPng = ref.mime.equals(MIME_PNG, ignoreCase = true)
        if (isText && !syncText) return
        if (isPng && !syncImage) return
        if (!isText && !isPng) {
            LimeLog.info("Ignoring unsupported clipboard blob mime=${ref.mime}")
            return
        }
        if (ref.size > BLOB_MAX_BYTES) {
            LimeLog.warning("Clipboard blob too large id=${ref.id} advertised=${ref.size} max=$BLOB_MAX_BYTES")
            return
        }
        val executor = blobExecutor
        val provider = nvHttpProvider
        if (executor == null || provider == null) {
            LimeLog.warning("Clipboard blob ref id=${ref.id} received but blob transport unavailable")
            return
        }
        executor.execute {
            try {
                val nvHttp = provider() ?: run {
                    LimeLog.warning("Clipboard blob ref id=${ref.id}: NvHTTP unavailable")
                    return@execute
                }
                val data = withBlobRetry("download id=${ref.id}") {
                    nvHttp.downloadClipboardBlob(ref.id)
                }
                // Independent post-download cap: even when ref.size==0 (unknown),
                // refuse to hand a multi-hundred-MiB buffer to the decoder.
                if (data.size > BLOB_MAX_BYTES) {
                    LimeLog.warning("Clipboard blob payload exceeds cap id=${ref.id} actual=${data.size} max=$BLOB_MAX_BYTES")
                    return@execute
                }
                // Hard fail on size mismatch — an honest server must report accurate size.
                if (ref.size > 0 && data.size.toLong() != ref.size) {
                    LimeLog.warning("Clipboard blob size mismatch id=${ref.id} expected=${ref.size} actual=${data.size}")
                    return@execute
                }
                if (isText) applyInboundText(data) else applyInboundPng(data)
            } catch (t: Throwable) {
                LimeLog.warning("Clipboard blob download id=${ref.id} failed: ${t.message}")
            }
        }
    }

    private fun parseRefPayload(payload: ByteArray): InboundRef? = try {
        val obj = JSONObject(String(payload, Charsets.UTF_8))
        val type = obj.optString("type", "ref").trim()
        val id = obj.optString("id").trim()
        val mime = obj.optString("mime").trim()
        val size = obj.optLong("size", -1L)
        if (id.isEmpty() || mime.isEmpty() || size < 0L || (type.isNotEmpty() && type != "ref")) {
            null
        } else {
            InboundRef(id, mime, size)
        }
    } catch (_: JSONException) {
        null
    } catch (_: Throwable) {
        null
    }

    private data class InboundRef(val id: String, val mime: String, val size: Long)

    /**
     * Mirror of the harmonyos client's isTextMime: accept any `text/...` mime
     * with no charset or with utf-8 / utf8 / us-ascii. Reject e.g.
     * `text/csv;charset=gbk` so we don't UTF-8-decode mojibake into the local
     * clipboard. Sunshine itself only emits `text/plain;charset=utf-8` today,
     * but other senders may not.
     */
    private fun isTextMime(mime: String): Boolean {
        val lower = mime.lowercase()
        if (!lower.startsWith("text/")) return false
        val charsetMatch = TEXT_CHARSET_REGEX.find(lower) ?: return true
        val charset = charsetMatch.groupValues[1].trim().trim('"')
        return charset == "utf-8" || charset == "utf8" || charset == "us-ascii"
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

    /**
     * 64-bit FNV-1a fingerprint of (kind, payload bytes). Used purely for
     * dedup; not a cryptographic hash. Returns 0L iff bytes is empty so the
     * "never sent yet" sentinel cannot collide with real content.
     */
    private fun fingerprint(kind: Byte, bytes: ByteArray): Long {
        if (bytes.isEmpty()) return 0L
        var h = -3750763034362895579L // FNV offset basis (64-bit)
        h = (h xor (kind.toLong() and 0xFFL)) * 1099511628211L
        for (b in bytes) {
            h = (h xor (b.toLong() and 0xFFL)) * 1099511628211L
        }
        // Avoid the sentinel value collision.
        return if (h == 0L) 1L else h
    }

    companion object {
        // moonlight-common-c PR #5 caps the wire payload at ~64 KiB; no chunking.
        private const val MAX_PAYLOAD_BYTES = 65500 - MoonBridge.CLIPBOARD_WIRE_HEADER
        private const val MAX_IMAGE_PIXELS = 32L * 1024L * 1024L
        private const val ECHO_TTL_MS = 5_000L
        private const val MAX_TOKEN_HISTORY = 16
        // Hard upper bound for any single clipboard blob (upload or download).
        // Comfortably covers a full-screen 4K PNG screenshot while preventing
        // a malicious or buggy host from exhausting client memory.
        private const val BLOB_MAX_BYTES = 64 * 1024 * 1024
        private const val BLOB_RETRY_ATTEMPTS = 3
        private const val BLOB_RETRY_DELAY_MS = 500L
        private const val MIME_TEXT = "text/plain;charset=utf-8"
        private const val MIME_PNG = "image/png"
        private val TEXT_CHARSET_REGEX = Regex(""";\s*charset=([^;\s]+)""")
    }
}
