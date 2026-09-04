package com.limelight.binding.input.advance_setting

import org.junit.Assert.*
import org.junit.Test

class DirectConfigActionTest {
    @Test fun targetUsesStableIdIncludingDefaultConfig() {
        for (id in listOf(0L, 123L, Long.MAX_VALUE)) {
            assertEquals(id, DirectConfigAction.parse(DirectConfigAction.encode(id)))
        }
    }

    @Test fun malformedValuesAndExistingActionsAreNotDirectActions() {
        for (value in listOf(null, "", "CSW", "k27", "DCS:", "DCS:-1", "DCS:+1",
            "DCS: 1", "DCS:1x", "DCS:9223372036854775808")) {
            assertNull(value, DirectConfigAction.parse(value))
        }
    }

    @Test fun onePressChoosesOnlyFirstTarget() {
        val state = DirectConfigSwitchState()
        state.begin("button")
        state.request("button", 1)
        state.begin("button") // Duplicate DOWN cannot reset the press.
        state.request("button", 2)
        state.finish("button", false)
        state.finish("button", false)
        assertEquals(1L, state.takeCompletedTarget())
        assertNull(state.takeCompletedTarget())
    }

    @Test fun cancelOrUnmatchedReleaseCannotSwitch() {
        val state = DirectConfigSwitchState()
        state.request("button", 1)
        state.finish("button", false)
        assertNull(state.takeCompletedTarget())
        state.begin("button")
        state.request("button", 1)
        state.finish("button", true)
        assertNull(state.takeCompletedTarget())
    }

    @Test fun unfinishedPressCannotCommitAndFirstCompletedPressWins() {
        val state = DirectConfigSwitchState()
        state.begin("held")
        state.request("held", 1)
        assertNull(state.takeCompletedTarget())
        state.begin("first")
        state.begin("second")
        state.request("first", 1)
        state.request("second", 2)
        state.finish("second", false)
        state.finish("first", false)
        assertEquals(2L, state.takeCompletedTarget())
    }

    @Test fun streamCancelAndConfigReloadDiscardQueuedSwitch() {
        val state = DirectConfigSwitchState()
        state.begin("button")
        state.request("button", 1)
        state.finish("button", false)
        state.reset()
        assertNull(state.takeCompletedTarget())
    }

    @Test fun independentClicksCanEachSwitchWithoutToggleHistory() {
        val state = DirectConfigSwitchState()
        repeat(2) {
            state.begin("button")
            state.request("button", 1)
            state.finish("button", false)
            assertEquals(1L, state.takeCompletedTarget())
        }
    }
}
