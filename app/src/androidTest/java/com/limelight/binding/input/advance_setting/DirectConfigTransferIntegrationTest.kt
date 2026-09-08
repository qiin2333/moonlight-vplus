package com.limelight.binding.input.advance_setting

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.element.DigitalCommonButton
import com.limelight.binding.input.advance_setting.element.Element
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.utils.ConfigurationSyncManager
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectConfigTransferIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = SuperConfigDatabaseHelper(context)
    private val prefix = "transfer-${System.nanoTime()}"
    private var nextId = System.currentTimeMillis() - 100000L
    private var createdDefault = false

    private fun create(name: String, action: String? = null): Long {
        val id = nextId++
        database.insertConfig(ContentValues().apply {
            put(PageConfigController.COLUMN_LONG_CONFIG_ID, id)
            put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "$prefix-$name")
        })
        if (action != null) database.insertElement(DigitalCommonButton.getInitialInfo().apply {
            put(Element.COLUMN_LONG_CONFIG_ID, id)
            put(Element.COLUMN_LONG_ELEMENT_ID, nextId++)
            put(Element.COLUMN_STRING_ELEMENT_VALUE, action)
        })
        return id
    }

    private fun action(configId: Long): String {
        val id = database.queryAllElementIds(configId).single()
        return database.queryAllElementAttributes(configId, id)[Element.COLUMN_STRING_ELEMENT_VALUE] as String
    }

    private fun named(name: String): Long = database.queryAllConfigIds().filter {
        database.queryConfigAttribute(it, PageConfigController.COLUMN_STRING_CONFIG_NAME, "") == "$prefix-$name"
    }.single()

    @After fun cleanup() {
        database.queryAllConfigIds().filter {
            (database.queryConfigAttribute(it, PageConfigController.COLUMN_STRING_CONFIG_NAME, "") as String)
                .startsWith(prefix)
        }.forEach(database::deleteConfig)
        if (createdDefault) database.deleteConfig(0)
        database.close()
    }

    @Test fun marketImportAndMergeDisableForeignDefaultReference() {
        if (0L !in database.queryAllConfigIds()) {
            database.insertConfig(ContentValues().apply {
                put(PageConfigController.COLUMN_LONG_CONFIG_ID, 0L)
                put(PageConfigController.COLUMN_STRING_CONFIG_NAME, "default")
            })
            createdDefault = true
        }
        val source = create("source", "DCS:0")
        val payload = database.exportConfig(source)
        val before = database.queryAllConfigIds().toSet()
        assertEquals(0, database.importConfig(payload))
        val installed = database.queryAllConfigIds().filter { it !in before }.single()
        assertEquals("DCS:disabled", action(installed))
        assertEquals("DCS:0", action(source))

        val destination = create("merge")
        assertEquals(0, database.mergeConfig(payload, destination))
        assertEquals("DCS:disabled", action(destination))
    }

    @Test fun completeBackupRestoresForwardReferencesAndUpdatesExistingLocalLinks() {
        val b = create("B")
        val a = create("A", DirectConfigAction.encode(b))
        val manager = ConfigurationSyncManager(context)
        val snapshot = manager.exportSyncPackage()
        database.deleteConfig(a)
        database.deleteConfig(b)

        assertEquals(0, manager.importSyncPackage(snapshot).crownProfilesFailed)
        val firstB = named("B")
        assertNotEquals(b, firstB)
        assertEquals(DirectConfigAction.encode(firstB), action(named("A")))

        val local = create("local", DirectConfigAction.encode(firstB))
        assertEquals(0, manager.importSyncPackage(snapshot).crownProfilesFailed)
        val secondB = named("B")
        assertEquals(DirectConfigAction.encode(secondB), action(named("A")))
        assertEquals(DirectConfigAction.encode(secondB), action(local))
    }

    @Test fun sanitizingActionsCannotBypassOriginalPayloadChecksum() {
        val source = create("checksum", "DCS:0")
        val payload = JSONObject(database.exportConfig(source))
        payload.put("elements", payload.getString("elements").replace("DCS:0", "DCS:123"))
        val before = database.queryAllConfigIds().toSet()
        assertEquals(-2, database.importConfig(payload.toString()))
        assertEquals(before, database.queryAllConfigIds().toSet())
    }

    @Test fun missingProfileInBackupDisablesItsReference() {
        val b = create("B")
        val a = create("A", DirectConfigAction.encode(b))
        val manager = ConfigurationSyncManager(context)
        val snapshot = JSONObject(manager.exportSyncPackage())
        val sections = snapshot.getJSONObject("sections")
        val profiles = sections.getJSONArray("crownProfiles")
        val kept = JSONArray()
        for (index in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(index)
            if (profile.optString("name") != "$prefix-B") kept.put(profile)
        }
        sections.put("crownProfiles", kept)
        database.deleteConfig(a)
        database.deleteConfig(b)
        assertEquals(0, manager.importSyncPackage(snapshot.toString()).crownProfilesFailed)
        assertEquals("DCS:disabled", action(named("A")))
    }

    @Test fun syncedDeletionDisablesExistingLocalReference() {
        val b = create("B")
        create("A", DirectConfigAction.encode(b))
        val manager = ConfigurationSyncManager(context)
        val snapshot = JSONObject(manager.exportSyncPackage())
        val profiles = snapshot.getJSONObject("sections").getJSONArray("crownProfiles")
        for (index in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(index)
            if (profile.optString("name") == "$prefix-B") {
                profile.put("deleted", true).put("payload", "")
            }
        }
        assertEquals(0, manager.importSyncPackage(snapshot.toString()).crownProfilesFailed)
        assertFalse(b in database.queryAllConfigIds())
        assertEquals("DCS:disabled", action(named("A")))
    }
}
