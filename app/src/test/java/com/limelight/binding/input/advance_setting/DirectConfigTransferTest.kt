package com.limelight.binding.input.advance_setting

import com.limelight.utils.MathUtils
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectConfigTransferTest {
    private fun payload(value: String): String {
        val settings = "{\"config_id\":10}"
        val elements = JSONArray().put(JSONObject().put("element_value", value)
            .put("element_text", "DCS:0")).toString()
        return JSONObject().put("version", 1).put("settings", settings).put("elements", elements)
            .put("md5", MathUtils.computeMD5("1$settings$elements")).toString()
    }

    @Test fun standaloneImportNeverTrustsForeignNumericOrPortableIds() {
        assertEquals("DCS:disabled", DirectConfigTransfer.forImport("DCS:0", false))
        assertEquals("DCS:disabled", DirectConfigTransfer.forImport("DCS:p:other", false))
        assertEquals("DCS:disabled", DirectConfigTransfer.forImport("DCS:0", true))
    }

    @Test fun wheelCombinationsPreserveOtherKeysGroupsAndLabels() {
        assertEquals("k111+DCS:disabled|DCS:42,gb9|Group",
            DirectConfigTransfer.forImport("k111+DCS:0|DCS:42,gb9|Group", false))
        assertEquals("CSW", DirectConfigTransfer.forImport("CSW", false))
    }

    @Test fun oldPayloadWithoutDirectActionsIsByteIdentical() {
        val original = payload("k111")
        assertEquals(original, DirectConfigTransfer.forBackup(original, emptyMap()))
    }

    @Test fun backupUsesExistingStableIdentityAndUpdatesChecksum() {
        val exported = JSONObject(DirectConfigTransfer.forBackup(payload("DCS:0"), mapOf(0L to "profile-B")))
        val element = JSONArray(exported.getString("elements")).getJSONObject(0)
        assertEquals("DCS:p:profile-B", element.getString("element_value"))
        assertEquals("DCS:0", element.getString("element_text"))
        assertEquals(MathUtils.computeMD5("1${exported.getString("settings")}${exported.getString("elements")}"),
            exported.getString("md5"))
        assertEquals("DCS:999", DirectConfigTransfer.restoreValue(
            DirectConfigTransfer.forImport(element.getString("element_value"), true),
            emptyMap(), mapOf("profile-B" to 999L)))
    }

    @Test fun missingTargetNeverFallsBackToRecipientDefaultConfig() {
        assertEquals("DCS:disabled", DirectConfigTransfer.restoreValue(
            "DCS:p:missing", emptyMap(), mapOf("recipient-default" to 0L)))
        val exported = JSONObject(DirectConfigTransfer.forBackup(payload("DCS:999"), emptyMap()))
        assertEquals("DCS:disabled", JSONArray(exported.getString("elements"))
            .getJSONObject(0).getString("element_value"))
    }

    @Test fun replacementAndDeletionOnlyChangeKnownLocalTargetsOnce() {
        assertEquals("DCS:20+DCS:disabled+DCS:50|Label", DirectConfigTransfer.restoreValue(
            "DCS:10+DCS:30+DCS:50|Label", mapOf(10L to 20L, 20L to 40L, 30L to null), emptyMap()))
    }

    @Test fun portableIdentityEscapesWheelSeparators() {
        val identity = "profile +,|中文"
        val exported = JSONObject(DirectConfigTransfer.forBackup(payload("k111+DCS:0|Label"), mapOf(0L to identity)))
        val value = JSONArray(exported.getString("elements")).getJSONObject(0).getString("element_value")
        assertEquals("k111+DCS:91|Label", DirectConfigTransfer.restoreValue(value, emptyMap(), mapOf(identity to 91L)))
    }
}
