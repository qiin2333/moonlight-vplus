package com.limelight.binding.input.advance_setting.share

import com.limelight.utils.MathUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCrownProfileStorePublisherTest {
    @Test
    fun buildProfilePathUsesGameNameAndBundleHash() {
        val request = publishRequest(profileName = "Apex FPS Layout", game = "Apex Legends")

        val path = GitHubCrownProfileStorePublisher.buildProfilePath(request)

        assertTrue(path.startsWith("profiles/apex-legends/apex-fps-layout-"))
        assertTrue(path.endsWith(CrownProfileShareManager.FILE_EXTENSION))
    }

    @Test
    fun appendProfileToIndexAddsStoreEntry() {
        val request = publishRequest(
            layoutBasis = CrownProfileShareManager.LayoutBasis(
                widthPx = 2400,
                heightPx = 1080,
                densityDpi = 440,
                density = 2.75f,
                orientation = "landscape"
            )
        )
        val updatedIndex = GitHubCrownProfileStorePublisher.appendProfileToIndex(
            indexText = """
                {
                  "kind": "crown-profile-index",
                  "schemaVersion": 1,
                  "profiles": []
                }
            """.trimIndent(),
            request = request,
            profilePath = "profiles/apex-legends/apex-fps-layout.crown.json",
            updatedAt = "2026-06-18T00:00:00Z"
        )

        val root = JSONObject(updatedIndex)
        val profile = root.getJSONArray("profiles").getJSONObject(0)
        assertEquals(CrownProfileShareManager.INDEX_KIND, root.getString("kind"))
        assertEquals("2026-06-18T00:00:00Z", root.getString("generatedAt"))
        assertEquals("Apex FPS Layout", profile.getString("name"))
        assertEquals("Apex Legends", profile.getString("game"))
        assertEquals("WA Crown", profile.getString("author"))
        assertEquals("../profiles/apex-legends/apex-fps-layout.crown.json", profile.getString("url"))
        assertEquals("fps", profile.getJSONArray("tags").getString(0))
        assertEquals(2400, profile.getJSONObject("layoutBasis").getInt("widthPx"))
        assertEquals(1080, profile.getJSONObject("layoutBasis").getInt("heightPx"))
        assertEquals(440, profile.getJSONObject("layoutBasis").getInt("densityDpi"))
        assertEquals("landscape", profile.getJSONObject("layoutBasis").getString("orientation"))
    }

    @Test
    fun createBundleIncludesStoreDisplayMetadata() {
        val bundle = CrownProfileShareManager.createBundle(
            profileName = "Apex FPS Layout",
            payload = validPayload(),
            metadata = CrownProfileShareManager.ExportMetadata(
                packageName = "com.limelight.test",
                appVersionCode = 390,
                appVersionName = "12.9.8"
            ),
            displayMetadata = CrownProfileShareManager.BundleDisplayMetadata(
                summary = "Fast access abilities and movement",
                authorName = "WA Crown",
                gameName = "Apex Legends",
                tags = listOf("fps", "movement")
            )
        )

        val root = JSONObject(bundle)
        assertEquals("Fast access abilities and movement", root.getString("summary"))
        assertEquals("WA Crown", root.getJSONObject("author").getString("name"))
        assertEquals("Apex Legends", root.getJSONObject("game").getString("name"))
        assertEquals("movement", root.getJSONArray("tags").getString(1))
    }

    private fun publishRequest(
        profileName: String = "Apex FPS Layout",
        game: String = "Apex Legends",
        layoutBasis: CrownProfileShareManager.LayoutBasis? = null
    ): GitHubCrownProfileStorePublisher.PublishRequest {
        val bundle = CrownProfileShareManager.createBundle(
            profileName = profileName,
            payload = validPayload(),
            metadata = CrownProfileShareManager.ExportMetadata(
                packageName = "com.limelight.test",
                appVersionCode = 390,
                appVersionName = "12.9.8",
                layoutBasis = layoutBasis
            )
        )
        return GitHubCrownProfileStorePublisher.PublishRequest(
            profileName = profileName,
            summary = "Fast access abilities and movement",
            author = "WA Crown",
            game = game,
            tags = listOf("fps", "movement"),
            bundleJson = bundle
        )
    }

    private fun validPayload(): String {
        val settings = """{"config_name":"default","touch_enable":"true"}"""
        val elements = """[{"element_id":1,"element_type":1}]"""
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
