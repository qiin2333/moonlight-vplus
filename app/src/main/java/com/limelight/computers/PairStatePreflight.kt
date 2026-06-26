package com.limelight.computers

import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.PairStateTrust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PairStatePreflight {
    fun hasTrustedPairState(computer: ComputerDetails): Boolean {
        return PairStateTrust.isTrustedPaired(computer)
    }

    suspend fun isConfirmedNotPaired(
        computer: ComputerDetails,
        binder: ComputerManagerService.ComputerManagerBinder,
        source: String
    ): Boolean {
        if (PairStateTrust.isTrustedPaired(computer)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            binder.verifyCurrentPairState(computer, source) ==
                    ComputerManagerService.PairStateVerificationResult.NOT_PAIRED
        }
    }
}
