package com.limelight.binding.input.advance_setting.share

import com.limelight.utils.MathUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.MalformedURLException
import java.net.URL

object CrownProfileShareManager {
    const val BUNDLE_KIND = "crown-profile-bundle"
    const val INDEX_KIND = "crown-profile-index"
    const val SCHEMA_VERSION = 1
    const val FILE_EXTENSION = ".crown.json"

    data class ExportMetadata(
        val packageName: String,
        val appVersionCode: Long,
        val appVersionName: String,
        val layoutBasis: LayoutBasis? = null,
        val exportedAtMillis: Long = System.currentTimeMillis()
    )

    data class LayoutBasis(
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val density: Float,
        val orientation: String
    ) {
        fun isPresent(): Boolean {
            return widthPx > 0 && heightPx > 0
        }
    }

    data class BundleDisplayMetadata(
        val summary: String = "",
        val authorName: String = "",
        val gameName: String = "",
        val tags: List<String> = emptyList()
    )

    data class PayloadInfo(
        val version: Int,
        val settingsCount: Int,
        val elementCount: Int,
        val payloadSha256: String
    )

    data class ImportedProfile(
        val name: String,
        val summary: String,
        val author: String,
        val game: String,
        val layoutBasis: LayoutBasis?,
        val sourceLabel: String,
        val payload: String,
        val payloadInfo: PayloadInfo
    )

    data class StoreProfile(
        val bundleId: String,
        val name: String,
        val summary: String,
        val author: String,
        val game: String,
        val tags: List<String>,
        val layoutBasis: LayoutBasis?,
        val updatedAt: String,
        val url: String
    )

    class CrownProfileShareException(message: String) : IllegalArgumentException(message)

    fun createBundle(
        profileName: String,
        payload: String,
        metadata: ExportMetadata,
        displayMetadata: BundleDisplayMetadata = BundleDisplayMetadata()
    ): String {
        val cleanName = profileName.trim().ifBlank { "Crown Profile" }
        val payloadInfo = validatePayload(payload)
        val timestamp = formatIso8601(metadata.exportedAtMillis)
        val bundleId = "crown.${suggestedFileStem(cleanName)}.${payloadInfo.payloadSha256.take(8)}"

        val root = JSONObject()
            .put("kind", BUNDLE_KIND)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("bundleId", bundleId)
            .put("name", cleanName)
            .put("summary", displayMetadata.summary.trim())
            .put(
                "compatibility",
                JSONObject()
                    .put("minAppVersionCode", metadata.appVersionCode)
                    .put("profilePayloadVersion", payloadInfo.version)
            )
            .put(
                "profile",
                JSONObject()
                    .put("profileId", "public-payload-${payloadInfo.payloadSha256}")
                    .put("name", cleanName)
                    .put("payload", payload)
                    .put("payloadSha256", payloadInfo.payloadSha256)
            )
            .put("createdAt", timestamp)
            .put("updatedAt", timestamp)
            .put("packageName", metadata.packageName)

        metadata.layoutBasis
            ?.takeIf { it.isPresent() }
            ?.let { root.put("layoutBasis", layoutBasisToJson(it)) }

        displayMetadata.authorName.trim()
            .takeIf { it.isNotBlank() }
            ?.let { root.put("author", JSONObject().put("name", it)) }
        displayMetadata.gameName.trim()
            .takeIf { it.isNotBlank() }
            ?.let { root.put("game", JSONObject().put("name", it)) }
        displayMetadata.tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.let { tags ->
                val jsonTags = JSONArray()
                tags.forEach { jsonTags.put(it) }
                root.put("tags", jsonTags)
            }

        metadata.appVersionName
            .takeIf { it.isNotBlank() }
            ?.let { root.put("appVersionName", it) }

        return root.toString(2)
    }

    fun parseImportText(text: String): ImportedProfile {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            throw CrownProfileShareException("Profile file is empty")
        }

        val root = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw CrownProfileShareException("Profile file is not valid JSON")
        }

        return if (root.optString("kind") == BUNDLE_KIND) {
            parseBundle(root)
        } else {
            parseLegacyPayload(trimmed)
        }
    }

    fun validatePayload(payload: String): PayloadInfo {
        val root = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            throw CrownProfileShareException("Crown payload is not valid JSON")
        }

        if (!root.has("version") || !root.has("settings") || !root.has("elements") || !root.has("md5")) {
            throw CrownProfileShareException("Crown payload is missing required fields")
        }

        val version = root.optInt("version", -1)
        val settings = root.optString("settings", "")
        val elements = root.optString("elements", "")
        val expectedMd5 = root.optString("md5", "")
        if (version < 0 || settings.isBlank() || elements.isBlank() || expectedMd5.isBlank()) {
            throw CrownProfileShareException("Crown payload is incomplete")
        }

        val actualMd5 = MathUtils.computeMD5("$version$settings$elements")
        if (!actualMd5.equals(expectedMd5, ignoreCase = true)) {
            throw CrownProfileShareException("Crown payload checksum does not match")
        }

        val settingsCount = try {
            JSONObject(settings).length()
        } catch (e: JSONException) {
            throw CrownProfileShareException("Crown payload settings are invalid")
        }
        val elementCount = try {
            JSONArray(elements).length()
        } catch (e: JSONException) {
            throw CrownProfileShareException("Crown payload elements are invalid")
        }

        return PayloadInfo(
            version = version,
            settingsCount = settingsCount,
            elementCount = elementCount,
            payloadSha256 = sha256Hex(payload)
        )
    }

    fun suggestedFileName(profileName: String): String {
        return suggestedFileStem(profileName) + FILE_EXTENSION
    }

    fun parseStoreIndex(text: String): List<StoreProfile> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            throw CrownProfileShareException("Crown store index is empty")
        }

        val root = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw CrownProfileShareException("Crown store index is not valid JSON")
        }

        if (root.optString("kind") != INDEX_KIND) {
            throw CrownProfileShareException("Crown store index has an unsupported kind")
        }
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SCHEMA_VERSION) {
            throw CrownProfileShareException("Unsupported Crown store index version")
        }

        val profiles = root.optJSONArray("profiles")
            ?: throw CrownProfileShareException("Crown store index is missing profiles")
        val result = ArrayList<StoreProfile>(profiles.length())
        for (index in 0 until profiles.length()) {
            val profile = profiles.optJSONObject(index)
                ?: throw CrownProfileShareException("Crown store index contains an invalid profile")
            result.add(parseStoreProfile(profile))
        }
        return result
    }

    fun resolveStoreProfileUrl(indexUrl: String, profileUrl: String): String {
        val resolvedUrl = try {
            URL(URL(indexUrl), profileUrl.trim())
        } catch (e: MalformedURLException) {
            throw CrownProfileShareException("Crown store profile URL is invalid")
        }

        val protocol = resolvedUrl.protocol.lowercase(Locale.US)
        if (protocol != "https") {
            throw CrownProfileShareException("Crown store profile URL must use HTTPS")
        }
        return resolvedUrl.toString()
    }

    private fun parseBundle(root: JSONObject): ImportedProfile {
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SCHEMA_VERSION) {
            throw CrownProfileShareException("Unsupported Crown profile bundle version")
        }

        val profile = root.optJSONObject("profile")
            ?: throw CrownProfileShareException("Crown profile bundle is missing profile data")
        val payload = profile.optString("payload", "")
        val expectedSha256 = profile.optString("payloadSha256", "")
        if (payload.isBlank() || expectedSha256.isBlank()) {
            throw CrownProfileShareException("Crown profile bundle is incomplete")
        }

        val actualSha256 = sha256Hex(payload)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw CrownProfileShareException("Crown profile bundle checksum does not match")
        }

        val payloadInfo = validatePayload(payload)
        val author = root.optJSONObject("author")?.optString("name", "").orEmpty()
        val game = root.optJSONObject("game")?.optString("name", "").orEmpty()
        val layoutBasis = parseLayoutBasis(root.optJSONObject("layoutBasis"))
        val name = profile.optString("name", "")
            .takeIf { it.isNotBlank() }
            ?: root.optString("name", "Crown Profile")

        return ImportedProfile(
            name = name,
            summary = root.optString("summary", ""),
            author = author,
            game = game,
            layoutBasis = layoutBasis,
            sourceLabel = "Crown share package",
            payload = payload,
            payloadInfo = payloadInfo
        )
    }

    private fun parseLegacyPayload(payload: String): ImportedProfile {
        val payloadInfo = validatePayload(payload)
        return ImportedProfile(
            name = "Crown Profile",
            summary = "",
            author = "",
            game = "",
            layoutBasis = null,
            sourceLabel = "Legacy .mdat",
            payload = payload,
            payloadInfo = payloadInfo
        )
    }

    private fun parseStoreProfile(profile: JSONObject): StoreProfile {
        val name = profile.optString("name", "").trim()
        val url = profile.optString("url", "").trim()
        if (name.isBlank() || url.isBlank()) {
            throw CrownProfileShareException("Crown store profile is missing a name or URL")
        }

        return StoreProfile(
            bundleId = profile.optString("bundleId", profile.optString("id", "")).trim(),
            name = name,
            summary = profile.optString("summary", "").trim(),
            author = profile.optString("author", "").trim(),
            game = profile.optString("game", "").trim(),
            tags = parseStringArray(profile.optJSONArray("tags")),
            layoutBasis = parseLayoutBasis(profile.optJSONObject("layoutBasis")),
            updatedAt = profile.optString("updatedAt", "").trim(),
            url = url
        )
    }

    fun layoutBasisToJson(layoutBasis: LayoutBasis): JSONObject {
        return JSONObject()
            .put("widthPx", layoutBasis.widthPx)
            .put("heightPx", layoutBasis.heightPx)
            .put("densityDpi", layoutBasis.densityDpi)
            .put("density", layoutBasis.density.toDouble())
            .put("orientation", layoutBasis.orientation)
    }

    private fun parseLayoutBasis(json: JSONObject?): LayoutBasis? {
        if (json == null) return null
        val widthPx = json.optInt("widthPx", 0)
        val heightPx = json.optInt("heightPx", 0)
        if (widthPx <= 0 || heightPx <= 0) return null
        return LayoutBasis(
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = json.optInt("densityDpi", 0),
            density = json.optDouble("density", 0.0).toFloat(),
            orientation = json.optString("orientation", "").trim()
        )
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.optString(index, "").trim()
            if (value.isNotBlank()) {
                result.add(value)
            }
        }
        return result
    }

    private fun suggestedFileStem(profileName: String): String {
        val normalized = profileName
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        return normalized.ifBlank { "crown-profile" }
    }

    private fun formatIso8601(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestampMs))
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}
