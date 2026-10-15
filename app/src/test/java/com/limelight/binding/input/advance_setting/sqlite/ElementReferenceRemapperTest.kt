package com.limelight.binding.input.advance_setting.sqlite

import org.junit.Assert.assertEquals
import org.junit.Test

class ElementReferenceRemapperTest {
    @Test
    fun missingOrEmptyGroupValuesHaveNoChildren() {
        for (value in listOf(null, "", "-1", ",,,")) {
            assertEquals("-1", ElementReferenceRemapper.group(value) { error("No child ID should be resolved") })
        }
    }

    @Test
    fun invalidGroupIdsAreSkippedWhileValidChildrenKeepTheirOrder() {
        val replacements = mapOf(100L to 1000L, 200L to 2000L)
        assertEquals("-1,2000,1000,2000", ElementReferenceRemapper.group(
            "-1,,bad,200,100,9223372036854775808,999,200,", replacements::get))
    }

    @Test
    fun sentinelNeverResolvesEvenIfAnElementHasThatId() {
        assertEquals("-1,1000", ElementReferenceRemapper.group("-1,100") {
            if (it == -1L) error("Sentinel must not become a child") else 1000L
        })
    }

    @Test
    fun wheelReferencesUseNewGroupIdsAndPreserveLabelsAndOtherActions() {
        val replacements = mapOf(200L to 2000L, 300L to 3000L)
        assertEquals("gb2000|Group|extra,66|Key,gb3000,DCS:42|Profile,,",
            ElementReferenceRemapper.wheel("gb200|Group|extra,66|Key,gb300,DCS:42|Profile,,", replacements::get))
    }

    @Test
    fun unresolvedAndMalformedWheelSegmentsRemainUnchanged() {
        val value = "gb999|Missing,gbbad|Invalid,gb9223372036854775808,gb|Empty,gb200+k65|Combo"
        assertEquals(value, ElementReferenceRemapper.wheel(value) { null })
        assertEquals("", ElementReferenceRemapper.wheel("") { error("No group ID should be resolved") })
    }
}
