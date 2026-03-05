package com.limelight.nvstream.http

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.limelight.LimeLog

class NvApp {
    @JvmField
    var appName: String = ""
    @JvmField
    var appId: Int = 0
    @JvmField
    var initialized: Boolean = false
    @JvmField
    var hdrSupported: Boolean = false

    @JvmField
    var cmdList: JsonArray? = null

    constructor()

    constructor(appName: String) {
        this.appName = appName
    }

    constructor(appName: String, appId: Int, hdrSupported: Boolean) {
        this.appName = appName
        this.appId = appId
        this.hdrSupported = hdrSupported
        this.initialized = true
    }

    fun setAppName(appName: String) {
        this.appName = appName
    }

    fun setAppId(appId: String) {
        try {
            this.appId = appId.toInt()
            this.initialized = true
        } catch (e: NumberFormatException) {
            LimeLog.warning("Malformed app ID: $appId")
        }
    }

    fun setAppId(appId: Int) {
        this.appId = appId
        this.initialized = true
    }

    fun setHdrSupported(hdrSupported: Boolean) {
        this.hdrSupported = hdrSupported
    }

    fun getAppName(): String = this.appName

    fun getAppId(): Int = this.appId

    fun isHdrSupported(): Boolean = this.hdrSupported

    fun isInitialized(): Boolean = this.initialized

    fun setCmdList(cmdList: String) {
        this.cmdList = Gson().fromJson(cmdList, JsonArray::class.java)
    }

    fun getCmdList(): JsonArray? = this.cmdList

    override fun toString(): String {
        return buildString {
            append("Name: ").append(appName).append("\n")
            append("HDR Supported: ").append(if (hdrSupported) "Yes" else "Unknown").append("\n")
            append("ID: ").append(appId).append("\n")
            if (cmdList != null) append("Super CMDs: ").append(cmdList.toString()).append("\n")
        }
    }
}
