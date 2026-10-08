package com.limelight.utils.background

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal object PipwUrlPolicy {
    const val API_HOST = "img-api.pipw.top"

    fun candidate(url: HttpUrl, pool: PipwPool): HttpUrl {
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
            throw IOException("Invalid Pipw redirect")
        }
        if (url.encodedPathSegments.any { Regex("%(2f|5c|25|2e)", RegexOption.IGNORE_CASE).containsMatchIn(it) }) {
            throw IOException("Invalid Pipw path")
        }
        val filename = filename(url)
        val stem = filename.substringBeforeLast('.', "")
        val suffix = filename.substringAfterLast('.', "").lowercase()
        if (stem.isEmpty() || stem == "." || stem == ".." ||
            filename.any { it == '/' || it == '\\' || it == '\u0000' } || suffix !in PipwReviewIndex.extensions) {
            throw IOException("Invalid Pipw filename")
        }
        if (PipwPool.entries.any { it != pool && stem.startsWith("image-${it.jsonKey}-") }) {
            throw IOException("Unexpected Pipw collection")
        }
        return url
    }

    fun filename(url: HttpUrl): String = url.pathSegments.lastOrNull().orEmpty()

    fun redirect(base: HttpUrl, location: String?, pool: PipwPool): HttpUrl =
        candidate(base.resolve(location ?: throw IOException("Missing Pipw redirect"))
            ?: throw IOException("Invalid Pipw redirect"), pool)

    fun fallback(template: HttpUrl, filename: String, pool: PipwPool): HttpUrl = candidate(
        template.newBuilder().setPathSegment(template.pathSegments.lastIndex, filename)
            .query(null).fragment(null).build(), pool
    )
}

/** Explicit cancellation also covers blocking response-body reads. */
class PipwDownloadOperation {
    private val lock = Any()
    @Volatile private var cancelled = false
    private var activeCall: Call? = null
    internal var template: HttpUrl? = null

    fun cancel() {
        val call = synchronized(lock) {
            cancelled = true
            activeCall
        }
        call?.cancel()
    }

    internal fun checkActive() {
        if (cancelled) throw CancellationException("Pipw download cancelled")
    }

    internal fun <T> request(client: OkHttpClient, url: HttpUrl, deadline: Long, read: (Response) -> T): T {
        checkActive()
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw IOException("Pipw download timed out")
        val call = client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build())
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        synchronized(lock) {
            checkActive()
            activeCall = call
        }
        try {
            return call.execute().use {
                checkActive()
                read(it)
            }
        } catch (e: IOException) {
            checkActive()
            throw e
        } finally {
            synchronized(lock) { if (activeCall === call) activeCall = null }
        }
    }
}

class PipwDownloader(
    private val client: OkHttpClient,
    private val index: PipwReviewIndex,
    private val directory: File,
    private val isDecodable: (File) -> Boolean,
    private val chooseFallback: (List<String>) -> String = { it.random() }
) {
    fun load(pool: PipwPool, apiUrl: String, operation: PipwDownloadOperation): File {
        val api = apiUrl.toHttpUrlOrNull() ?: throw IOException("Invalid Pipw API")
        if (!api.isHttps || api.username.isNotEmpty() || api.password.isNotEmpty()) {
            throw IOException("Invalid Pipw API")
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val initial = operation.request(client, api, minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(7))) {
            if (it.code !in REDIRECTS) throw IOException("Pipw API unavailable")
            PipwUrlPolicy.redirect(api, it.header("Location"), pool)
        }
        operation.template = initial
        try {
            return download(initial, pool, deadline, operation)
        } catch (e: IOException) {
            operation.checkActive()
            if (System.nanoTime() >= deadline) throw e
            val template = operation.template ?: throw e
            val excluded = setOf(PipwUrlPolicy.filename(initial), PipwUrlPolicy.filename(template))
            val candidates = index[pool].fallbackFilenames.filterNot { it in excluded }
            if (candidates.isEmpty()) throw e
            return download(PipwUrlPolicy.fallback(template, chooseFallback(candidates), pool), pool, deadline, operation)
        }
    }

    private fun download(initial: HttpUrl, pool: PipwPool, overallDeadline: Long, operation: PipwDownloadOperation): File {
        val deadline = minOf(overallDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(15))
        var current = initial
        val visited = hashSetOf<HttpUrl>()
        repeat(3) { hop ->
            if (!visited.add(current)) throw IOException("Pipw redirect loop")
            var next: HttpUrl? = null
            val file = operation.request(client, current, deadline) { response ->
                if (response.code in REDIRECTS) {
                    if (hop == 2) throw IOException("Too many Pipw redirects")
                    next = PipwUrlPolicy.redirect(current, response.header("Location"), pool)
                    operation.template = next
                    null
                } else {
                    if (response.code != 200) throw IOException("Pipw image unavailable")
                    readVerifiedFile(response, pool, deadline, operation)
                }
            }
            if (file != null) return file
            current = next ?: throw IOException("Missing Pipw redirect")
        }
        throw IOException("Too many Pipw redirects")
    }

    private fun readVerifiedFile(response: Response, pool: PipwPool, deadline: Long, operation: PipwDownloadOperation): File {
        val declared = response.body.contentLength()
        if (declared > MAX_BYTES) throw IOException("Pipw image exceeds size limit")
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            throw IOException("Unexpected Pipw content encoding")
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Pipw temporary directory unavailable")
        val file = File.createTempFile("pipw-", ".tmp", directory)
        var accepted = false
        try {
            val digest = MessageDigest.getInstance("MD5")
            var count = 0L
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        operation.checkActive()
                        if (System.nanoTime() >= deadline) throw IOException("Pipw download timed out")
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > MAX_BYTES) throw IOException("Pipw image exceeds size limit")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (declared >= 0 && count != declared) throw IOException("Incomplete Pipw image")
            val md5 = buildString(32) {
                for (byte in digest.digest()) {
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0xf])
                }
            }
            if (md5 !in index[pool].byMd5) throw IOException("Pipw content has not been reviewed")
            operation.checkActive()
            if (!isDecodable(file)) throw IOException("Unsupported Pipw image")
            operation.checkActive()
            accepted = true
            return file
        } finally {
            if (!accepted) file.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        private const val HEX = "0123456789abcdef"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    }
}
