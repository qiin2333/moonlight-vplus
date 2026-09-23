package com.limelight.nvstream.http

import java.io.FileNotFoundException

class HostHttpNotFoundException(
    requestDescription: String,
    val isPairingEndpoint: Boolean
) : FileNotFoundException(requestDescription)
