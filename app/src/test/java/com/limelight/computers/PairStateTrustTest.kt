package com.limelight.computers

import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairStateTrust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
            serverCert = TestCertificates.serverCert
            pairState = null
        }
        val polled = notPairedPoll(trustedByCert = false)

        val sanitized = PairStateTrust.sanitizePollResult(current, polled)

        assertEquals(PairingManager.PairState.PAIRED, sanitized.pairState)
        assertSame(TestCertificates.serverCert, sanitized.serverCert)
    }

    @Test
    fun localServerCertificateProtectsAlreadyMutatedUpdate() {
        val details = notPairedPoll(trustedByCert = false).apply {
            serverCert = TestCertificates.serverCert
        }

        val sanitized = PairStateTrust.sanitizePollResult(details, details)

        assertEquals(PairingManager.PairState.PAIRED, sanitized.pairState)
        assertSame(TestCertificates.serverCert, sanitized.serverCert)
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
        }
        val untrusted = pairedComputer(hasServerCert = true).apply {
            serverInfoTrustedByCert = false
        }
        val missingCert = pairedComputer(hasServerCert = false).apply {
            serverInfoTrustedByCert = true
        }

        assertEquals(true, PairStateTrust.isTrustedPaired(trusted))
        assertEquals(false, PairStateTrust.isTrustedPaired(untrusted))
        assertEquals(false, PairStateTrust.isTrustedPaired(missingCert))
    }

    @Test
    fun untrustedNotPairedIsAcceptedForUnpairedHost() {
        val current = ComputerDetails()
        val polled = notPairedPoll(trustedByCert = false)

        val sanitized = PairStateTrust.sanitizePollResult(current, polled)

        assertSame(polled, sanitized)
        assertEquals(PairingManager.PairState.NOT_PAIRED, sanitized.pairState)
    }

    private fun pairedComputer(hasServerCert: Boolean): ComputerDetails {
        return ComputerDetails().apply {
            pairState = PairingManager.PairState.PAIRED
            if (hasServerCert) {
                serverCert = TestCertificates.serverCert
            }
        }
    }

    private fun notPairedPoll(trustedByCert: Boolean): ComputerDetails {
        return ComputerDetails().apply {
            state = ComputerDetails.State.ONLINE
            pairState = PairingManager.PairState.NOT_PAIRED
            serverInfoTrustedByCert = trustedByCert
        }
    }
}
