package com.limelight.nvstream.http

import com.limelight.LimeLog

internal object PairStateTrust {
    fun sanitizePollResult(
        current: ComputerDetails,
        polled: ComputerDetails,
        source: String = "poll"
    ): ComputerDetails {
        if (!shouldPreserveLocalPairing(current, polled)) {
            return polled
        }

        LimeLog.warning(
            "Ignoring untrusted NOT_PAIRED $source for locally paired host ${current.name ?: current.uuid}"
        )
        return ComputerDetails(polled).apply {
            pairState = PairingManager.PairState.PAIRED
            serverCert = current.serverCert
            serverInfoTrustedByCert = false
        }
    }

    fun shouldPreserveLocalPairing(current: ComputerDetails, incoming: ComputerDetails): Boolean {
        return hasLocalPairing(current) && isUntrustedNotPaired(incoming)
    }

    fun isTrustedPaired(details: ComputerDetails): Boolean {
        return details.pairState == PairingManager.PairState.PAIRED &&
                details.serverInfoTrustedByCert &&
                details.pairStateTrusted &&
                details.serverCert != null
    }

    fun isTrustedNotPaired(details: ComputerDetails): Boolean {
        return details.state == ComputerDetails.State.ONLINE &&
                details.pairState == PairingManager.PairState.NOT_PAIRED &&
                details.pairStateTrusted
    }

    fun resolvePairState(
        reportedState: PairingManager.PairState?,
        serverInfoTrustedByCert: Boolean,
        pairStateTrusted: Boolean
    ): PairingManager.PairState? {
        return if (pairStateTrusted && !serverInfoTrustedByCert) {
            PairingManager.PairState.NOT_PAIRED
        } else {
            reportedState
        }
    }

    private fun isUntrustedNotPaired(details: ComputerDetails): Boolean {
        return details.state == ComputerDetails.State.ONLINE &&
                details.pairState == PairingManager.PairState.NOT_PAIRED &&
                !details.pairStateTrusted
    }

    private fun hasLocalPairing(details: ComputerDetails): Boolean {
        return details.serverCert != null ||
                details.pairState == PairingManager.PairState.PAIRED
    }
}
