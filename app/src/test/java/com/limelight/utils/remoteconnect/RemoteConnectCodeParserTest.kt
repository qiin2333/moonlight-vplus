package com.limelight.utils.remoteconnect

import com.limelight.utils.easytier.EasyTierTomlCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteConnectCodeParserTest {
    @Test
    fun legacyPairingCodeRemainsSupported() {
        val code = RemoteConnectCodeParser.parse(
                "moonlight://pair?host=192.168.1.5&port=47989&pin=1234&name=PC",
                nowEpochSeconds = 100
        )

        assertEquals("192.168.1.5", code.host)
        assertEquals(47989, code.port)
        assertNull(code.easyTierProfile)
    }

    @Test
    fun pendingConnectionSurvivesControllerRecreationUntilConsumed() {
        val code = RemoteConnectCodeParser.parse(
                "moonlight://pair?host=192.168.1.5&port=47989&pin=1234&name=PC",
                nowEpochSeconds = 100
        )

        PendingRemoteConnectState.consume()
        PendingRemoteConnectState.stage(code)
        assertEquals(code, PendingRemoteConnectState.peek())
        assertEquals(code, PendingRemoteConnectState.consume())
        assertNull(PendingRemoteConnectState.peek())
    }

    @Test
    fun versionTwoParsesSafeEasyTierProfile() {
        val code = RemoteConnectCodeParser.parse(
                "moonlight://pair?v=2&host=10.86.24.1&port=47989&pin=1234" +
                        "&profile=host-abc&et_host=10.86.24.1&et_name=remote-host-abc" +
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
        RemoteConnectCodeParser.parse(
                "moonlight://pair?v=2&host=10.86.24.1&pin=1234&profile=host-abc" +
                        "&et_host=10.86.24.1&et_name=remote-host-abc" +
                        "&et_secret=0123456789abcdef&et_peer=udp%3A%2F%2Fpeer.example%3A11010&expires=99",
                nowEpochSeconds = 100
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun versionTwoRejectsTrafficHijackingPeerScheme() {
        RemoteConnectCodeParser.parse(
                "moonlight://pair?v=2&host=10.86.24.1&pin=1234&profile=host-abc" +
                        "&et_host=10.86.24.1&et_name=remote-host-abc" +
                        "&et_secret=0123456789abcdef&et_peer=file%3A%2F%2Fevil&expires=1000",
                nowEpochSeconds = 100
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedPresentPort() {
        RemoteConnectCodeParser.parse("moonlight://pair?host=192.168.1.5&port=abc&pin=1234")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedPresentVersion() {
        RemoteConnectCodeParser.parse("moonlight://pair?v=abc&host=192.168.1.5&pin=1234")
    }

    @Test
    fun hostIssuedProfileDisablesRouteAndDnsTakeover() {
        val profile = RemoteConnectCodeParser.parse(
                "moonlight://pair?v=2&host=100.86.24.1&pin=1234&profile=host-abc" +
                        "&et_host=100.86.24.1&et_name=remote-host-abc" +
                        "&et_secret=0123456789abcdef&et_peer=udp%3A%2F%2Fpeer.example%3A11010&expires=1000",
                nowEpochSeconds = 100
        ).easyTierProfile!!
        val toml = EasyTierTomlCodec.buildConnectionProfile(profile)

        assertEquals(true, toml.contains("exit_nodes = []"))
        assertEquals(true, toml.contains("routes = []"))
        assertEquals(true, toml.contains("proxy_network = []"))
        assertEquals(true, toml.contains("accept_dns = false"))
        assertEquals(true, toml.contains("enable_exit_node = false"))
    }
}
