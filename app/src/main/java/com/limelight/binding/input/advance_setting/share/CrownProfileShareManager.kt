package com.limelight.binding.input.advance_setting.share

import com.limelight.binding.input.advance_setting.element.Element
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
        val payloadInfo: PayloadInfo,
        val installName: String? = null
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

    enum class PreviewElementKind {
        BUTTON,
        PAD,
        STICK,
        GROUP,
        WHEEL
    }

    data class PreviewElement(
        val kind: PreviewElementKind,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val fillColor: Int,
        val pressedColor: Int,
        val textColor: Int,
        val text: String,
        val alpha: Float,
        val invisible: Boolean
    )

    data class ProfilePreview(
        val widthPx: Int,
        val heightPx: Int,
        val elements: List<PreviewElement>
    ) {
        val isEmpty: Boolean
            get() = elements.isEmpty()

        companion object {
            fun empty(widthPx: Int = DEFAULT_PREVIEW_WIDTH_PX, heightPx: Int = DEFAULT_PREVIEW_HEIGHT_PX) =
                ProfilePreview(widthPx, heightPx, emptyList())
        }
    }

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

    /**
     * Extracts a small, display-only representation from the exported profile.
     * Invalid optional element fields are ignored so thumbnail failures never
     * affect importing or validating the profile itself.
     */
    fun parsePreview(payload: String, layoutBasis: LayoutBasis? = null): ProfilePreview {
        val payloadRoot = runCatching { JSONObject(payload) }.getOrNull() ?: return ProfilePreview.empty()
        val elements = runCatching { JSONArray(payloadRoot.optString("elements", "[]")) }.getOrNull()
            ?: return ProfilePreview.empty(
                layoutBasis?.widthPx ?: DEFAULT_PREVIEW_WIDTH_PX,
                layoutBasis?.heightPx ?: DEFAULT_PREVIEW_HEIGHT_PX
            )

        val parsedElements = buildList {
            for (index in 0 until elements.length()) {
                val element = elements.optJSONObject(index) ?: continue
                val elementType = element.optInt("element_type", -1)
                if (elementType < 0) continue

                val width = element.optInt("element_width", 0).coerceAtLeast(1).toFloat()
                val height = element.optInt("element_height", 0).coerceAtLeast(1).toFloat()
                val centerX = element.optInt("element_central_x", 0).toFloat()
                val centerY = element.optInt("element_central_y", 0).toFloat()
                if (centerX <= 0f || centerY <= 0f) continue

                val fillColor = previewColor(
                    element.optInt("element_color", 0),
                    element.optInt("element_background_color", 0)
                )
                val pressedColor = previewColor(
                    element.optInt("element_pressed_color", 0),
                    fillColor
                )
                val textColor = previewColor(
                    element.optInt("normalTextColor", 0),
                    0xFFFFFFFF.toInt()
                )
                val opacity = element.optInt("element_opacity", 255)
                    .coerceIn(0, 255) / 255f
                val invisible = elementType == Element.ELEMENT_TYPE_INVISIBLE_ANALOG_STICK ||
                    elementType == Element.ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK

                add(
                    PreviewElement(
                        kind = previewElementKind(elementType),
                        centerX = centerX,
                        centerY = centerY,
                        width = width,
                        height = height,
                        fillColor = fillColor,
                        pressedColor = pressedColor,
                        textColor = textColor,
                        text = element.optString("element_text", "").trim(),
                        alpha = if (invisible) opacity.coerceAtMost(0.28f) else opacity,
                        invisible = invisible
                    )
                )
            }
        }

        val inferredWidth = parsedElements.maxOfOrNull { it.centerX + it.width / 2f }
            ?.coerceAtLeast(1f)
            ?.toInt()
            ?: DEFAULT_PREVIEW_WIDTH_PX
        val inferredHeight = parsedElements.maxOfOrNull { it.centerY + it.height / 2f }
            ?.coerceAtLeast(1f)
            ?.toInt()
            ?: DEFAULT_PREVIEW_HEIGHT_PX
        val basisWidth = layoutBasis?.widthPx?.takeIf { it > 0 }
        val basisHeight = layoutBasis?.heightPx?.takeIf { it > 0 }
        val metadataLooksLikeDeviceFrame = basisWidth != null && basisHeight != null &&
            inferredWidth > basisWidth * 1.2f && inferredHeight < basisHeight * 0.8f
        val widthPx = if (metadataLooksLikeDeviceFrame) inferredWidth else basisWidth ?: inferredWidth
        val heightPx = if (metadataLooksLikeDeviceFrame) inferredHeight else basisHeight ?: inferredHeight

        return ProfilePreview(
            widthPx = widthPx,
            heightPx = heightPx,
            elements = parsedElements
                .sortedByDescending { it.width * it.height }
                .take(MAX_PREVIEW_ELEMENTS)
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

    fun payloadForInstallAsNew(profile: ImportedProfile): String {
        val installName = profile.installName?.trim().orEmpty()
        if (installName.isBlank()) {
            return profile.payload
        }

        val root = try {
            JSONObject(profile.payload)
        } catch (e: JSONException) {
            throw CrownProfileShareException("Crown payload is not valid JSON")
        }
        val settings = try {
            JSONObject(root.optString("settings", ""))
        } catch (e: JSONException) {
            throw CrownProfileShareException("Crown payload settings are invalid")
        }

        settings.put(CrownProfileSchema.CONFIG_NAME_KEY, installName)
        val settingsText = settings.toString()
        val version = root.optInt("version", -1)
        val elements = root.optString("elements", "")
        root.put("settings", settingsText)
        root.put("md5", MathUtils.computeMD5("$version$settingsText$elements"))

        val payload = root.toString()
        validatePayload(payload)
        return payload
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
            payloadInfo = payloadInfo,
            installName = name
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

    private fun previewElementKind(elementType: Int): PreviewElementKind = when (elementType) {
        Element.ELEMENT_TYPE_DIGITAL_PAD -> PreviewElementKind.PAD
        Element.ELEMENT_TYPE_ANALOG_STICK,
        Element.ELEMENT_TYPE_DIGITAL_STICK,
        Element.ELEMENT_TYPE_INVISIBLE_ANALOG_STICK,
        Element.ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK -> PreviewElementKind.STICK
        Element.ELEMENT_TYPE_GROUP_BUTTON -> PreviewElementKind.GROUP
        Element.ELEMENT_TYPE_WHEEL_PAD -> PreviewElementKind.WHEEL
        else -> PreviewElementKind.BUTTON
    }

    private fun previewColor(value: Int, fallback: Int): Int {
        return if (value ushr 24 == 0) fallback else value
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

    private const val MAX_PREVIEW_ELEMENTS = 96
    private const val DEFAULT_PREVIEW_WIDTH_PX = 1920
    private const val DEFAULT_PREVIEW_HEIGHT_PX = 1080
}
