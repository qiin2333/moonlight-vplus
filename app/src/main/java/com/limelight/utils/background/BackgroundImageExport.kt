package com.limelight.utils.background

import java.io.File
import java.io.IOException
import java.io.OutputStream

internal object BackgroundImageExport {
    fun write(directory: File, extension: String, writer: (OutputStream) -> Unit): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Background directory unavailable")
        // Atomic unique creation means failure cleanup can never delete an existing user image.
        val file = File.createTempFile("vplus-background-${System.currentTimeMillis()}-", ".$extension", directory)
        var complete = false
        try {
            file.outputStream().use(writer)
            complete = true
            return file
        } finally {
            if (!complete) file.delete()
        }
    }
}
