package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class KishiSensaPacketTest {
    @Test fun matchesObservedStereoFrame() {
        val points = List(4) { KishiSensaPacket.Point(21.0 / 63, 30 + 23.5 * 370 / 127) }
        val bytes = KishiSensaPacket.frame(listOf(listOf(points), listOf(points)))
        val expected = "51 54 ba a5 d5 2e a9 72 a9 75 4b aa 5d 52 e0"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test fun matchesObservedSilence() {
        val points = List(4) { KishiSensaPacket.Point(0.0, 30 + 1.5 * 370 / 127) }
        val expected = "51 00 08 00 40 02 00 12 00 10 00 80 04 00 20"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, KishiSensaPacket.frame(listOf(listOf(points), listOf(points))))
    }

    @Test fun fullThreeBandFrameFitsOneReport() {
        val data = KishiSensaPacket.frame(List(2) { List(3) { List(4) { KishiSensaPacket.Point(1.0, 400.0) } } })
        assertEquals(41, data.size)
        val report = KishiSensaPacket.report(0x0e, data)
        assertEquals(64, report.size)
        assertEquals(45, report[1].toInt())
        assertTrue(report.drop(data.size + 5).all { it == 0.toByte() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPoints() {
        KishiSensaPacket.frame(List(2) { listOf(List(4) { KishiSensaPacket.Point(Double.NaN, 100.0) }) })
    }
}
