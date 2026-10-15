package com.limelight

import org.junit.Assert.*
import org.junit.Test

class UsbAutoSharePolicyTest {
    private val pad = UsbAutoShareIdentity(0x054c, 0x0ce6, "hid-signature", "pad-A")

    @Test fun serialRuleRequiresPermissionAndExactDevice() {
        val session = UsbAutoShareSession()
        assertTrue(session.allows(pad, listOf(pad), 1))
        assertFalse(session.allows(pad.copy(serial = null), listOf(pad), 1))
        assertFalse(session.allows(pad.copy(serial = "pad-B"), listOf(pad), 1))
        assertFalse(session.allows(pad.copy(signature = "other-interfaces"), listOf(pad), 1))
        assertFalse(session.allows(pad.copy(productId = 1), listOf(pad), 1))
    }

    @Test fun serialLessRuleMatchesTypeButNeverAmbiguousAndroidInputGroup() {
        val rule = pad.copy(serial = null)
        val session = UsbAutoShareSession()
        assertTrue(session.allows(rule, listOf(rule), 1))
        assertTrue(session.allows(pad, listOf(rule), 1))
        assertFalse(session.allows(pad, listOf(rule), 2))
        assertFalse(session.allows(pad, listOf(rule), 0))
    }

    @Test fun manualStopSurvivesRecreationAndOnlySuppressesItsInputGroup() {
        val session = UsbAutoShareSession()
        session.suppressedGroups.add(pad.inputGroup)
        val restored = UsbAutoShareSession(session.suppressedGroups.toList())
        assertFalse(restored.allows(pad, listOf(pad), 1))
        val mouse = pad.copy(vendorId = 123, productId = 456)
        assertTrue(restored.allows(mouse, listOf(pad, mouse), 1))
        restored.suppressedGroups.remove(pad.inputGroup) // Manual Share resumes auto eligibility.
        assertTrue(restored.allows(pad, listOf(pad), 1))
        assertTrue(UsbAutoShareSession().allows(pad, listOf(pad), 1)) // Next game.
    }

    @Test fun hostRulesAreOptInAndCanContainSeveralDevices() {
        val session = UsbAutoShareSession()
        val otherHostRules = emptySet<UsbAutoShareIdentity>()
        assertFalse(session.allows(pad, otherHostRules, 1))
        val mouse = pad.copy(vendorId = 123, productId = 456)
        val rules = setOf(pad, mouse)
        assertTrue(session.allows(pad, rules, 1))
        assertTrue(session.allows(mouse, rules, 1))
        assertFalse(session.allows(pad, rules - pad, 1))
    }

    @Test fun persistenceRoundTripsSpecialCharactersAndRejectsUnknownVersions() {
        val id = pad.copy(serial = "serial\"\\\nΩ")
        assertEquals(id, UsbAutoShareIdentity.decode(id.encode()))
        val type = pad.copy(serial = null)
        assertEquals(type, UsbAutoShareIdentity.decode(type.encode()))
        assertNull(UsbAutoShareIdentity.decode("broken"))
        assertNull(UsbAutoShareIdentity.decode(pad.encode().replace("\"version\":1", "\"version\":2")))
    }

    @Test fun persistentRulesRemainSeparateForTwoComputerUuidsAndCanBeRemoved() {
        val values = mutableMapOf<String, Set<String>>()
        fun host(uuid: String) = UsbAutoShareRules(uuid,
            { values[it].orEmpty() }, { key, rules -> values[key] = rules })
        val a = host("host-A")
        val b = host("host-B")
        assertTrue(a.load().isEmpty())
        val mouse = pad.copy(vendorId = 123, productId = 456, serial = null)
        a.save(setOf(pad, mouse))
        assertEquals(setOf(pad, mouse), host("host-A").load())
        assertTrue(b.load().isEmpty())
        b.save(setOf(mouse))
        a.save(a.load() - pad)
        assertEquals(setOf(mouse), a.load())
        assertEquals(setOf(mouse), b.load())
        a.save(emptySet())
        assertTrue(a.load().isEmpty())
        assertEquals(setOf(mouse), b.load())
    }

    @Test fun corruptedPreferenceDoesNotAuthorizeAnyDeviceOrHideOtherRules() {
        val store = UsbAutoShareRules("host-A", { setOf("bad", pad.encode()) }, { _, _ -> })
        assertEquals(setOf(pad), store.load())
        assertEquals(emptySet<UsbAutoShareIdentity>(),
            UsbAutoShareRules("host-A", { setOf("bad") }, { _, _ -> }).load())
    }
}
