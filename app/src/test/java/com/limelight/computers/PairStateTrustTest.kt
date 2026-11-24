package com.limelight.computers

import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairStateTrust
import okhttp3.tls.HeldCertificate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PairStateTrustTest {
    @Test
    fun untrustedNotPairedDoesNotDowngradeLocalPairing() {
        val current = pairedComputer(hasServerCert = false)
        val polled = notPairedPoll(trustedByCert = false)

        val sanitized = PairStateTrust.sanitizePollResult(current, polled)

        assertEquals(PairingManager.PairState.PAIRED, sanitized.pairState)
    }

    @Test
    fun localServerCertificateIsEnoughToProtectColdStartState() {
        val current = ComputerDetails().apply {
            serverCert = serverCertificate
            pairState = null
        }
        val polled = notPairedPoll(trustedByCert = false)

        val sanitized = PairStateTrust.sanitizePollResult(current, polled)

        assertEquals(PairingManager.PairState.PAIRED, sanitized.pairState)
        assertSame(serverCertificate, sanitized.serverCert)
    }

    @Test
    fun localServerCertificateProtectsAlreadyMutatedUpdate() {
        val details = notPairedPoll(trustedByCert = false).apply {
            serverCert = serverCertificate
        }

        val sanitized = PairStateTrust.sanitizePollResult(details, details)

        assertEquals(PairingManager.PairState.PAIRED, sanitized.pairState)
        assertSame(serverCertificate, sanitized.serverCert)
    }

    @Test
    fun trustedNotPairedCanDowngradeLocalPairing() {
        val current = pairedComputer(hasServerCert = true)
        val polled = notPairedPoll(trustedByCert = true)

        val sanitized = PairStateTrust.sanitizePollResult(current, polled)

        assertSame(polled, sanitized)
        assertEquals(PairingManager.PairState.NOT_PAIRED, sanitized.pairState)
    }

    @Test
    fun trustedPairStateRequiresTrustedServerInfo() {
        val trusted = pairedComputer(hasServerCert = true).apply {
            serverInfoTrustedByCert = true
            pairStateTrusted = true
        }
        val untrusted = pairedComputer(hasServerCert = true).apply {
            serverInfoTrustedByCert = false
            pairStateTrusted = false
        }
        val missingCert = pairedComputer(hasServerCert = false).apply {
            serverInfoTrustedByCert = true
            pairStateTrusted = true
        }

        assertEquals(true, PairStateTrust.isTrustedPaired(trusted))
        assertEquals(false, PairStateTrust.isTrustedPaired(untrusted))
        assertEquals(false, PairStateTrust.isTrustedPaired(missingCert))
    }

    @Test
    fun onlyAuthenticatedNotPairedStateCanClearLocalPairing() {
        val trusted = notPairedPoll(trustedByCert = true)
        val untrusted = notPairedPoll(trustedByCert = false)
        val authenticated401 = notPairedPoll(trustedByCert = false).apply {
            pairStateTrusted = true
        }

        assertEquals(true, PairStateTrust.isTrustedNotPaired(trusted))
        assertEquals(false, PairStateTrust.isTrustedNotPaired(untrusted))
        assertEquals(true, PairStateTrust.isTrustedNotPaired(authenticated401))
    }

    @Test
    fun authenticated401CannotBeOverriddenByUntrustedHttpBody() {
        val resolved = PairStateTrust.resolvePairState(
            PairingManager.PairState.PAIRED,
            serverInfoTrustedByCert = false,
            pairStateTrusted = true
        )

        assertEquals(PairingManager.PairState.NOT_PAIRED, resolved)
    }

    @Test
    fun untrustedNotPairedIsAcceptedForUnpairedHost() {
        val current = ComputerDetails()
        val polled = notPairedPoll(trustedByCert = false)

        val sanitized = PairStateTrust.sanitizePollResult(current, polled)

        assertSame(polled, sanitized)
        assertEquals(PairingManager.PairState.NOT_PAIRED, sanitized.pairState)
    }

    @Test
    fun sameHostIdentityAcceptsKnownMatchingUuid() {
        assertTrue(PairStateTrust.isSameHostIdentity(HOST_A_UUID, HOST_A_UUID))
    }

    @Test
    fun sameHostIdentityRejectsDifferentHostsSharingOneAddress() {
        // Several hosts can answer on the same address (for example, separate Sunshine
        // instances that reuse one port on the same machine). They are still different
        // hosts and must never share poll results.
        assertFalse(PairStateTrust.isSameHostIdentity(HOST_A_UUID, HOST_B_UUID))
    }

    @Test
    fun sameHostIdentityAcceptsUnknownIdentity() {
        // Manual add / first discovery has no UUID yet.
        assertTrue(PairStateTrust.isSameHostIdentity(null, HOST_B_UUID))
        assertTrue(PairStateTrust.isSameHostIdentity(HOST_A_UUID, null))
        assertTrue(PairStateTrust.isSameHostIdentity(null, null))
    }

    private fun pairedComputer(hasServerCert: Boolean): ComputerDetails {
        return ComputerDetails().apply {
            pairState = PairingManager.PairState.PAIRED
            if (hasServerCert) {
                serverCert = serverCertificate
            }
        }
    }

    private fun notPairedPoll(trustedByCert: Boolean): ComputerDetails {
        return ComputerDetails().apply {
            state = ComputerDetails.State.ONLINE
            pairState = PairingManager.PairState.NOT_PAIRED
            serverInfoTrustedByCert = trustedByCert
            pairStateTrusted = trustedByCert
        }
    }

    private companion object {
        const val HOST_A_UUID = "11111111-1111-1111-1111-111111111111"
        const val HOST_B_UUID = "22222222-2222-2222-2222-222222222222"
        val serverCertificate = HeldCertificate.Builder().commonName("test-server").build().certificate
    }
}
