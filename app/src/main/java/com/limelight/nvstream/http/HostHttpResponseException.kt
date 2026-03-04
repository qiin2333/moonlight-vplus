package com.limelight.nvstream.http

import java.io.IOException

class HostHttpResponseException(
    private val errorCode: Int,
    private val errorMsg: String
) : IOException() {

    companion object {
        private const val serialVersionUID = 1543508830807804222L
    }

    fun getErrorCode(): Int = errorCode

    fun getErrorMessage(): String = errorMsg

    override val message: String
        get() = "Host PC returned error: $errorMsg (Error code: $errorCode)"
}
