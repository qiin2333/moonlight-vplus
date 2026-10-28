package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrV3Decoder
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AuthoredV3FixtureTest {
    @Test fun actualHostWireFixturesRetainUniqueEventsThroughTheClientTimeline() {
        val external = System.getProperty("authoredIrFixtureDir")?.let(::File)
        val directory = external ?: sequenceOf(File("src/debug/assets/haptics/generated"),
            File("app/src/debug/assets/haptics/generated")).first { it.isDirectory }
        val files = directory.listFiles { file -> file.extension == "irv3bin" }!!.sortedBy { it.name }
        assertTrue("Host-generated fixtures are required", files.size >= if (external == null) 5 else 60)
        for (file in files) {
            val frames = Ds5HapticsIrV3Decoder.decode(file.readBytes())
            val expected = frames.flatMap { it.events }.filter { it.confidence > 0f &&
                maxOf(it.leftStrength, it.rightStrength) > 0f }.map { it.eventId }.toSet()
            val timeline = AuthoredV3Timeline()
            val delivered = mutableListOf<Int>()
            frames.forEach { frame ->
                val now = frame.windowEndUs + 1_000_000
                timeline.accept(frame, now)
                timeline.poll(now)?.event?.let { delivered += it.value.eventId }
                assertTrue(timeline.queuedEvents <= AuthoredV3Timeline.MAX_EVENTS)
            }
            assertEquals("${file.name}: duplicate event replay", delivered.size, delivered.toSet().size)
            assertEquals("${file.name}: an actual host event was lost", expected, delivered.toSet())
            if (file.name == "pulse_density_50.irv3bin") assertTrue("Dense source onset coverage", delivered.size >= 90)
            println("V3 wire timeline ${file.name}: ${delivered.size}/${expected.size} unique events")
        }
    }
}
