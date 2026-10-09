package com.limelight.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Locale

/** Parses the release-metadata contract published by the CNB mirror. */
internal object ReleaseMetadataParser {
    private const val SUPPORTED_SCHEMA = 1
    private const val METADATA_KIND = "release-metadata"

    internal data class Asset(
        val name: String,
        val size: Long,
        val sha256: String,
        val url: String,
        val fallbackUrl: String?
    )

    internal data class Release(
        val version: String,
        val githubReleaseId: Long?,
        val releaseNotes: String?,
        val asset: Asset
    )

    fun parse(
        json: String,
        expectedProduct: String,
        channelName: String,
        assetType: String
    ): Release? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optInt("schema", -1) != SUPPORTED_SCHEMA ||
            root.optString("kind") != METADATA_KIND ||
            root.optString("product") != expectedProduct
        ) {
            return null
        }

        val channel = root.optJSONObject("channels")?.optJSONObject(channelName) ?: return null
        val version = normalizeVersion(channel.optString("version")) ?: return null
        val assets = channel.optJSONArray("assets") ?: return null

        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("type") != assetType) continue

            val name = asset.optString("name").takeIf { it.endsWith(".apk", ignoreCase = true) } ?: continue
            val size = asset.optLong("size", -1L).takeIf { it > 0L } ?: continue
            val sha256 = asset.optString("sha256")
                .lowercase(Locale.ROOT)
                .takeIf { it.matches(SHA256_PATTERN) }
                ?: continue
            val url = asset.optString("url").takeIf(::isHttpsUrl) ?: continue
            val fallbackUrl = asset.optString("fallbackUrl")
                .takeIf(::isHttpsUrl)

            return Release(
                version = version,
                githubReleaseId = channel.optLong("githubReleaseId", -1L).takeIf { it > 0L },
                releaseNotes = channel.optString("releaseNotes").takeIf { it.isNotBlank() },
                asset = Asset(name, size, sha256, url, fallbackUrl)
            )
        }

        return null
    }

    private fun normalizeVersion(value: String): String? {
        val normalized = value.trim().removePrefix("v").removePrefix("V")
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun isHttpsUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host.isNotBlank()
    }

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
