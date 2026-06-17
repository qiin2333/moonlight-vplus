package com.limelight.binding.input.advance_setting.share

import com.limelight.utils.MathUtils
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
