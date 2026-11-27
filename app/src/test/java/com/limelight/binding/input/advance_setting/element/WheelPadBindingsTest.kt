package com.limelight.binding.input.advance_setting.element

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelPadBindingsTest {
    @Test
    fun emptyAndTrailingSlotsRetainTheirBindings() {
        val loaded = WheelPadBindings.load("k51,,k47,", null, 4)
        assertEquals(listOf("k51", "", "k47", ""), loaded.values)
        assertEquals(listOf("", "", "", ""), loaded.names)
    }

    @Test
    fun legacyInlineNamesKeepTheirSectorAndPipeCharacters() {
        val loaded = WheelPadBindings.load("k51|Up,gb42|Group|extra,,k47", null, 4)
        assertEquals(listOf("k51", "gb42", "", "k47"), loaded.values)
        assertEquals(listOf("Up", "Group|extra", "", ""), loaded.names)
    }

    @Test
    fun separateNamesPreserveCommasAndOverrideLegacyNames() {
        val loaded = WheelPadBindings.load("k51|Old,,gb42", listOf("Up,", "", "Group|one,two"), 3)
        assertEquals(listOf("k51", "", "gb42"), loaded.values)
        assertEquals(listOf("Up,", "", "Group|one,two"), loaded.names)
    }

    @Test
    fun missingValuesAndShortNameArraysArePaddedIndependently() {
        val loaded = WheelPadBindings.load("k51", listOf("First", "Second"), 4)
        assertEquals(listOf("k51", "null", "null", "null"), loaded.values)
        assertEquals(listOf("First", "Second", "", ""), loaded.names)
        assertEquals(listOf("null", "null"), WheelPadBindings.load(null, null, 2).values)
    }

    @Test
    fun excessSavedSlotsCannotBreakCountReductionOrReappearOnExpansion() {
        val loaded = WheelPadBindings.load("k51|Up,k32|Right,k47|Down,k29|Left,k30,k31,k32", null, 4)
        assertEquals(listOf("k51", "k32", "k47", "k29"), loaded.values)
        assertEquals(listOf("Up", "Right", "Down", "Left"), loaded.names)
        WheelPadBindings.resize(loaded.values, loaded.names, 2)
        assertEquals(listOf("k51", "k32"), loaded.values)
        assertEquals(listOf("Up", "Right"), loaded.names)
        WheelPadBindings.resize(loaded.values, loaded.names, 3)
        assertEquals(listOf("k51", "k32", "null"), loaded.values)
        assertEquals(listOf("Up", "Right", ""), loaded.names)
    }

    @Test
    fun resizingUnequalListsPreservesExistingSlotsWithoutUnderflow() {
        val values = mutableListOf("k51", "k32", "k47", "k29", "k30")
        val names = mutableListOf("First")
        WheelPadBindings.resize(values, names, 2)
        assertEquals(listOf("k51", "k32"), values)
        assertEquals(listOf("First", ""), names)
    }
}
