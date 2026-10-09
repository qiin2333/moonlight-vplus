package com.limelight.utils

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object CnbReleasePageParser {
    private const val CNB_ORIGIN = "https://cnb.cool"
    private const val GITHUB_DOWNLOAD_BASE =
        "https://github.com/qiin2333/moonlight-vplus/releases/download"

    internal data class Release(
        val version: String,
        val releaseNotes: String?,
        val apkName: String,
        val downloadUrls: List<String>,
        val expectedSha256: String,
        val expectedSize: Long
    )

    fun parse(payload: String): Release? {
        val releases = parseReleaseList(payload) ?: return null

        val latest = (0 until releases.length())
            .mapNotNull { releases.optJSONObject(it) }
            .firstOrNull {
                it.optBoolean("is_latest", false) &&
                    !it.optBoolean("is_draft", it.optBoolean("draft", false)) &&
                    !it.optBoolean("is_prerelease", it.optBoolean("prerelease", false))
            }
            ?: return null

        val tag = latest.optString("tag_ref")
            .takeIf { it.isNotBlank() }
            ?.substringAfterLast('/')
            ?: latest.optString("tag_name")
            .takeIf { it.isNotBlank() }
            ?: return null
        val version = tag.removePrefix("v").removePrefix("V").takeIf { it.isNotBlank() } ?: return null
        val assets = latest.optJSONArray("assets") ?: return null
        val apkAssets = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { asset ->
                val contentType = asset.optString("content_type")
                val name = asset.optString("name")
                contentType == "application/vnd.android.package-archive" ||
                    name.endsWith(".apk", ignoreCase = true)
            }
        val apk = apkAssets.firstOrNull { !it.optString("name").lowercase(Locale.ROOT).contains("root") }
            ?: apkAssets.firstOrNull()
            ?: return null

        val name = apk.optString("name").takeIf { it.endsWith(".apk", ignoreCase = true) } ?: return null
        val cnbUrl = buildCnbDownloadUrl(apk) ?: return null
        val size = apk.optLong("size", apk.optLong("size_in_byte", -1L))
            .takeIf { it > 0L }
            ?: return null
        val sha256 = (apk.optString("hash_value").takeIf { it.isNotBlank() }
            ?: apk.optString("sha256"))
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(SHA256_PATTERN) }
            ?: return null

        val githubUrl = GITHUB_DOWNLOAD_BASE.toHttpUrl()
            .newBuilder()
            .addPathSegment(tag)
            .addPathSegment(name)
            .build()
            .toString()

        return Release(
            version = version,
            releaseNotes = latest.optString("body").takeIf { it.isNotBlank() },
            apkName = name,
            downloadUrls = listOf(cnbUrl, githubUrl).distinct(),
            expectedSha256 = sha256,
            expectedSize = size
        )
    }

    private fun parseReleaseList(payload: String): JSONArray? {
        val trimmed = payload.trim()
        if (trimmed.startsWith("[")) {
            return runCatching { JSONArray(trimmed) }.getOrNull()
        }

        val nextData = NEXT_DATA_PATTERN.find(payload)?.groupValues?.getOrNull(1) ?: return null
        val root = runCatching { JSONObject(nextData) }.getOrNull() ?: return null
        return root.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("initialState")
            ?.optJSONObject("slug")
            ?.optJSONObject("repo")
            ?.optJSONObject("releases")
            ?.optJSONObject("list")
            ?.optJSONObject("data")
            ?.optJSONArray("releases")
    }

    private fun buildCnbDownloadUrl(asset: JSONObject): String? {
        val browserUrl = asset.optString("browser_download_url")
            .takeIf { it.startsWith("$CNB_ORIGIN/") }
        if (browserUrl != null) return browserUrl

        val path = asset.optString("path").takeIf { it.startsWith("/") } ?: return null
        return runCatching {
            CNB_ORIGIN.toHttpUrl()
                .newBuilder()
                .addPathSegments(path.trimStart('/'))
                .build()
                .toString()
        }.getOrNull()
    }

    private val NEXT_DATA_PATTERN = Regex(
        "<script\\s+id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
