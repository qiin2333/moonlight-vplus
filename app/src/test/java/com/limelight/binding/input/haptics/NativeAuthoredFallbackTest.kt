package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class NativeAuthoredFallbackTest {
    @Test fun loadingDoesNotFailOverButPermanentFailureSelectsTheNextBackend() {
        for (failsWhileLoading in listOf(true, false)) {
            val calls = mutableListOf<String>()
            class Sink(val name: String) : NativeAuthoredHaptics {
                override var ready = true
                override var failed = false
                override fun render(body: Float, point: Float, keepWarm: Boolean): Long? {
                    calls += "$name:render"
                    return null
                }
                override fun stop() { calls += "$name:stop" }
                override fun close() { calls += "$name:close" }
            }
            val preferred = Sink("preferred")
            val fallback = Sink("fallback")
            val sink = NativeAuthoredFallback(preferred, fallback)
            if (failsWhileLoading) {
                preferred.ready = false
                assertFalse(sink.ready)
                assertFalse(sink.failed)
                sink.render(0f, .2f, true)
                assertTrue(calls.isEmpty())
            } else sink.render(.1f, .2f, true)
            preferred.failed = true
            preferred.ready = false
            assertTrue(sink.ready)
            sink.render(.1f, .2f, true)
            assertTrue(calls.indexOf("preferred:close") < calls.indexOf("fallback:render"))
            fallback.failed = true
            fallback.ready = false
            assertFalse(sink.ready)
            assertTrue(sink.failed)
            sink.close(); sink.close()
            val countAfterClose = calls.size
            sink.stop()
            assertEquals(countAfterClose, calls.size)
            assertEquals(1, calls.count { it == "preferred:close" })
            assertEquals(1, calls.count { it == "fallback:close" })
        }
    }
}
