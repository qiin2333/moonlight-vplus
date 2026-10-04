package com.limelight.utils

import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class UpdateMetadata(
    val source: String,
    val version: String,
    val releaseNotes: String?,
    val releasePageUrl: String,
    val apkName: String?,
    val downloadUrls: List<String>,
    val expectedSha256: String?,
    val expectedSize: Long?
)

internal object UpdateMetadataClient {
    private const val TAG = "UpdateMetadataClient"
    private const val OFFICIAL_COM_METADATA_URL =
        "https://www.alkaidlab.com/release-metadata/moonlight-vplus.json"
    private const val OFFICIAL_CN_METADATA_URL =
        "https://www.alkaidlab.cn/release-metadata/moonlight-vplus.json"
    private const val CNB_RELEASE_PAGE_URL =
        "https://cnb.cool/AlkaidLab/moonlight-vplus-android-release/-/releases"
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/qiin2333/moonlight-vplus/releases/latest"
    internal const val GITHUB_RELEASE_PAGE_URL =
        "https://github.com/qiin2333/moonlight-vplus/releases/latest"

    private const val PRODUCT = "moonlight-vplus"
    private const val CHANNEL = "latest"
    private const val ANDROID_ASSET_TYPE = "android-apk"
    private const val CONNECT_TIMEOUT_MS = 2_000L
    private const val READ_TIMEOUT_MS = 5_000L
    private const val CALL_TIMEOUT_MS = 7_000L
    private const val OFFICIAL_GROUP_DEADLINE_MS = 12_000L
    private const val MAX_METADATA_BYTES = 512 * 1024L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun fetchForChinese(): UpdateMetadata? {
        fetchOfficialMetadata()?.let { official ->
            if (!official.releaseNotes.isNullOrBlank()) {
                return official
            }

            // The official manifest is authoritative for the APK and its
            // integrity fields, while CNB remains the release-note source
            // when the manifest publisher omitted releaseNotes.
            fetchCnbRelease()
                ?.takeIf { it.version == official.version && !it.releaseNotes.isNullOrBlank() }
                ?.let { cnb ->
                    return official.copy(releaseNotes = cnb.releaseNotes)
                }
            return official
        }
        fetchCnbRelease()?.let { return it }
        return fetchGithubRelease()
    }

    fun fetchForGithub(): UpdateMetadata? = fetchGithubRelease()

    private fun fetchOfficialMetadata(): UpdateMetadata? {
        val sources = listOf(
            MetadataTask("official-com", OFFICIAL_COM_METADATA_URL, 0),
            // One initial request plus one retry is enough for the domestic mirror.
            MetadataTask("official-cn", OFFICIAL_CN_METADATA_URL, 1)
        )
        val pool = Executors.newFixedThreadPool(sources.size)
        val completion = ExecutorCompletionService<UpdateMetadata?>(pool)
        val futures = ArrayList<Future<UpdateMetadata?>>(sources.size)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OFFICIAL_GROUP_DEADLINE_MS)
        try {
            sources.forEach { futures += completion.submit(it) }
            repeat(sources.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return null
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
                val result = runCatching { future.get() }.getOrNull()
                if (result != null) return result
            }
            return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            Log.w(TAG, "official metadata group failed: ${e.message}")
            return null
        } finally {
            sources.forEach(MetadataTask::cancel)
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    private fun fetchCnbRelease(): UpdateMetadata? {
        val payload = executeText("cnb-release", CNB_RELEASE_PAGE_URL) ?: return null
        val release = CnbReleasePageParser.parse(payload) ?: return null
        return UpdateMetadata(
            source = "cnb-release",
            version = release.version,
            releaseNotes = release.releaseNotes,
            releasePageUrl = "$GITHUB_RELEASE_PAGE_URL/tag/v${release.version}",
            apkName = release.apkName,
            downloadUrls = release.downloadUrls,
            expectedSha256 = release.expectedSha256,
            expectedSize = release.expectedSize
        )
    }

    private fun fetchGithubRelease(): UpdateMetadata? {
        val json = executeText("github", GITHUB_API_URL) ?: return null
        val response = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val version = response.optString("tag_name")
            .replaceFirst("^[Vv]".toRegex(), "")
            .takeIf { it.isNotBlank() }
            ?: return null
        val releaseNotes = response.optString("body")
        val releasePageUrl = response.optString("html_url").takeIf { it.isNotBlank() }
            ?: "$GITHUB_RELEASE_PAGE_URL/tag/v$version"

        val apkAssets = response.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .filter { asset ->
                    asset.optString("name").endsWith(".apk", ignoreCase = true) &&
                        asset.optString("browser_download_url").startsWith("https://")
                }
        }.orEmpty()
        val asset = apkAssets.firstOrNull { !it.optString("name").contains("root", ignoreCase = true) }
            ?: apkAssets.firstOrNull()

        val apkUrl = asset?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
        val apkName = asset?.optString("name")?.takeIf { it.isNotBlank() }
        val digest = asset?.optString("digest")
            ?.removePrefix("sha256:")
            ?.lowercase()
            ?.takeIf { it.matches(SHA256_PATTERN) }
        val sha256 = digest ?: extractSha256ForApk(releaseNotes, apkName)
        val size = asset?.optLong("size", -1L)?.takeIf { it > 0L }
        return UpdateMetadata(
            source = "github",
            version = version,
            releaseNotes = releaseNotes.takeIf { it.isNotBlank() },
            releasePageUrl = releasePageUrl,
            apkName = apkName,
            downloadUrls = listOfNotNull(apkUrl),
            expectedSha256 = sha256,
            expectedSize = size
        )
    }

    private fun executeText(source: String, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Moonlight-Android")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body ?: return null
                if (!response.isSuccessful) return null
                readBodyWithinLimit(body)
            }
        } catch (e: IOException) {
            Log.w(TAG, "$source request failed: ${e.message}")
            null
        }
    }

    private class MetadataTask(
        private val source: String,
        private val url: String,
        private val maxRetries: Int
    ) : Callable<UpdateMetadata?> {
        private val activeCall = AtomicReference<Call?>()
        private val finished = AtomicBoolean(false)

        override fun call(): UpdateMetadata? {
            for (attempt in 0..maxRetries) {
                if (Thread.currentThread().isInterrupted || finished.get()) return null
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Moonlight-Android")
                    .build()
                val call = client.newCall(request)
                activeCall.set(call)
                try {
                    call.execute().use { response ->
                        val body = response.body
                        if (response.isSuccessful) {
                            val json = readBodyWithinLimit(body) ?: return@use
                            val parsed = ReleaseMetadataParser.parse(
                                json = json,
                                expectedProduct = PRODUCT,
                                channelName = CHANNEL,
                                assetType = ANDROID_ASSET_TYPE
                            )
                            if (parsed != null) {
                                finished.set(true)
                                return UpdateMetadata(
                                    source = source,
                                    version = parsed.version,
                                    releaseNotes = parsed.releaseNotes,
                                    releasePageUrl = "$GITHUB_RELEASE_PAGE_URL/tag/v${parsed.version}",
                                    apkName = parsed.asset.name,
                                    downloadUrls = listOfNotNull(parsed.asset.url, parsed.asset.fallbackUrl).distinct(),
                                    expectedSha256 = parsed.asset.sha256,
                                    expectedSize = parsed.asset.size
                                )
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (attempt == maxRetries) Log.w(TAG, "$source metadata failed after retries: ${e.message}")
                } finally {
                    activeCall.compareAndSet(call, null)
                }
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(250L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
            finished.set(true)
            return null
        }

        fun cancel() {
            finished.set(true)
            activeCall.get()?.cancel()
        }
    }

    private fun readBodyWithinLimit(body: ResponseBody): String? {
        if (body.contentLength() > MAX_METADATA_BYTES) return null

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                total += count
                if (total > MAX_METADATA_BYTES) return null
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun extractSha256ForApk(notes: String, apkName: String?): String? {
        if (apkName.isNullOrBlank()) return null
        val name = Regex.escape(apkName)
        val patterns = listOf(
            Regex("([a-fA-F0-9]{64})\\s+\\*?$name", RegexOption.IGNORE_CASE),
            Regex("$name\\s*[:=\\-]?\\s*([a-fA-F0-9]{64})", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(notes)?.groupValues?.getOrNull(1)?.lowercase()
        }
    }

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
