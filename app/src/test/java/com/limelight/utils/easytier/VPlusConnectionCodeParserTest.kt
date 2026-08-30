package com.limelight.utils.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VPlusConnectionCodeParserTest {
    @Test
    fun legacyPairingCodeRemainsSupported() {
        val code = VPlusConnectionCodeParser.parse(
                "moonlight://pair?host=192.168.1.5&port=47989&pin=1234&name=PC",
                nowEpochSeconds = 100
        )

        assertEquals("192.168.1.5", code.host)
        assertEquals(47989, code.port)
        assertNull(code.easyTierProfile)
    }

    @Test
    fun versionTwoParsesSafeEasyTierProfile() {
        val code = VPlusConnectionCodeParser.parse(
                "moonlight://pair?v=2&host=10.86.24.1&port=47989&pin=1234" +
                        "&profile=host-abc&et_host=10.86.24.1&et_name=vplus-host-abc" +
                        "&et_secret=0123456789abcdef0123456789abcdef" +
                        "&et_peer=udp%3A%2F%2Fpublic.easytier.top%3A11010&expires=1000",
                nowEpochSeconds = 100
        )

        assertNotNull(code.easyTierProfile)
        assertEquals(true, EasyTierTomlCodec.parseConfig(
                EasyTierTomlCodec.buildConnectionProfile(code.easyTierProfile!!)
        ).dhcp)
    }

    @Test(expected = IllegalArgumentException::class)
    fun versionTwoRejectsExpiredCode() {
        VPlusConnectionCodeParser.parse(
                "moonlight://pair?v=2&host=10.86.24.1&pin=1234&profile=host-abc" +
                        "&et_host=10.86.24.1&et_name=vplus-host-abc" +
                        "&et_secret=0123456789abcdef&et_peer=udp%3A%2F%2Fpeer.example%3A11010&expires=99",
                nowEpochSeconds = 100
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun versionTwoRejectsTrafficHijackingPeerScheme() {
        VPlusConnectionCodeParser.parse(
                "moonlight://pair?v=2&host=10.86.24.1&pin=1234&profile=host-abc" +
                        "&et_host=10.86.24.1&et_name=vplus-host-abc" +
                        "&et_secret=0123456789abcdef&et_peer=file%3A%2F%2Fevil&expires=1000",
                nowEpochSeconds = 100
        )
    }
}
