package com.limelight.nvstream.http

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PairingManagerPolicyTest {
    @Test
    fun protocolConflictIsReportedAsPairingAlreadyInProgress() {
        assertEquals(
            PairingManager.PairState.ALREADY_IN_PROGRESS,
            PairingManager.classifyGetServerCertResponse(409, "0")
        )
    }

    @Test
    fun unsuccessfulResponseWithoutConflictIsPairingFailure() {
        assertEquals(
            PairingManager.PairState.FAILED,
            PairingManager.classifyGetServerCertResponse(null, "0")
        )
        assertEquals(
            PairingManager.PairState.FAILED,
            PairingManager.classifyGetServerCertResponse(null, null)
        )
    }

    @Test
    fun successfulPairedValueContinuesHandshake() {
        assertNull(PairingManager.classifyGetServerCertResponse(null, "1"))
        assertNull(PairingManager.classifyGetServerCertResponse(400, "1"))
    }

    @Test
    fun cleanupIOExceptionIsReturnedInsteadOfReplacingPairingResult() {
        val expected = FileNotFoundException("unpair")

        val actual = PairingManager.runBestEffortCleanup { throw expected }

        assertSame(expected, actual)
    }

    @Test
    fun successfulCleanupHasNoError() {
        assertNull(PairingManager.runBestEffortCleanup {})
    }
}
