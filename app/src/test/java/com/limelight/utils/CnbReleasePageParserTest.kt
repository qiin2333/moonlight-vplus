package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CnbReleasePageParserTest {
    @Test
    fun parsesCurrentCnbReleaseApiResponse() {
        val payload = """
            [{
              "tag_name": "v12.12.12",
              "is_latest": true,
              "draft": false,
              "prerelease": false,
              "body": "release notes",
              "assets": [{
                "name": "Moonlight.V+.12.12.12.apk",
                "content_type": "application/vnd.android.package-archive",
                "browser_download_url": "https://cnb.cool/AlkaidLab/moonlight-vplus-android-release/-/releases/download/v12.12.12/Moonlight.V%2B.12.12.12.apk",
                "size": 200,
                "hash_value": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              }]
            }]
        """.trimIndent()

        val result = CnbReleasePageParser.parse(payload)

        assertEquals("12.12.12", result?.version)
        assertEquals("Moonlight.V+.12.12.12.apk", result?.apkName)
        assertEquals(200L, result?.expectedSize)
        assertEquals(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            result?.expectedSha256
        )
        assertTrue(result?.downloadUrls?.first() ==
            "https://cnb.cool/AlkaidLab/moonlight-vplus-android-release/-/releases/download/v12.12.12/Moonlight.V%2B.12.12.12.apk")
    }

    @Test
    fun parsesLatestNonRootApkFromNextData() {
        val html = """
            <html><script id="__NEXT_DATA__" type="application/json">
            {
              "props": {"pageProps": {"initialState": {"slug": {"repo": {"releases": {"list": {"data": {
                "releases": [{
                  "tag_ref": "refs/tags/v12.12.12",
                  "is_latest": true,
                  "is_draft": false,
                  "is_prerelease": false,
                  "body": "release notes",
                  "assets": [
                    {"name":"Moonlight.V+.12.12.12-root.apk","content_type":"application/vnd.android.package-archive","path":"/root.apk","size_in_byte":100,"hash_value":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                    {"name":"Moonlight.V+.12.12.12.apk","content_type":"application/vnd.android.package-archive","path":"/AlkaidLab/moonlight-vplus-android-release/-/releases/download/v12.12.12/Moonlight.V+.12.12.12.apk","size_in_byte":200,"hash_value":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                  ]
                }]
              }}}}}}}}
            }
            </script></html>
        """.trimIndent()

        val result = CnbReleasePageParser.parse(html)

        assertEquals("12.12.12", result?.version)
        assertEquals("Moonlight.V+.12.12.12.apk", result?.apkName)
        assertEquals(200L, result?.expectedSize)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", result?.expectedSha256)
        assertTrue(result?.downloadUrls?.first()?.contains("cnb.cool") == true)
        assertTrue(result?.downloadUrls?.last()?.contains("github.com") == true)
    }
}
