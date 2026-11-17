package com.limelight.filetransfer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FileReceiveServerTest {
    private class MemorySink : FileReceiveServer.FileSink {
        val committed = LinkedHashMap<String, ByteArray>()
        val aborted = CopyOnWriteArrayList<String>()

        override fun create(name: String): FileReceiveServer.Target {
            val buffer = ByteArrayOutputStream()
            return object : FileReceiveServer.Target {
                override val output: OutputStream = buffer
                override fun commit() {
                    synchronized(committed) { committed[name] = buffer.toByteArray() }
                }
                override fun abort() {
                    aborted.add(name)
                }
            }
        }
    }

    private class RecordingListener : FileReceiveServer.Listener {
        val received = CopyOnWriteArrayList<String>()
        val lockedOut = CountDownLatch(1)
        override fun onFileReceived(name: String, bytes: Long) {
            received.add("$name:$bytes")
        }
        override fun onFileFailed(name: String) = Unit
        override fun onLockedOut() = lockedOut.countDown()
    }

    private val sink = MemorySink()
    private val listener = RecordingListener()
    private val server = FileReceiveServer("123456", sink, "<html>page</html>", listener)
    private val loopback = InetAddress.getByName("127.0.0.1")

    @After
    fun tearDown() = server.stop()

    private fun open(path: String, method: String): HttpURLConnection {
        val port = if (server.isRunning) server.port else server.start(0, loopback)
        return (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
        }
    }

    @Test
    fun servesThePageOnlyBehindTheToken() {
        val page = open("/123456", "GET")
        assertEquals(200, page.responseCode)
        assertEquals("<html>page</html>", page.inputStream.readBytes().toString(Charsets.UTF_8))

        assertEquals(404, open("/", "GET").responseCode)
        assertEquals(404, open("/000000", "GET").responseCode)
    }

    @Test
    fun storesAnUploadedFileUnderItsSanitizedName() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val upload = open("/123456/upload?name=..%2F..%2F%ED%95%9C%EA%B8%80+report.txt", "PUT")
        upload.doOutput = true
        upload.setFixedLengthStreamingMode(payload.size)
        upload.outputStream.use { it.write(payload) }

        assertEquals(200, upload.responseCode)
        assertArrayEquals(payload, sink.committed["한글 report.txt"])
        assertEquals(listOf("한글 report.txt:200000"), listener.received.toList())
        assertTrue(sink.aborted.isEmpty())
    }

    @Test
    fun rejectsUploadsWithoutTheToken() {
        val upload = open("/999999/upload?name=a.txt", "PUT")
        upload.doOutput = true
        upload.setFixedLengthStreamingMode(1)
        try {
            upload.outputStream.use { it.write(1) }
            assertEquals(404, upload.responseCode)
        } catch (_: java.io.IOException) {
            // The server may close the socket before the body is sent.
        }
        assertTrue(sink.committed.isEmpty())
    }

    @Test
    fun stopsAfterRepeatedWrongTokens() {
        repeat(FileReceiveServer.MAX_REJECTED_REQUESTS) {
            try {
                open("/wrong$it", "GET").responseCode
            } catch (_: java.io.IOException) {
            }
        }
        assertTrue(listener.lockedOut.await(5, TimeUnit.SECONDS))
        assertFalse(server.isRunning)
    }

    @Test
    fun sanitizesFileNames() {
        assertEquals("a.txt", FileReceiveServer.sanitizeFileName("C:\\Users\\me\\a.txt"))
        assertEquals("passwd", FileReceiveServer.sanitizeFileName("../../etc/passwd"))
        assertEquals("hidden", FileReceiveServer.sanitizeFileName(".hidden"))
        assertEquals("ab.txt", FileReceiveServer.sanitizeFileName("a<>:\"|?*b.txt"))
        assertEquals("file", FileReceiveServer.sanitizeFileName(""))
        assertEquals("file", FileReceiveServer.sanitizeFileName("../"))

        val long = FileReceiveServer.sanitizeFileName("x".repeat(300) + ".mp4")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".mp4"))
    }

    @Test
    fun readsQueryParameters() {
        assertEquals("a b.txt", FileReceiveServer.queryParameter("x=1&name=a+b.txt", "name"))
        assertEquals("", FileReceiveServer.queryParameter("x=1", "name"))
        assertEquals("", FileReceiveServer.queryParameter("name=%ZZ", "name"))
    }
}
