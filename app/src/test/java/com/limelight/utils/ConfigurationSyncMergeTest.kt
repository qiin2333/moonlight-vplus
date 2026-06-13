package com.limelight.utils

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigurationSyncMergeTest {
    @Test
    fun mergeChoosesNewestScalarAndUnionsStringSets() {
        val deviceA = syncPackage(
            deviceId = "device-a",
            defaultValues = values(
                "list_resolution" to typedValue("string", JsonPrimitive("1280x720"), 1000L, "device-a"),
                "perf_overlay_display_items" to typedValue(
                    "stringSet",
                    stringArray("decode_latency"),
                    3000L,
                    "device-a"
                )
            )
        )
        val deviceB = syncPackage(
            deviceId = "device-b",
            defaultValues = values(
                "list_resolution" to typedValue("string", JsonPrimitive("1920x1080"), 2000L, "device-b"),
                "perf_overlay_display_items" to typedValue(
                    "stringSet",
                    stringArray("network_latency"),
                    2500L,
                    "device-b"
                )
            )
        )

        val mergedValues = preferenceValues(
            ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(deviceA, deviceB)),
            "defaultPreferences"
        )

        assertEquals("1920x1080", mergedValues["list_resolution"].asJsonObject["value"].asString)
        assertEquals(
            listOf("decode_latency", "network_latency"),
            strings(mergedValues["perf_overlay_display_items"].asJsonObject["value"].asJsonArray)
        )
        assertEquals(3000L, mergedValues["perf_overlay_display_items"].asJsonObject["updatedAt"].asLong)
        assertEquals("device-a", mergedValues["perf_overlay_display_items"].asJsonObject["updatedBy"].asString)
    }

    @Test
    fun mergeUsesDeterministicUpdatedByTieBreakForScalars() {
        val deviceA = syncPackage(
            deviceId = "device-a",
            defaultValues = values(
                "checkbox_enable_hdr" to typedValue("boolean", JsonPrimitive(false), 1000L, "device-a")
            )
        )
        val deviceB = syncPackage(
            deviceId = "device-b",
            defaultValues = values(
                "checkbox_enable_hdr" to typedValue("boolean", JsonPrimitive(true), 1000L, "device-b")
            )
        )

        val mergedValues = preferenceValues(
            ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(deviceA, deviceB)),
            "defaultPreferences"
        )
        val hdr = mergedValues["checkbox_enable_hdr"].asJsonObject

        assertTrue(hdr["value"].asBoolean)
        assertEquals("device-b", hdr["updatedBy"].asString)
    }

    @Test
    fun mergeDedupesCrownProfilesByPayloadAcrossDeviceShards() {
        val deviceA = syncPackage(
            deviceId = "device-a",
            crownProfiles = crownProfiles(
                crownProfile(1L, "A shared profile", "payload-one")
            )
        )
        val deviceB = syncPackage(
            deviceId = "device-b",
            crownProfiles = crownProfiles(
                crownProfile(2L, "B shared profile", "payload-one"),
                crownProfile(3L, "B only profile", "payload-two")
            )
        )

        val merged = ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(deviceA, deviceB, deviceB))
        val profiles = sections(merged)["crownProfiles"].asJsonArray
        val payloads = profiles.map { it.asJsonObject["payload"].asString }.toSet()

        assertEquals(2, profiles.size())
        assertEquals(setOf("payload-one", "payload-two"), payloads)
    }

    @Test
    fun mergeUpdatesCrownProfilesByStableProfileId() {
        val deviceA = syncPackage(
            deviceId = "device-a",
            crownProfiles = crownProfiles(
                crownProfile(
                    sourceConfigId = 1L,
                    name = "Shared profile",
                    payload = "old-payload",
                    profileId = "profile-shared",
                    updatedAt = 1000L,
                    updatedBy = "device-a"
                )
            )
        )
        val deviceB = syncPackage(
            deviceId = "device-b",
            crownProfiles = crownProfiles(
                crownProfile(
                    sourceConfigId = 2L,
                    name = "Shared profile",
                    payload = "new-payload",
                    profileId = "profile-shared",
                    updatedAt = 2000L,
                    updatedBy = "device-b"
                )
            )
        )

        val profiles = sections(
            ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(deviceA, deviceB))
        )["crownProfiles"].asJsonArray
        val profile = profiles[0].asJsonObject

        assertEquals(1, profiles.size())
        assertEquals("profile-shared", profile["profileId"].asString)
        assertEquals("new-payload", profile["payload"].asString)
        assertEquals(2000L, profile["updatedAt"].asLong)
        assertEquals("device-b", profile["updatedBy"].asString)
    }

    @Test
    fun mergePropagatesCrownProfileDeletionByStableProfileId() {
        val deviceA = syncPackage(
            deviceId = "device-a",
            crownProfiles = crownProfiles(
                crownProfile(
                    sourceConfigId = 1L,
                    name = "Shared profile",
                    payload = "old-payload",
                    profileId = "profile-shared",
                    updatedAt = 1000L,
                    updatedBy = "device-a"
                )
            )
        )
        val deviceB = syncPackage(
            deviceId = "device-b",
            crownProfiles = crownProfiles(
                crownProfile(
                    sourceConfigId = 0L,
                    name = "Shared profile",
                    payload = "",
                    profileId = "profile-shared",
                    updatedAt = 2000L,
                    updatedBy = "device-b",
                    deleted = true
                )
            )
        )

        val profiles = sections(
            ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(deviceA, deviceB))
        )["crownProfiles"].asJsonArray
        val profile = profiles[0].asJsonObject

        assertEquals(1, profiles.size())
        assertEquals("profile-shared", profile["profileId"].asString)
        assertTrue(profile["deleted"].asBoolean)
        assertEquals("", profile["payload"].asString)
        assertEquals(2000L, profile["updatedAt"].asLong)
        assertEquals("device-b", profile["updatedBy"].asString)
    }

    @Test
    fun mergeFiltersNonPortableDefaultPreferenceKeys() {
        val deviceA = syncPackage(
            deviceId = "device-a",
            defaultValues = values(
                "config_sync_last_background_sync_error" to typedValue(
                    "string",
                    JsonPrimitive("should stay local"),
                    1000L,
                    "device-a"
                ),
                "list_fps" to typedValue("string", JsonPrimitive("60"), 1000L, "device-a")
            )
        )

        val mergedValues = preferenceValues(
            ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(deviceA)),
            "defaultPreferences"
        )

        assertEquals("60", mergedValues["list_fps"].asJsonObject["value"].asString)
        assertFalse(mergedValues.has("config_sync_last_background_sync_error"))
    }

    @Test
    fun mergePropagatesNewerDeletionTombstone() {
        val deviceA = syncPackage(
            deviceId = "device-a",
            defaultValues = values(
                "list_resolution" to typedValue("string", JsonPrimitive("1920x1080"), 1000L, "device-a")
            )
        )
        val deviceB = syncPackage(
            deviceId = "device-b",
            defaultValues = values(
                "list_resolution" to deletedValue(2000L, "device-b")
            )
        )

        val mergedValues = preferenceValues(
            ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(deviceA, deviceB)),
            "defaultPreferences"
        )
        val resolution = mergedValues["list_resolution"].asJsonObject

        assertEquals("deleted", resolution["type"].asString)
        assertTrue(resolution["value"].isJsonNull)
        assertEquals(2000L, resolution["updatedAt"].asLong)
        assertEquals("device-b", resolution["updatedBy"].asString)
    }

    @Test
    fun mergeKeepsNewerValueOverOlderDeletionTombstone() {
        val deviceA = syncPackage(
            deviceId = "device-a",
            defaultValues = values(
                "list_resolution" to deletedValue(1000L, "device-a")
            )
        )
        val deviceB = syncPackage(
            deviceId = "device-b",
            defaultValues = values(
                "list_resolution" to typedValue("string", JsonPrimitive("1920x1080"), 2000L, "device-b")
            )
        )

        val mergedValues = preferenceValues(
            ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(deviceA, deviceB)),
            "defaultPreferences"
        )
        val resolution = mergedValues["list_resolution"].asJsonObject

        assertEquals("string", resolution["type"].asString)
        assertEquals("1920x1080", resolution["value"].asString)
        assertEquals(2000L, resolution["updatedAt"].asLong)
        assertEquals("device-b", resolution["updatedBy"].asString)
    }

    @Test
    fun mergeChoosesNewerPairingSnapshotAsWhole() {
        val older = syncPackage(
            deviceId = "device-a",
            pairingState = pairingState(
                updatedAt = 1000L,
                uniqueId = "aaaaaaaaaaaaaaaa",
                computerUuid = "old-host"
            )
        )
        val newer = syncPackage(
            deviceId = "device-a",
            pairingState = pairingState(
                updatedAt = 2000L,
                uniqueId = "bbbbbbbbbbbbbbbb",
                computerUuid = "new-host"
            )
        )

        val pairing = sections(
            ConfigurationSyncManager.mergeSyncPackagesForTest(listOf(older, newer))
        )["pairing"].asJsonObject
        val computers = pairing["computers"].asJsonArray

        assertEquals(2000L, pairing["updatedAt"].asLong)
        assertEquals("bbbbbbbbbbbbbbbb", pairing["uniqueId"].asString)
        assertEquals(1, computers.size())
        assertEquals("new-host", computers[0].asJsonObject["uuid"].asString)
    }

    @Test
    fun importedTombstoneKeysStayTrackedForFutureExports() {
        val trackedKeys = ConfigurationSyncManager.trackedKeysAfterImportForTest(
            existingKeys = setOf("list_fps"),
            importedKey = "list_resolution"
        )

        assertEquals(setOf("list_fps", "list_resolution"), trackedKeys)
    }

    @Test
    fun encryptedSyncPackageRoundTripsWithoutLeakingPairingPrivateKey() {
        val pairing = pairingState(
            updatedAt = 2000L,
            uniqueId = "aaaaaaaaaaaaaaaa",
            computerUuid = "encrypted-host"
        ).apply {
            addProperty("clientCertificatePem", "client-cert-plain-text")
            addProperty("clientPrivateKeyPkcs8", "client-key-plain-text")
        }
        val plainPackage = syncPackage(deviceId = "device-a", pairingState = pairing)

        val encryptedPackage = ConfigurationSyncManager.encryptSyncPackageForTest(
            plainPackage,
            "correct horse battery staple"
        )

        assertTrue(ConfigurationSyncManager.isEncryptedSyncPackage(encryptedPackage))
        assertFalse(encryptedPackage.contains("client-key-plain-text"))
        assertFalse(encryptedPackage.contains("client-cert-plain-text"))
        assertEquals(
            plainPackage,
            ConfigurationSyncManager.decryptSyncPackageForTest(encryptedPackage, "correct horse battery staple")
        )
    }

    @Test
    fun encryptedSyncPackageRejectsWrongPassword() {
        val encryptedPackage = ConfigurationSyncManager.encryptSyncPackageForTest(
            syncPackage(deviceId = "device-a"),
            "right-password"
        )

        try {
            ConfigurationSyncManager.decryptSyncPackageForTest(encryptedPackage, "wrong-password")
            fail("Expected encrypted backup decryption to reject the wrong password")
        } catch (_: Exception) {
            // Expected.
        }
    }

    @Test
    fun externalSnapshotRewriteMigratesPlaintextBackupEvenWhenContentMatches() {
        assertTrue(
            ConfigurationSyncManager.shouldWriteExternalSnapshotForTest(
                mergedHash = "same-content",
                externalHash = "same-content",
                externalRequiresEncryptionRewrite = true
            )
        )
        assertTrue(
            ConfigurationSyncManager.shouldWriteExternalSnapshotForTest(
                mergedHash = "new-content",
                externalHash = "old-content",
                externalRequiresEncryptionRewrite = false
            )
        )
        assertFalse(
            ConfigurationSyncManager.shouldWriteExternalSnapshotForTest(
                mergedHash = "same-content",
                externalHash = "same-content",
                externalRequiresEncryptionRewrite = false
            )
        )
    }

    private fun syncPackage(
        deviceId: String,
        defaultValues: JsonObject = JsonObject(),
        crownProfiles: JsonArray = JsonArray(),
        pairingState: JsonObject = pairingState()
    ): String {
        val root = JsonObject()
        root.addProperty("schemaVersion", 2)
        root.addProperty("syncId", "$deviceId-sync")
        root.addProperty("deviceId", deviceId)
        root.addProperty("backupDeviceKey", "same-device-backup-key")
        root.addProperty("backupTarget", "sameDevice")
        root.addProperty("packageName", "com.limelight.test")
        root.addProperty("appVersionCode", 1L)
        root.addProperty("appVersionName", "test")
        root.addProperty("exportedAt", 1781010000000L)

        val packageSections = JsonObject()
        packageSections.add("defaultPreferences", preferenceSection(defaultValues))
        packageSections.add("appLastSettings", preferenceSection())
        packageSections.add("customResolutions", preferenceSection())
        packageSections.add("sceneConfigs", preferenceSection())
        packageSections.add("appViewPreferences", preferenceSection())
        packageSections.add("hiddenApps", preferenceSection())
        packageSections.add("crownProfiles", crownProfiles)
        packageSections.add("pairing", pairingState)
        root.add("sections", packageSections)
        return root.toString()
    }

    private fun pairingState(
        updatedAt: Long = 0L,
        uniqueId: String = "",
        computerUuid: String? = null
    ): JsonObject {
        val pairing = JsonObject()
        pairing.addProperty("updatedAt", updatedAt)
        if (uniqueId.isNotBlank()) {
            pairing.addProperty("uniqueId", uniqueId)
        }
        pairing.add("computers", JsonArray().apply {
            if (computerUuid != null) {
                add(pairingComputer(computerUuid))
            }
        })
        return pairing
    }

    private fun pairingComputer(uuid: String): JsonObject {
        val computer = JsonObject()
        computer.addProperty("uuid", uuid)
        computer.addProperty("name", "Gaming PC")
        computer.add("localAddress", pairingAddress("192.168.1.10", 47989))
        computer.add("remoteAddress", null)
        computer.add("manualAddress", null)
        computer.add("ipv6Address", null)
        computer.addProperty("ipv6Disabled", false)
        computer.add("activeAddress", pairingAddress("192.168.1.10", 47989))
        computer.addProperty("httpsPort", 47984)
        computer.addProperty("macAddress", "00:11:22:33:44:55")
        computer.addProperty("pairName", "Moonlight V+")
        computer.addProperty("serverCertificateDer", "c2VydmVyLWNlcnQ=")
        return computer
    }

    private fun pairingAddress(address: String, port: Int): JsonObject {
        val encoded = JsonObject()
        encoded.addProperty("address", address)
        encoded.addProperty("port", port)
        return encoded
    }

    private fun preferenceSection(values: JsonObject = JsonObject()): JsonObject {
        val section = JsonObject()
        section.add("values", values)
        return section
    }

    private fun values(vararg entries: Pair<String, JsonObject>): JsonObject {
        val values = JsonObject()
        for ((key, value) in entries) {
            values.add(key, value)
        }
        return values
    }

    private fun typedValue(
        type: String,
        value: JsonElement,
        updatedAt: Long,
        updatedBy: String
    ): JsonObject {
        val encoded = JsonObject()
        encoded.addProperty("type", type)
        encoded.add("value", value)
        encoded.addProperty("updatedAt", updatedAt)
        encoded.addProperty("updatedBy", updatedBy)
        return encoded
    }

    private fun deletedValue(updatedAt: Long, updatedBy: String): JsonObject {
        val encoded = JsonObject()
        encoded.addProperty("type", "deleted")
        encoded.add("value", null)
        encoded.addProperty("updatedAt", updatedAt)
        encoded.addProperty("updatedBy", updatedBy)
        return encoded
    }

    private fun stringArray(vararg values: String): JsonArray {
        val array = JsonArray()
        for (value in values) {
            array.add(value)
        }
        return array
    }

    private fun crownProfiles(vararg profiles: JsonObject): JsonArray {
        val array = JsonArray()
        for (profile in profiles) {
            array.add(profile)
        }
        return array
    }

    private fun crownProfile(
        sourceConfigId: Long,
        name: String,
        payload: String,
        profileId: String? = null,
        updatedAt: Long? = null,
        updatedBy: String? = null,
        deleted: Boolean = false
    ): JsonObject {
        val profile = JsonObject()
        profile.addProperty("sourceConfigId", sourceConfigId)
        profile.addProperty("name", name)
        profile.addProperty("payload", payload)
        if (profileId != null) {
            profile.addProperty("profileId", profileId)
        }
        if (updatedAt != null) {
            profile.addProperty("updatedAt", updatedAt)
        }
        if (updatedBy != null) {
            profile.addProperty("updatedBy", updatedBy)
        }
        if (deleted) {
            profile.addProperty("deleted", true)
        }
        return profile
    }

    private fun preferenceValues(syncPackage: String, sectionName: String): JsonObject {
        return sections(syncPackage)[sectionName].asJsonObject["values"].asJsonObject
    }

    private fun sections(syncPackage: String): JsonObject {
        return JsonParser.parseString(syncPackage)
            .asJsonObject["sections"]
            .asJsonObject
    }

    private fun strings(array: JsonArray): List<String> {
        return array.map { it.asString }
    }
}
