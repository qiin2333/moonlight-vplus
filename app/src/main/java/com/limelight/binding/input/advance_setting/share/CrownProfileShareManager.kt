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

object CrownProfileShareManager {
    const val BUNDLE_KIND = "crown-profile-bundle"
    const val SCHEMA_VERSION = 1
    const val FILE_EXTENSION = ".crown.json"

    data class ExportMetadata(
        val packageName: String,
        val appVersionCode: Long,
        val appVersionName: String,
        val exportedAtMillis: Long = System.currentTimeMillis()
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
        val sourceLabel: String,
        val payload: String,
        val payloadInfo: PayloadInfo
    )

    class CrownProfileShareException(message: String) : IllegalArgumentException(message)

    fun createBundle(profileName: String, payload: String, metadata: ExportMetadata): String {
        val cleanName = profileName.trim().ifBlank { "Crown Profile" }
        val payloadInfo = validatePayload(payload)
        val timestamp = formatIso8601(metadata.exportedAtMillis)
        val bundleId = "crown.${suggestedFileStem(cleanName)}.${payloadInfo.payloadSha256.take(8)}"

        val root = JSONObject()
            .put("kind", BUNDLE_KIND)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("bundleId", bundleId)
            .put("name", cleanName)
            .put("summary", "")
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
        val name = profile.optString("name", "")
            .takeIf { it.isNotBlank() }
            ?: root.optString("name", "Crown Profile")

        return ImportedProfile(
            name = name,
            summary = root.optString("summary", ""),
            author = author,
            game = game,
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
            sourceLabel = "Legacy .mdat",
            payload = payload,
            payloadInfo = payloadInfo
        )
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
