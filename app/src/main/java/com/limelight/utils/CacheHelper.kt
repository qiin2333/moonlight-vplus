package com.limelight.utils

import java.io.*

object CacheHelper {
    fun openPath(createPath: Boolean, root: File, vararg path: String): File {
        require(path.isNotEmpty()) { "Cache path must contain at least one component" }

        val canonicalRoot = root.canonicalFile
        var candidate = canonicalRoot
        for (component in path) {
            validatePathComponent(component)
            candidate = File(candidate, component)
        }

        val canonicalCandidate = candidate.canonicalFile
        require(isPathWithinRoot(canonicalRoot, canonicalCandidate)) {
            "Cache path escapes its root"
        }

        if (createPath) {
            val parent = canonicalCandidate.parentFile
                ?: throw IOException("Cache path has no parent")
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Unable to create cache directory")
            }
        }
        return canonicalCandidate
    }

    private fun validatePathComponent(component: String) {
        require(component.isNotEmpty()) { "Cache path component must not be empty" }
        require(component != "." && component != "..") { "Invalid cache path component" }
        require('/' !in component && '\\' !in component && '\u0000' !in component) {
            "Cache path component contains a separator"
        }
        require(!File(component).isAbsolute) { "Cache path component must be relative" }
    }

    internal fun isPathWithinRoot(canonicalRoot: File, canonicalCandidate: File): Boolean {
        val rootPath = canonicalRoot.path
        val rootPrefix = if (rootPath.endsWith(File.separator)) {
            rootPath
        } else {
            rootPath + File.separator
        }
        return canonicalCandidate != canonicalRoot && canonicalCandidate.path.startsWith(rootPrefix)
    }

    fun getFileSize(root: File, vararg path: String): Long {
        return openPath(false, root, *path).length()
    }

    fun deleteCacheFile(root: File, vararg path: String): Boolean {
        return openPath(false, root, *path).delete()
    }

    fun cacheFileExists(root: File, vararg path: String): Boolean {
        return openPath(false, root, *path).exists()
    }

    @Throws(FileNotFoundException::class)
    fun openCacheFileForInput(root: File, vararg path: String): InputStream {
        return BufferedInputStream(FileInputStream(openPath(false, root, *path)))
    }

    @Throws(FileNotFoundException::class)
    fun openCacheFileForOutput(root: File, vararg path: String): OutputStream {
        return BufferedOutputStream(FileOutputStream(openPath(true, root, *path)))
    }

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

    @Throws(IOException::class)
    fun writeStringToOutputStream(out: OutputStream, str: String) {
        out.write(str.toByteArray(Charsets.UTF_8))
    }
}
