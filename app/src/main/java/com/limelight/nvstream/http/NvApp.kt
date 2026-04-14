package com.limelight.nvstream.http

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.limelight.LimeLog

class NvApp {
    var appName: String = ""
    var appId: Int = 0
        set(value) {
            field = value
            initialized = true
        }
    var initialized: Boolean = false
        private set
    var hdrSupported: Boolean = false

    var cmdList: JsonArray? = null

    constructor()

    constructor(appName: String) {
        this.appName = appName
    }

    constructor(appName: String, appId: Int, hdrSupported: Boolean) {
        this.appName = appName
        this.appId = appId
        this.hdrSupported = hdrSupported
    }

    fun setAppId(appId: String) {
        try {
            this.appId = appId.toInt()
        } catch (e: NumberFormatException) {
            LimeLog.warning("Malformed app ID: $appId")
        }
    }

    fun isHdrSupported(): Boolean = this.hdrSupported

    fun isInitialized(): Boolean = this.initialized

    fun setCmdList(cmdList: String) {
        this.cmdList = Gson().fromJson(cmdList, JsonArray::class.java)
    }

    override fun toString(): String {
        return buildString {
            append("Name: ").append(appName).append("\n")
            append("HDR Supported: ").append(if (hdrSupported) "Yes" else "Unknown").append("\n")
            append("ID: ").append(appId).append("\n")
            if (cmdList != null) append("Super CMDs: ").append(cmdList.toString()).append("\n")
        }
    }
}
