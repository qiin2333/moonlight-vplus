package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReleaseMetadataParserTest {
    @Test
    fun parsesAndroidLatestAssetAndFallback() {
        val result = ReleaseMetadataParser.parse(
            json = metadataJson(),
            expectedProduct = "moonlight-vplus",
            channelName = "latest",
            assetType = "android-apk"
        )

        assertNotNull(result)
        assertEquals("12.12.12", result?.version)
        assertEquals("Moonlight.V+.12.12.12.apk", result?.asset?.name)
        assertEquals(25407131L, result?.asset?.size)
        assertEquals(
            "3d36e93048bfb7616372250b445c780ea2905da8744016306e86c9006fb2e13e",
            result?.asset?.sha256
        )
        assertEquals(
            "https://cnb.example/release.apk",
            result?.asset?.url
        )
        assertEquals(
            "https://github.com/example/release.apk",
            result?.asset?.fallbackUrl
        )
    }

    @Test
    fun rejectsUnsupportedSchemaAndWrongProduct() {
        assertNull(
            ReleaseMetadataParser.parse(
                metadataJson().replace("\"schema\": 1", "\"schema\": 2"),
                expectedProduct = "moonlight-vplus",
                channelName = "latest",
                assetType = "android-apk"
            )
        )
        assertNull(
            ReleaseMetadataParser.parse(
                metadataJson(),
                expectedProduct = "other-product",
                channelName = "latest",
                assetType = "android-apk"
            )
        )
    }

    @Test
    fun rejectsInvalidAssetIntegrityFields() {
        val invalidSha = metadataJson().replace(
            "3d36e93048bfb7616372250b445c780ea2905da8744016306e86c9006fb2e13e",
            "not-a-sha256"
        )
        val insecureUrl = metadataJson().replace("https://cnb.example", "http://cnb.example")

        assertNull(ReleaseMetadataParser.parse(invalidSha, "moonlight-vplus", "latest", "android-apk"))
        assertNull(ReleaseMetadataParser.parse(insecureUrl, "moonlight-vplus", "latest", "android-apk"))
    }

    @Test
    fun rejectsHostlessHttpsUrl() {
        val hostlessUrl = metadataJson().replace(
            "https://cnb.example/release.apk",
            "https://"
        )

        assertNull(ReleaseMetadataParser.parse(hostlessUrl, "moonlight-vplus", "latest", "android-apk"))
    }

    private fun metadataJson(): String = """
        {
          "schema": 1,
          "kind": "release-metadata",
          "product": "moonlight-vplus",
          "channels": {
            "latest": {
              "version": "v12.12.12",
              "githubReleaseId": 396518942,
              "releaseNotes": "optional notes",
              "assets": [
                {
                  "type": "android-apk",
                  "name": "Moonlight.V+.12.12.12.apk",
                  "size": 25407131,
                  "sha256": "3d36e93048bfb7616372250b445c780ea2905da8744016306e86c9006fb2e13e",
                  "url": "https://cnb.example/release.apk",
                  "fallbackUrl": "https://github.com/example/release.apk",
                  "futureField": "ignored"
                }
              ]
            },
            "pre-latest": null
          }
        }
    """.trimIndent()
}
