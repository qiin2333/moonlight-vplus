package com.limelight.computers

import com.limelight.nvstream.http.ComputerDetails

interface ComputerManagerListener {
    fun notifyComputerUpdated(details: ComputerDetails)
}
