package com.limelight.handbook

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

sealed class HandbookLoadResult {
    data class Success(
        val html: String,
        val baseUrl: String
    ) : HandbookLoadResult()

    data class Failure(val reason: HandbookFailureReason) : HandbookLoadResult()
}

enum class HandbookFailureReason {
    NETWORK,
    TIMEOUT,
    UNAVAILABLE
}

class HandbookRepository(
    private val appContext: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(ORIGIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ORIGIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
) {
    suspend fun load(page: HandbookPageRef): HandbookLoadResult = withContext(Dispatchers.IO) {
        if (!isNetworkConnected()) {
            return@withContext HandbookLoadResult.Failure(HandbookFailureReason.NETWORK)
        }

        val failures = mutableListOf<AttemptFailure>()
        for (candidate in HandbookUrlPolicy.originCandidates(page)) {
            try {
                return@withContext fetchFromOrigin(candidate)
            } catch (error: Exception) {
                failures += classify(error)
            }
        }

        val reason = when {
            failures.isNotEmpty() && failures.all { it == AttemptFailure.NETWORK } ->
                HandbookFailureReason.NETWORK
            failures.isNotEmpty() && failures.all { it == AttemptFailure.TIMEOUT } ->
                HandbookFailureReason.TIMEOUT
            else -> HandbookFailureReason.UNAVAILABLE
        }
        HandbookLoadResult.Failure(reason)
    }

    private fun fetchFromOrigin(initialUrl: HttpUrl): HandbookLoadResult.Success {
        val originHost = initialUrl.host
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(ORIGIN_TIMEOUT_MS)
        var currentUrl = initialUrl
        var redirectCount = 0

        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos < TimeUnit.MILLISECONDS.toNanos(1L)) {
                throw SocketTimeoutException("Handbook origin timed out")
            }

            val requestClient = client.newBuilder()
                .callTimeout(remainingNanos, TimeUnit.NANOSECONDS)
                .build()
            val request = Request.Builder()
                .url(currentUrl)
                .header("Accept", "text/html, application/xhtml+xml")
                .header("Cache-Control", "no-cache")
                .build()

            val response = requestClient.newCall(request).execute()
            if (response.code in REDIRECT_CODES) {
                response.use {
                    if (++redirectCount > MAX_REDIRECTS) {
                        throw IOException("Too many handbook redirects")
                    }
                    currentUrl = validatedRedirect(it, currentUrl, originHost)
                }
                continue
            }

            response.use {
                if (!it.isSuccessful) {
                    throw IOException("Handbook response was not successful")
                }

                val html = readValidatedHtml(it)
                return HandbookLoadResult.Success(
                    html = html,
                    baseUrl = currentUrl.toString()
                )
            }
        }
    }

    private fun validatedRedirect(
        response: Response,
        currentUrl: HttpUrl,
        originHost: String
    ): HttpUrl {
        val location = response.header("Location")
            ?: throw IOException("Handbook redirect had no location")
        val redirected = currentUrl.resolve(location)
            ?: throw IOException("Invalid handbook redirect")
        if (redirected.host != originHost || HandbookUrlPolicy.parse(redirected.toString()) == null) {
            throw IOException("Handbook redirect left its allowed origin")
        }
        return redirected.newBuilder().fragment(null).build()
    }

    private fun readValidatedHtml(response: Response): String {
        val body = response.body
        val contentType = body.contentType()
            ?: throw IOException("Handbook response had no content type")
        val isHtml = (contentType.type == "text" && contentType.subtype == "html") ||
            (contentType.type == "application" && contentType.subtype == "xhtml+xml")
        if (!isHtml) {
            throw IOException("Handbook response was not HTML")
        }
        if (body.contentLength() > MAX_HTML_BYTES) {
            throw IOException("Handbook response was too large")
        }

        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_HTML_BYTES) {
                    throw IOException("Handbook response was too large")
                }
                output.write(buffer, 0, read)
            }
        }

        val charset = contentType.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
        return output.toString(charset.name())
    }

    @Suppress("DEPRECATION")
    private fun isNetworkConnected(): Boolean {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun classify(error: Exception): AttemptFailure {
        return when (error) {
            is SocketTimeoutException,
            is InterruptedIOException -> AttemptFailure.TIMEOUT
            is UnknownHostException,
            is NoRouteToHostException,
            is ConnectException -> AttemptFailure.NETWORK
            else -> AttemptFailure.UNAVAILABLE
        }
    }

    private enum class AttemptFailure {
        NETWORK,
        TIMEOUT,
        UNAVAILABLE
    }

    private companion object {
        const val ORIGIN_TIMEOUT_MS = 3_000L
        const val MAX_HTML_BYTES = 2 * 1024 * 1024
        const val READ_BUFFER_BYTES = 8 * 1024
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
