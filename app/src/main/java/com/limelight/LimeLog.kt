package com.limelight

import java.io.IOException
import java.util.logging.FileHandler
import java.util.logging.Logger

object LimeLog {
    private val LOGGER: Logger = Logger.getLogger(LimeLog::class.java.name)

    @JvmStatic
    fun info(msg: String) {
        LOGGER.info(msg)
    }

    @JvmStatic
    fun warning(msg: String) {
        LOGGER.warning(msg)
    }

    @JvmStatic
    fun severe(msg: String) {
        LOGGER.severe(msg)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setFileHandler(fileName: String) {
        LOGGER.addHandler(FileHandler(fileName))
    }
}
