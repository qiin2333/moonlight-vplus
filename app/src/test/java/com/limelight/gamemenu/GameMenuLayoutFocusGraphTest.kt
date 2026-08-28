package com.limelight.gamemenu

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMenuLayoutFocusGraphTest {
    @Test
    fun touchModeWideLayoutUsesOneToTwoPaneRatio() {
        assertEquals(1f, TOUCH_MODE_PRIMARY_PANE_WEIGHT)
        assertEquals(2f, TOUCH_MODE_SECONDARY_PANE_WEIGHT)
    }

    @Test
    fun touchModeSplitPanesKeepVerticalOrderAndMapAcrossColumns() {
        assertEquals(
            TouchModeFocusTargets(left = null, right = 8, up = 3, down = null),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 4)
        )
        assertEquals(
            TouchModeFocusTargets(left = 1, right = null, up = 5, down = 7),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 6)
        )
        assertEquals(
            TouchModeFocusTargets(left = 3, right = null, up = 7, down = null),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 4, globalIndex = 8)
        )
    }

    @Test
    fun touchPointerSensitivityParticipatesInTheSecondaryFocusColumn() {
        assertEquals(
            TouchModeFocusTargets(left = 0, right = null, up = null, down = 6),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 5, globalIndex = 5)
        )
        assertEquals(
            TouchModeFocusTargets(left = 1, right = null, up = 5, down = 7),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 5, globalIndex = 6)
        )
        assertEquals(
            TouchModeFocusTargets(left = null, right = 9, up = 3, down = null),
            touchModeFocusTargets(primaryCount = 5, compatibleCount = 5, globalIndex = 4)
        )
    }

    @Test
    fun dialogShellUsesLargestHorizontalSafeInsetOnBothSides() {
        assertEquals(18.dp, symmetricHorizontalPadding(12.dp, 18.dp))
        assertEquals(18.dp, symmetricHorizontalPadding(18.dp, 12.dp))
    }

    @Test
    fun childDialogOptionsAlwaysKeepParentMenuOpen() {
        assertTrue(gameMenuChildDialogOption("Add key", Runnable {}).isKeepDialog)
    }

    @Test
    fun fiveSegmentControlKeepsOneHorizontalFocusRow() {
        val middle = segmentedFocusTargets(itemCount = 5, index = 2, columnCount = 5)
        assertEquals(1, middle.left)
        assertEquals(3, middle.right)
        assertNull(middle.up)
        assertNull(middle.down)

        assertEquals(
            SegmentedFocusTargets(left = 3, right = null, up = null, down = null),
            segmentedFocusTargets(itemCount = 5, index = 4, columnCount = 5)
        )
    }

    @Test
    fun touchModeSegmentsUseTwoRowsOnPhonesAndOneRowOnLargeScreens() {
        assertEquals(
            3,
            responsiveSegmentColumnCount(
                itemCount = 5,
                smallestScreenWidthDp = 411,
                smallScreenColumnCount = 3
            )
        )
        assertEquals(2, segmentedRowCount(itemCount = 5, columnCount = 3))
        assertEquals(
            SegmentedFocusTargets(left = 0, right = 2, up = null, down = 4),
            segmentedFocusTargets(itemCount = 5, index = 1, columnCount = 3)
        )
        assertEquals(
            SegmentedFocusTargets(left = 3, right = null, up = 1, down = null),
            segmentedFocusTargets(itemCount = 5, index = 4, columnCount = 3)
        )
        assertEquals(
            5,
            responsiveSegmentColumnCount(
                itemCount = 5,
                smallestScreenWidthDp = LARGE_SCREEN_MIN_SMALLEST_WIDTH_DP,
                smallScreenColumnCount = 3
            )
        )
    }

    @Test
    fun localizedTouchModeShortLabelsFitTheFiveSegmentBudget() {
        val resourceRoot = listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull(File::isDirectory)
            ?: error("Unable to locate app/src/main/res")
        val requiredShortLabelNames = setOf(
            "enhanced",
            "classic",
            "trackpad",
            "native_mouse",
            "ds5"
        )
        val shortLabelPattern = Regex(
            """<string name="game_menu_touch_mode_(enhanced|classic|trackpad|native_mouse|ds5)_short">([^<]+)</string>"""
        )

        listOf("values", "values-es", "values-pt", "values-zh-rCN").forEach { directory ->
            val stringsFile = File(resourceRoot, "$directory/strings.xml")
            val labelsByName = shortLabelPattern.findAll(stringsFile.readText())
                .associate { match -> match.groupValues[1] to match.groupValues[2].trim() }
            assertEquals(
                "$directory must define exactly the required touch-mode short labels",
                requiredShortLabelNames,
                labelsByName.keys
            )
            labelsByName.forEach { (name, label) ->
                assertTrue(
                    "$directory label '$name' ('$label') exceeds the five-code-point single-row budget",
                    label.codePointCount(0, label.length) <= 5
                )
            }
        }
    }
}
