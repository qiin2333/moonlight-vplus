package com.limelight.preferences

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Transport shared by GitHub OAuth and REST calls. Business errors stay with their callers. */
internal class GitHubHttpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    data class Result(
        val code: Int,
        val body: String,
        val retryAfter: String?,
        val rateLimitRemaining: String?
    )

    @Throws(IOException::class)
    fun form(url: String, values: Map<String, String>): Result {
        val form = FormBody.Builder().apply {
            values.forEach { (key, value) -> add(key, value) }
        }.build()
        return execute(
            Request.Builder().url(url)
                .header("Accept", "application/json")
                .post(form)
                .build(),
            15
        )
    }

    @Throws(IOException::class)
    fun api(url: String, token: String, method: String = "GET", json: String? = null): Result {
        val body = json?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Moonlight-VPlus-Crown-Store")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body)
            .build()
        return execute(request, 20)
    }

    private fun execute(request: Request, readTimeoutSeconds: Long): Result {
        val callClient = client.newBuilder()
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        return callClient.newCall(request).execute().use { response ->
            Result(
                code = response.code,
                body = response.body.string(),
                retryAfter = response.header("Retry-After"),
                rateLimitRemaining = response.header("X-RateLimit-Remaining")
            )
        }
    }
}
