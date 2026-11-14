package com.limelight.filetransfer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny HTTP server that lets a PC browser push files to this device.
 *
 * The streaming protocol has no file channel, so the transfer runs next to it: the user opens
 * `http://<device>:<port>/<token>` in the browser of the streamed PC and drops files on the page.
 * Each file arrives as one `PUT /<token>/upload?name=...` request whose body is the raw file,
 * which keeps the server free of multipart parsing and lets files of any size stream to disk.
 *
 * The token is part of every path. Requests without it get a bare 404, and the server shuts
 * itself down after [MAX_REJECTED_REQUESTS] of them so the token cannot be guessed by brute force.
 */
class FileReceiveServer(
    private val token: String,
    private val sink: FileSink,
    private val page: String,
    private val listener: Listener,
) {
    /** Where received bytes end up. Kept separate from Android storage so it can be tested on the JVM. */
    interface FileSink {
        /** Opens the destination for a new file. */
        @Throws(IOException::class)
        fun create(name: String): Target
    }

    interface Target {
        val output: OutputStream

        /** Makes the completed file visible to the user. */
        @Throws(IOException::class)
        fun commit()

        /** Removes whatever was written of an incomplete file. */
        fun abort()
    }

    interface Listener {
        fun onFileReceived(name: String, bytes: Long)
        fun onFileFailed(name: String)

        /** The server stopped on its own after too many requests with a wrong token. */
        fun onLockedOut()
    }

    private var serverSocket: ServerSocket? = null
    private var workers: ExecutorService? = null
    private val rejectedRequests = AtomicInteger()

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    val port: Int
        get() = serverSocket?.localPort ?: -1

    /**
     * Starts listening on [preferredPort], or on any free port when that one is taken.
     * @return the port actually bound
     */
    @Synchronized
    @Throws(IOException::class)
    fun start(preferredPort: Int, bindAddress: InetAddress? = null): Int {
        stop()
        val socket = try {
            ServerSocket(preferredPort, BACKLOG, bindAddress)
        } catch (e: IOException) {
            ServerSocket(0, BACKLOG, bindAddress)
        }
        serverSocket = socket
        rejectedRequests.set(0)
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "FileReceive").apply { isDaemon = true }
        }
        workers = pool
        pool.execute { acceptLoop(socket, pool) }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            try {
                pool.execute { handle(client) }
            } catch (e: RuntimeException) {
                // The pool was shut down between accept() and execute().
                closeQuietly(client)
                return
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream(), BUFFER_SIZE)
            val output = client.getOutputStream()

            val requestLine = readLine(input)?.split(' ') ?: return
            if (requestLine.size < 2) {
                respond(output, 400, "Bad Request")
                return
            }
            val method = requestLine[0].uppercase(Locale.ROOT)
            val target = requestLine[1]

            var contentLength = -1L
            var expectContinue = false
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val headerName = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                val headerValue = line.substring(separator + 1).trim()
                when (headerName) {
                    "content-length" -> contentLength = headerValue.toLongOrNull() ?: -1L
                    "expect" -> expectContinue = headerValue.equals("100-continue", ignoreCase = true)
                }
            }

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val segments = path.trim('/').split('/')

            if (segments.firstOrNull() != token) {
                respond(output, 404, "Not Found")
                if (rejectedRequests.incrementAndGet() >= MAX_REJECTED_REQUESTS) {
                    stop()
                    listener.onLockedOut()
                }
                return
            }

            when {
                method == "GET" && segments.size == 1 ->
                    respond(output, 200, "OK", "text/html; charset=utf-8", page.toByteArray(Charsets.UTF_8))
                method == "PUT" && segments.size == 2 && segments[1] == "upload" -> {
                    if (contentLength < 0) {
                        respond(output, 411, "Length Required")
                        return
                    }
                    if (expectContinue) {
                        output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.flush()
                    }
                    receive(input, output, sanitizeFileName(queryParameter(query, "name")), contentLength)
                }
                else -> respond(output, 404, "Not Found")
            }
        } catch (_: IOException) {
            // The browser went away; nothing to report beyond the per-file failure.
        } finally {
            closeQuietly(client)
        }
    }

    private fun receive(input: InputStream, output: OutputStream, name: String, length: Long) {
        val target = try {
            sink.create(name)
        } catch (e: IOException) {
            listener.onFileFailed(name)
            respond(output, 500, "Internal Server Error")
            return
        }

        var completed = false
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = length
            target.output.use { fileOutput ->
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Upload ended early")
                    fileOutput.write(buffer, 0, read)
                    remaining -= read
                }
            }
            target.commit()
            completed = true
        } catch (e: IOException) {
            // Reported below, after the partial file is gone.
        } finally {
            if (!completed) target.abort()
        }

        if (completed) {
            listener.onFileReceived(name, length)
            respond(output, 200, "OK")
        } else {
            listener.onFileFailed(name)
            try {
                respond(output, 500, "Internal Server Error")
            } catch (_: IOException) {
            }
        }
    }

    private fun respond(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String = "text/plain; charset=utf-8",
        body: ByteArray = reason.toByteArray(Charsets.UTF_8),
    ) {
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val MAX_REJECTED_REQUESTS = 20
        private const val BACKLOG = 8
        private const val BUFFER_SIZE = 64 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_LINE_LENGTH = 8 * 1024
        private const val MAX_NAME_LENGTH = 120
        private const val FALLBACK_NAME = "file"

        /** Reads one CRLF-terminated header line, or null at end of stream. */
        @Throws(IOException::class)
        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
                if (value == '\n'.code) break
                if (value != '\r'.code) line.write(value)
                if (line.size() > MAX_LINE_LENGTH) throw SocketException("Header line too long")
            }
            return line.toString("ISO-8859-1")
        }

        @JvmStatic
        fun queryParameter(query: String, name: String): String {
            for (pair in query.split('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0 || pair.substring(0, separator) != name) continue
                return try {
                    URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    ""
                }
            }
            return ""
        }

        /**
         * Reduces a browser-supplied name to a plain file name: no directories, no control or
         * reserved characters, no leading dots, and a bounded length that keeps the extension.
         */
        @JvmStatic
        fun sanitizeFileName(raw: String): String {
            val leaf = raw.replace('\\', '/').substringAfterLast('/')
            val cleaned = leaf
                .filter { it.code >= 0x20 && it.code != 0x7F && it !in "<>:\"|?*" }
                .trim()
                .trimStart('.')
                .trim()
            if (cleaned.isEmpty()) return FALLBACK_NAME
            if (cleaned.length <= MAX_NAME_LENGTH) return cleaned
            val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..10 }
            return if (extension == null) {
                cleaned.take(MAX_NAME_LENGTH)
            } else {
                cleaned.take(MAX_NAME_LENGTH - extension.length - 1) + "." + extension
            }
        }
    }
}
