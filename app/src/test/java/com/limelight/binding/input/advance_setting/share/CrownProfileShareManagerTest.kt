package com.limelight.binding.input.advance_setting.share

import com.limelight.utils.MathUtils
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownProfileShareManagerTest {
    @Test
    fun createBundleWrapsOnlyPublicCrownProfileData() {
        val payload = validPayload()

        val bundle = CrownProfileShareManager.createBundle(
            profileName = "Apex Layout",
            payload = payload,
            metadata = CrownProfileShareManager.ExportMetadata(
                packageName = "com.limelight.test",
                appVersionCode = 390,
                appVersionName = "12.9.8",
                exportedAtMillis = 1781712000000L
            )
        )

        val root = JSONObject(bundle)
        assertEquals(CrownProfileShareManager.BUNDLE_KIND, root.getString("kind"))
        assertEquals(CrownProfileShareManager.SCHEMA_VERSION, root.getInt("schemaVersion"))
        assertEquals("Apex Layout", root.getString("name"))
        assertEquals(payload, root.getJSONObject("profile").getString("payload"))
        assertTrue(root.getJSONObject("profile").getString("profileId").startsWith("public-payload-"))
        assertFalse(bundle.contains("deviceId"))
        assertFalse(bundle.contains("backupDeviceKey"))
        assertFalse(bundle.contains("pairing"))
        assertFalse(bundle.contains("clientPrivateKey"))
    }

    @Test
    fun parseImportTextAcceptsBundle() {
        val payload = validPayload(elementCount = 2)
        val bundle = CrownProfileShareManager.createBundle(
            profileName = "My Layout",
            payload = payload,
            metadata = CrownProfileShareManager.ExportMetadata(
                packageName = "com.limelight.test",
                appVersionCode = 390,
                appVersionName = "12.9.8"
            )
        )

        val imported = CrownProfileShareManager.parseImportText(bundle)

        assertEquals("My Layout", imported.name)
        assertEquals(payload, imported.payload)
        assertEquals(9, imported.payloadInfo.version)
        assertEquals(2, imported.payloadInfo.elementCount)
        assertEquals(2, imported.payloadInfo.settingsCount)
    }

    @Test
    fun parseImportTextAcceptsLegacyMdatPayload() {
        val payload = validPayload()

        val imported = CrownProfileShareManager.parseImportText(payload)

        assertEquals("Legacy .mdat", imported.sourceLabel)
        assertEquals(payload, imported.payload)
        assertEquals(9, imported.payloadInfo.version)
    }

    @Test(expected = CrownProfileShareManager.CrownProfileShareException::class)
    fun parseImportTextRejectsTamperedBundleHash() {
        val payload = validPayload()
        val bundle = JSONObject(
            CrownProfileShareManager.createBundle(
                profileName = "My Layout",
                payload = payload,
                metadata = CrownProfileShareManager.ExportMetadata(
                    packageName = "com.limelight.test",
                    appVersionCode = 390,
                    appVersionName = "12.9.8"
                )
            )
        )
        bundle.getJSONObject("profile").put("payloadSha256", "bad")

        CrownProfileShareManager.parseImportText(bundle.toString())
    }

    @Test(expected = CrownProfileShareManager.CrownProfileShareException::class)
    fun validatePayloadRejectsTamperedMdatChecksum() {
        val payload = JSONObject(validPayload())
        payload.put("settings", """{"config_name":"changed"}""")

        CrownProfileShareManager.validatePayload(payload.toString())
    }

    @Test
    fun parseStoreIndexAcceptsProfiles() {
        val index = JSONObject()
            .put("kind", CrownProfileShareManager.INDEX_KIND)
            .put("schemaVersion", CrownProfileShareManager.SCHEMA_VERSION)
            .put(
                "profiles",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("bundleId", "crown.apex.fps")
                            .put("name", "Apex FPS Layout")
                            .put("summary", "Fast access abilities and movement")
                            .put("author", "WA Crown")
                            .put("game", "Apex Legends")
                            .put("tags", JSONArray().put("fps").put("movement"))
                            .put("updatedAt", "2026-06-18T00:00:00Z")
                            .put("url", "profiles/apex/fps.crown.json")
                    )
            )

        val profiles = CrownProfileShareManager.parseStoreIndex(index.toString())

        assertEquals(1, profiles.size)
        assertEquals("crown.apex.fps", profiles[0].bundleId)
        assertEquals("Apex FPS Layout", profiles[0].name)
        assertEquals("WA Crown", profiles[0].author)
        assertEquals("Apex Legends", profiles[0].game)
        assertEquals(listOf("fps", "movement"), profiles[0].tags)
        assertEquals("profiles/apex/fps.crown.json", profiles[0].url)
    }

    @Test
    fun resolveStoreProfileUrlAcceptsRelativeUrls() {
        val resolved = CrownProfileShareManager.resolveStoreProfileUrl(
            indexUrl = "https://raw.githubusercontent.com/qiin2333/crown-profiles/main/index/v1.json",
            profileUrl = "../profiles/apex/fps.crown.json"
        )

        assertEquals(
            "https://raw.githubusercontent.com/qiin2333/crown-profiles/main/profiles/apex/fps.crown.json",
            resolved
        )
    }

    @Test(expected = CrownProfileShareManager.CrownProfileShareException::class)
    fun parseStoreIndexRejectsWrongKind() {
        val index = JSONObject()
            .put("kind", CrownProfileShareManager.BUNDLE_KIND)
            .put("schemaVersion", CrownProfileShareManager.SCHEMA_VERSION)
            .put("profiles", JSONArray())

        CrownProfileShareManager.parseStoreIndex(index.toString())
    }

    private fun validPayload(elementCount: Int = 1): String {
        val settings = """{"config_name":"default","touch_enable":"true"}"""
        val elements = buildString {
            append("[")
            repeat(elementCount) { index ->
                if (index > 0) append(",")
                append("""{"element_id":$index,"element_type":1}""")
            }
            append("]")
        }
        val version = 9
        val md5 = MathUtils.computeMD5("$version$settings$elements")
        return JSONObject()
            .put("version", version)
            .put("settings", settings)
            .put("elements", elements)
            .put("md5", md5)
            .toString()
    }
}
