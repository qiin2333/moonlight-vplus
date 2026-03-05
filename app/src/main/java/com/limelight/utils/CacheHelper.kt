package com.limelight.utils

import java.io.*

object CacheHelper {
    @JvmStatic
    fun openPath(createPath: Boolean, root: File, vararg path: String): File {
        var f = root
        for (i in path.indices) {
            val component = path[i]

            if (i == path.size - 1) {
                // This is the file component so now we create parent directories
                if (createPath) {
                    f.mkdirs()
                }
            }

            f = File(f, component)
        }
        return f
    }

    @JvmStatic
    fun getFileSize(root: File, vararg path: String): Long {
        return openPath(false, root, *path).length()
    }

    @JvmStatic
    fun deleteCacheFile(root: File, vararg path: String): Boolean {
        return openPath(false, root, *path).delete()
    }

    @JvmStatic
    fun cacheFileExists(root: File, vararg path: String): Boolean {
        return openPath(false, root, *path).exists()
    }

    @JvmStatic
    @Throws(FileNotFoundException::class)
    fun openCacheFileForInput(root: File, vararg path: String): InputStream {
        return BufferedInputStream(FileInputStream(openPath(false, root, *path)))
    }

    @JvmStatic
    @Throws(FileNotFoundException::class)
    fun openCacheFileForOutput(root: File, vararg path: String): OutputStream {
        return BufferedOutputStream(FileOutputStream(openPath(true, root, *path)))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeInputStreamToOutputStream(input: InputStream, out: OutputStream, maxLength: Long) {
        var remaining = maxLength
        val buf = ByteArray(4096)
        var bytesRead: Int

        while (input.read(buf).also { bytesRead = it } != -1) {
            remaining -= bytesRead
            if (remaining <= 0) {
                throw IOException("Stream exceeded max size")
            }
            out.write(buf, 0, bytesRead)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readInputStreamToString(input: InputStream): String {
        val r = InputStreamReader(input)

        val sb = StringBuilder()
        val buf = CharArray(256)
        var bytesRead: Int
        while (r.read(buf).also { bytesRead = it } != -1) {
            sb.append(buf, 0, bytesRead)
        }

        try {
            input.close()
        } catch (_: IOException) {
        }

        return sb.toString()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeStringToOutputStream(out: OutputStream, str: String) {
        out.write(str.toByteArray(Charsets.UTF_8))
    }
}
