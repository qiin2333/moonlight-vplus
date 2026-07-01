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
    fun validateProfileNotAlreadyPublishedAcceptsNewProfile() {
        val request = publishRequest(
            layoutBasis = CrownProfileShareManager.LayoutBasis(
                widthPx = 2400,
                heightPx = 1080,
                densityDpi = 440,
                density = 2.75f,
                orientation = "landscape"
            )
        )
        GitHubCrownProfileStorePublisher.validateProfileNotAlreadyPublished(
            indexText = """
                {
                  "kind": "crown-profile-index",
                  "schemaVersion": 1,
                  "profiles": []
                }
            """.trimIndent(),
            request = request,
            profilePath = "profiles/apex-legends/apex-fps-layout.crown.json"
        )
    }

    @Test(expected = GitHubCrownProfileStorePublisher.GitHubCrownStoreException::class)
    fun validateProfileNotAlreadyPublishedRejectsExistingBundle() {
        val request = publishRequest()
        val bundleId = JSONObject(request.bundleJson).getString("bundleId")

        GitHubCrownProfileStorePublisher.validateProfileNotAlreadyPublished(
            indexText = """
                {
                  "kind": "crown-profile-index",
                  "schemaVersion": 1,
                  "profiles": [
                    {
                      "bundleId": "$bundleId",
                      "url": "../profiles/other/other.crown.json"
                    }
                  ]
                }
            """.trimIndent(),
            request = request,
            profilePath = "profiles/apex-legends/apex-fps-layout.crown.json"
        )
    }

    @Test(expected = GitHubCrownProfileStorePublisher.GitHubCrownStoreException::class)
    fun validateProfileNotAlreadyPublishedRejectsExistingPath() {
        val request = publishRequest()

        GitHubCrownProfileStorePublisher.validateProfileNotAlreadyPublished(
            indexText = """
                {
                  "kind": "crown-profile-index",
                  "schemaVersion": 1,
                  "profiles": [
                    {
                      "bundleId": "crown.other.profile",
                      "url": "../profiles/apex-legends/apex-fps-layout.crown.json"
                    }
                  ]
                }
            """.trimIndent(),
            request = request,
            profilePath = "profiles/apex-legends/apex-fps-layout.crown.json"
        )
    }

    @Test(expected = GitHubCrownProfileStorePublisher.GitHubCrownStoreException::class)
    fun validateProfileNotAlreadyPublishedRejectsMissingProfiles() {
        GitHubCrownProfileStorePublisher.validateProfileNotAlreadyPublished(
            indexText = """
                {
                  "kind": "crown-profile-index",
                  "schemaVersion": 1
                }
            """.trimIndent(),
            request = publishRequest(),
            profilePath = "profiles/apex-legends/apex-fps-layout.crown.json"
        )
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
